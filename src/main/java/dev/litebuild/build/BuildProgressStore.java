package dev.litebuild.build;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import dev.litebuild.LiteBuild;
import dev.litebuild.model.Blueprint;
import dev.litebuild.plan.Planner;
import dev.litebuild.state.StateRules;

/**
 * Persists build progress across client restarts as JSON under
 * {@code config/litebuild-progress/} — one file per world identity.
 *
 * The schematic identity is a REAL content fingerprint: a SHA-256 over the
 * canonicalized cell map (world positions + block states) of the imported
 * blueprint. A moved, rotated, mirrored or edited placement produces a
 * different fingerprint, and a stored record for a different build in the
 * same world is discarded instead of resumed.
 *
 * Stored cells are never trusted blindly either: {@link #applyVerified}
 * re-reads the live world and only cells still showing their final
 * blueprint state are marked done — everything else stays queued and is
 * rebuilt normally.
 */
public final class BuildProgressStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int FORMAT_VERSION = 1;
	/** Verified cells buffered before the next atomic disk write. */
	private static final int FLUSH_EVERY = 16;

	/** JSON record on disk — one file per world identity. */
	public static final class ProgressRecord {
		public int version = FORMAT_VERSION;
		public String worldId = "";
		public String dimension = "";
		public String schematicName = "";
		/** {@link #contentFingerprint} of the blueprint cell map. */
		public String schematicHash = "";
		/** World-space placement origin, "x,y,z". */
		public String origin = "";
		/** Placement transform identity (BlueprintImporter fingerprint). */
		public String region = "";
		/** Fully verified cells, "x,y,z", sorted for a stable file. */
		public List<String> verified = new ArrayList<>();
		public long updatedAt;
	}

	private final Path dir;
	private ProgressRecord record;
	private final Set<BlockPos> verified = new LinkedHashSet<>();
	private int pendingWrites;

	public BuildProgressStore() {
		this(net.fabricmc.loader.api.FabricLoader.getInstance()
				.getConfigDir().resolve("litebuild-progress"));
	}

	public BuildProgressStore(Path dir) {
		this.dir = dir;
	}

	// ------------------------------------------------------------------
	// Identity
	// ------------------------------------------------------------------

	/**
	 * World identity: remote server address, or the integrated server's
	 * level-storage id for singleplayer. Never Java object identity.
	 */
	public static String worldKey(Minecraft mc) {
		ServerData remote = mc.getCurrentServer();
		if (remote != null && remote.ip != null) {
			return "server/" + remote.ip;
		}
		IntegratedServer local = mc.getSingleplayerServer();
		if (local != null) {
			// The save directory name is the true world id; the display
			// name is only a fallback.
			java.nio.file.Path root = local.getWorldPath(
					net.minecraft.world.level.storage.LevelResource.ROOT);
			String id = root.getFileName() != null
					? root.getFileName().toString()
					: local.getWorldData().getLevelName();
			return "world/" + id;
		}
		return "unknown";
	}

	/**
	 * Canonical per-state string: {@code namespace:path[prop=value,...]}
	 * with properties sorted by name. Registry-id based, so identical
	 * content hashes identically across restarts of the same install.
	 */
	public static String stateString(BlockState state) {
		StringBuilder sb = new StringBuilder();
		sb.append(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
		List<String> props = new ArrayList<>();
		state.getValues().forEach(v ->
				props.add(v.property().getName() + "=" + v.valueName()));
		if (!props.isEmpty()) {
			Collections.sort(props);
			sb.append('[').append(String.join(",", props)).append(']');
		}
		return sb.toString();
	}

	/**
	 * SHA-256 (hex) over {@code "x,y,z=namespace:path[prop=value,...]"}
	 * lines, sorted. Positions are world-space — an origin shift, rotation
	 * or mirror changes the cells and therefore the fingerprint.
	 */
	public static String contentFingerprint(Map<BlockPos, BlockState> cells) {
		List<String> entries = new ArrayList<>(cells.size());
		for (Map.Entry<BlockPos, BlockState> e : cells.entrySet()) {
			entries.add(posString(e.getKey()) + "=" + stateString(e.getValue()));
		}
		Collections.sort(entries);
		return sha256(String.join("\n", entries));
	}

	private static String sha256(String input) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				sb.append(Character.forDigit((b >> 4) & 0xF, 16))
						.append(Character.forDigit(b & 0xF, 16));
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String posString(BlockPos pos) {
		return pos == null ? "" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	private static BlockPos parsePos(String s) {
		if (s == null) {
			return null;
		}
		String[] parts = s.split(",");
		if (parts.length != 3) {
			return null;
		}
		try {
			return new BlockPos(Integer.parseInt(parts[0].trim()),
					Integer.parseInt(parts[1].trim()),
					Integer.parseInt(parts[2].trim()));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	// ------------------------------------------------------------------
	// Session
	// ------------------------------------------------------------------

	/**
	 * Attach the store to a freshly snapshotted blueprint. A stored record
	 * that matches world, dimension, schematic fingerprint AND origin is
	 * loaded for resume; any other stored record for this world is
	 * discarded. Returns the number of stored verified cells offered for
	 * resume (0 on a cold start).
	 */
	public synchronized int begin(String worldId, String dimension, BlockPos origin,
								  String region, Blueprint blueprint) {
		record = null;
		verified.clear();
		pendingWrites = 0;
		String hash = contentFingerprint(blueprint.blocks());
		String originStr = posString(origin);
		Path file = fileFor(worldId);
		ProgressRecord stored = readRecord(file);
		if (stored != null && stored.version == FORMAT_VERSION
				&& worldId.equals(stored.worldId)
				&& dimension.equals(stored.dimension)
				&& hash.equals(stored.schematicHash)
				&& originStr.equals(stored.origin)) {
			record = stored;
			for (String s : stored.verified == null ? List.<String>of() : stored.verified) {
				BlockPos pos = parsePos(s);
				if (pos != null) {
					verified.add(pos);
				}
			}
			return verified.size();
		}
		if (stored != null) {
			deleteQuietly(file); // different build in this world — discard
		}
		record = new ProgressRecord();
		record.worldId = worldId;
		record.dimension = dimension;
		record.schematicName = blueprint.name();
		record.schematicHash = hash;
		record.origin = originStr;
		record.region = region == null ? "" : region;
		write();
		return 0;
	}

	/** Record a fully server-verified cell; persists on a bounded batch. */
	public synchronized void noteVerified(BlockPos pos) {
		if (record == null || pos == null || !verified.add(pos.immutable())) {
			return;
		}
		if (++pendingWrites >= FLUSH_EVERY) {
			write();
		}
	}

	/** Force any buffered verified cells to disk. No-op when clean. */
	public synchronized void flush() {
		if (record != null && pendingWrites > 0) {
			write();
		}
	}

	/** Session over (disconnect/unload): persist what remains, keep the file. */
	public synchronized void endSession() {
		flush();
		record = null;
		verified.clear();
		pendingWrites = 0;
	}

	/** Build finished or superseded: remove the stored record entirely. */
	public synchronized void discard() {
		Path file = record != null ? fileFor(record.worldId) : null;
		record = null;
		verified.clear();
		pendingWrites = 0;
		if (file != null) {
			deleteQuietly(file);
		}
	}

	/** Stored verified cells, for tests/diagnostics. */
	public synchronized int storedVerifiedCount() {
		return verified.size();
	}

	/**
	 * Revalidate stored cells against the live world and mark only the
	 * still-matching ones verified in {@code planner}. Cells whose live
	 * state no longer equals the blueprint's final state stay unverified
	 * and are re-queued by the normal eligibility scan — nothing is
	 * resumed blindly.
	 */
	public synchronized int applyVerified(Planner planner, Blueprint blueprint,
										  Function<BlockPos, BlockState> live) {
		if (record == null) {
			return 0;
		}
		return applyVerified(verified, planner, blueprint, live);
	}

	/** Shared revalidation core (instance-independent for tests). */
	public static int applyVerified(Collection<BlockPos> stored, Planner planner,
									Blueprint blueprint,
									Function<BlockPos, BlockState> live) {
		int applied = 0;
		for (BlockPos pos : stored) {
			BlockState expected = blueprint.blocks().get(pos);
			if (expected == null) {
				continue;
			}
			BlockState actual = live.apply(pos);
			if (actual != null && StateRules.finalEquivalent(expected, actual)) {
				planner.markVerified(pos);
				applied++;
			}
		}
		return applied;
	}

	// ------------------------------------------------------------------
	// Disk IO — same atomic temp+rename pattern as BuildConfig.save
	// ------------------------------------------------------------------

	private Path fileFor(String worldId) {
		String base = worldId.replaceAll("[^a-zA-Z0-9._-]", "_");
		if (base.length() > 40) {
			base = base.substring(0, 40);
		}
		return dir.resolve(base + "-" + sha256(worldId).substring(0, 8) + ".json");
	}

	private void write() {
		List<String> cells = new ArrayList<>(verified.size());
		for (BlockPos pos : verified) {
			cells.add(posString(pos));
		}
		Collections.sort(cells);
		record.verified = cells;
		record.updatedAt = System.currentTimeMillis();
		writeRecord(fileFor(record.worldId), record);
		pendingWrites = 0;
	}

	static ProgressRecord readRecord(Path path) {
		if (!Files.isRegularFile(path)) {
			return null;
		}
		try (Reader reader = Files.newBufferedReader(path)) {
			return GSON.fromJson(reader, ProgressRecord.class);
		} catch (IOException | JsonSyntaxException e) {
			LiteBuild.LOGGER.warn("Build progress {} could not be read: {}",
					path.getFileName(), e.getMessage());
			return null;
		}
	}

	static boolean writeRecord(Path path, ProgressRecord record) {
		Path temp = path.resolveSibling(path.getFileName() + ".tmp");
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(temp)) {
				GSON.toJson(record, writer);
			}
			try {
				Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING,
						StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException e) {
				Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
			}
			return true;
		} catch (IOException e) {
			LiteBuild.LOGGER.warn("Build progress {} could not be saved: {}",
					path.getFileName(), e.getMessage());
			return false;
		} finally {
			try {
				Files.deleteIfExists(temp);
			} catch (IOException ignored) {
				// best effort
			}
		}
	}

	private static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException e) {
			LiteBuild.LOGGER.warn("Build progress {} could not be deleted: {}",
					path.getFileName(), e.getMessage());
		}
	}
}
