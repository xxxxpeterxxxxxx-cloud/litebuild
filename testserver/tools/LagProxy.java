import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LagProxy - a single-file TCP proxy that sits between the Minecraft
 * client and the test server to inject NETWORK-level faults the in-game
 * /fault command cannot reproduce:
 *
 *   - constant latency + random jitter (true wire delay, both directions)
 *   - loss bursts, simulated as TCP retransmission stalls (extra delay
 *     spikes), because dropping bytes inside a TCP stream would corrupt it
 *   - hard disconnect of all proxied clients ("kick")
 *
 * Run (no compilation needed, Java 21+):
 *   java LagProxy.java [--listen 25566] [--target 127.0.0.1:25565] \
 *                      [--delay 150] [--jitter 50] [--loss 5]
 *
 * Then connect the client to  localhost:25566 .
 * While it runs, type commands on stdin:
 *   delay <ms> | jitter <ms> | loss <pct> | c2sdelay <ms> | s2cdelay <ms>
 *   kick            - close all proxied connections (simulated net loss)
 *   status          - print current settings + byte counters
 *   quit            - exit
 */
public class LagProxy {

    static volatile int delayC2S = 0;
    static volatile int delayS2C = 0;
    static volatile int jitter = 0;
    static volatile int lossPct = 0;
    static volatile boolean running = true;

    static final List<Socket> CLIENTS = new CopyOnWriteArrayList<>();
    static final AtomicLong bytesC2S = new AtomicLong();
    static final AtomicLong bytesS2C = new AtomicLong();

    public static void main(String[] args) throws Exception {
        int listen = 25566;
        String targetHost = "127.0.0.1";
        int targetPort = 25565;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--listen" -> listen = Integer.parseInt(args[++i]);
                case "--target" -> {
                    String[] hp = args[++i].split(":");
                    targetHost = hp[0];
                    targetPort = Integer.parseInt(hp[1]);
                }
                case "--delay" -> { delayC2S = Integer.parseInt(args[++i]); delayS2C = delayC2S; }
                case "--jitter" -> jitter = Integer.parseInt(args[++i]);
                case "--loss" -> lossPct = Integer.parseInt(args[++i]);
                default -> System.err.println("ignoring unknown arg " + args[i]);
            }
        }

        final String host = targetHost;
        final int port = targetPort;
        final int listenPort = listen;

        Thread acceptor = new Thread(() -> acceptLoop(listenPort, host, port), "lagproxy-acceptor");
        acceptor.setDaemon(false);
        acceptor.start();

        System.out.println("[lagproxy] listening on :" + listenPort
            + " -> " + host + ":" + port
            + "  (stdin commands: delay|jitter|loss|kick|status|quit)");
        status();

        Scanner in = new Scanner(System.in);
        while (running && in.hasNextLine()) {
            String line = in.nextLine().trim();
            if (line.isEmpty()) continue;
            String[] t = line.split("\\s+");
            try {
                switch (t[0]) {
                    case "delay" -> { delayC2S = delayS2C = Integer.parseInt(t[1]); status(); }
                    case "c2sdelay" -> { delayC2S = Integer.parseInt(t[1]); status(); }
                    case "s2cdelay" -> { delayS2C = Integer.parseInt(t[1]); status(); }
                    case "jitter" -> { jitter = Integer.parseInt(t[1]); status(); }
                    case "loss" -> { lossPct = Integer.parseInt(t[1]); status(); }
                    case "kick" -> kickAll();
                    case "status" -> status();
                    case "quit", "exit" -> running = false;
                    default -> System.out.println("[lagproxy] unknown command: " + t[0]);
                }
            } catch (Exception e) {
                System.out.println("[lagproxy] bad command '" + line + "': " + e.getMessage());
            }
        }
        kickAll();
        System.exit(0);
    }

    static void acceptLoop(int listenPort, String host, int port) {
        try (ServerSocket ss = new ServerSocket(listenPort)) {
            while (running) {
                Socket client = ss.accept();
                try {
                    client.setTcpNoDelay(true);
                    Socket upstream = new Socket();
                    upstream.connect(new InetSocketAddress(host, port), 5000);
                    upstream.setTcpNoDelay(true);
                    CLIENTS.add(client);
                    CLIENTS.add(upstream);
                    System.out.println("[lagproxy] client connected: " + client.getRemoteSocketAddress());
                    Thread a = pump(client, upstream, delayC2SRef(), bytesC2S, "c2s");
                    Thread b = pump(upstream, client, delayS2CRef(), bytesS2C, "s2c");
                    a.start();
                    b.start();
                } catch (IOException e) {
                    System.out.println("[lagproxy] connect failed: " + e.getMessage());
                    closeQuietly(client);
                }
            }
        } catch (IOException e) {
            System.out.println("[lagproxy] accept loop ended: " + e.getMessage());
        }
    }

    interface IntRef { int get(); }
    static IntRef delayC2SRef() { return () -> delayC2S; }
    static IntRef delayS2CRef() { return () -> delayS2C; }

    static Thread pump(Socket from, Socket to, IntRef baseDelay, AtomicLong counter, String tag) {
        return new Thread(() -> {
            byte[] buf = new byte[16384];
            try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                int n;
                while (running && (n = in.read(buf)) >= 0) {
                    int d = baseDelay.get();
                    if (d > 0 || jitter > 0) {
                        Thread.sleep(d + (jitter > 0 ? ThreadLocalRandom.current().nextInt(jitter) : 0));
                    }
                    if (lossPct > 0 && ThreadLocalRandom.current().nextInt(100) < lossPct) {
                        // Simulated TCP retransmit stall: hold this segment back.
                        Thread.sleep(300 + ThreadLocalRandom.current().nextInt(600));
                    }
                    out.write(buf, 0, n);
                    out.flush();
                    counter.addAndGet(n);
                }
            } catch (IOException | InterruptedException ignored) {
            } finally {
                CLIENTS.remove(from);
                CLIENTS.remove(to);
                closeQuietly(from);
                closeQuietly(to);
                System.out.println("[lagproxy] " + tag + " pump closed "
                    + from.getRemoteSocketAddress());
            }
        }, "lagproxy-" + tag);
    }

    static void kickAll() {
        System.out.println("[lagproxy] kicking " + (CLIENTS.size() / 2) + " connection(s)");
        for (Socket s : CLIENTS) closeQuietly(s);
        CLIENTS.clear();
    }

    static void status() {
        System.out.println("[lagproxy] delay c2s=" + delayC2S + "ms s2c=" + delayS2C
            + "ms jitter=" + jitter + "ms loss=" + lossPct + "%"
            + " | bytes c2s=" + bytesC2S.get() + " s2c=" + bytesS2C.get()
            + " | connections=" + (CLIENTS.size() / 2));
    }

    static void closeQuietly(Socket s) {
        try { s.close(); } catch (IOException ignored) {}
    }
}
