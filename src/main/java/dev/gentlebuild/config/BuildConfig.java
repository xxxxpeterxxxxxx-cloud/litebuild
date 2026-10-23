package dev.gentlebuild.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import dev.gentlebuild.GentleBuild;

/** Runtime configuration, persisted as JSON in the Fabric config dir. */
public class BuildConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Path file() {
		return net.fabricmc.loader.api.FabricLoader.getInstance()
				.getConfigDir().resolve("gentlebuild.json");
	}

	public enum HudCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

	public int minPlaceIntervalTicks = 3;
	public int confirmationTimeoutTicks = 60;
	public int settleTicks = 8;
	public int hotbarSwitchTicks = 4;
	public int mainRefillTicks = 8;
	public double maxYawSpeed = 380.0;      // deg/s — saccades are fast
	public double maxPitchSpeed = 300.0;    // deg/s
	public double maxAngularAccel = 4000.0; // deg/s^2
	public int maxPlanNodes = 4096;
	public String scaffoldBlock = "minecraft:dirt";
	public boolean allowTemporarySupports = true;
	public boolean sprintOnLongWalks = true;
	public int checkpointStructureBlocks = 10;
	public int checkpointSensitiveBlocks = 5;

	// Natural movement/look cadence.
	public boolean naturalMovement = true;
	public double microCorrectionStrength = 0.6;
	public boolean automaticPauses = true;
	public int shortPauseMinActions = 4, shortPauseMaxActions = 10;
	public int shortPauseMinTicks = 8, shortPauseMaxTicks = 24;
	public int afkIntervalMinTicks = 2400, afkIntervalMaxTicks = 6000;
	public int afkDurationMinTicks = 40, afkDurationMaxTicks = 100;
	public boolean automaticRepairs = true;
	public int maxRepairRounds = 3;

	// HUD overlay.
	public boolean hudEnabled = true;
	public HudCorner hudCorner = HudCorner.TOP_LEFT;
	public boolean hudCompact = false;
	public double hudScale = 1.0;
	public int hudOpacity = 85;

	// Per-session toggles, intentionally NOT persisted across worlds.
	public transient boolean fluidsEnabled = false;
	public transient boolean activationEnabled = false;

	public static BuildConfig load() {
		return load(file());
	}

	static BuildConfig load(Path path) {
		BuildConfig config = new BuildConfig();
		if (Files.isRegularFile(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				BuildConfig loaded = GSON.fromJson(reader, BuildConfig.class);
				if (loaded != null) {
					config = loaded;
				}
			} catch (IOException | JsonSyntaxException e) {
				GentleBuild.LOGGER.warn("gentlebuild.json konnte nicht gelesen werden, nutze Standardwerte: {}",
						e.getMessage());
			}
		}
		config.clamp();
		config.save(path);
		return config;
	}

	public boolean save() {
		return save(file());
	}

	/** Atomic temp-file write + move; falls back when ATOMIC_MOVE is unsupported. */
	boolean save(Path path) {
		Path temp = path.resolveSibling(path.getFileName() + ".tmp");
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(temp)) {
				GSON.toJson(this, writer);
			}
			try {
				Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING,
						StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException e) {
				Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
			}
			return true;
		} catch (IOException e) {
			GentleBuild.LOGGER.warn("gentlebuild.json konnte nicht gespeichert werden: {}", e.getMessage());
			return false;
		} finally {
			try {
				Files.deleteIfExists(temp);
			} catch (IOException ignored) {
				// best effort
			}
		}
	}

	/** Independent copy via JSON round trip; session-only flags restored from this instance. */
	public BuildConfig copy() {
		BuildConfig next = GSON.fromJson(GSON.toJson(this), BuildConfig.class);
		next.fluidsEnabled = fluidsEnabled;
		next.activationEnabled = activationEnabled;
		return next;
	}

	/**
	 * Pure validation of every numeric/identifier field. Returns German error
	 * descriptions; empty list means the draft can be saved. Nothing is
	 * silently corrected here — the user must fix or revert the input.
	 */
	public List<String> validationErrors() {
		List<String> errors = new ArrayList<>();
		checkInt(errors, "minPlaceIntervalTicks", minPlaceIntervalTicks, 2, 100);
		checkInt(errors, "confirmationTimeoutTicks", confirmationTimeoutTicks, 20, 1200);
		checkInt(errors, "settleTicks", settleTicks, 2, 100);
		checkInt(errors, "hotbarSwitchTicks", hotbarSwitchTicks, 1, 20);
		checkInt(errors, "mainRefillTicks", mainRefillTicks, 1, 20);
		checkDouble(errors, "maxYawSpeed", maxYawSpeed, 10.0, 800.0);
		checkDouble(errors, "maxPitchSpeed", maxPitchSpeed, 10.0, 720.0);
		checkDouble(errors, "maxAngularAccel", maxAngularAccel, 50.0, 12000.0);
		checkInt(errors, "maxPlanNodes", maxPlanNodes, 256, 65536);
		if (scaffoldBlock == null || !scaffoldBlock.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
			errors.add("Stützblock: ungültige Block-ID \"" + scaffoldBlock
					+ "\" (Format z.B. minecraft:dirt)");
		}
		checkDouble(errors, "microCorrectionStrength", microCorrectionStrength, 0.0, 1.0);
		checkInt(errors, "shortPauseMinActions", shortPauseMinActions, 1, 100);
		checkInt(errors, "shortPauseMaxActions", shortPauseMaxActions, 1, 100);
		checkMinMax(errors, "Kurzpausen-Aktionen", shortPauseMinActions, shortPauseMaxActions);
		checkInt(errors, "shortPauseMinTicks", shortPauseMinTicks, 1, 200);
		checkInt(errors, "shortPauseMaxTicks", shortPauseMaxTicks, 1, 200);
		checkMinMax(errors, "Kurzpausen-Ticks", shortPauseMinTicks, shortPauseMaxTicks);
		checkInt(errors, "afkIntervalMinTicks", afkIntervalMinTicks, 20, 72000);
		checkInt(errors, "afkIntervalMaxTicks", afkIntervalMaxTicks, 20, 72000);
		checkMinMax(errors, "AFK-Intervall", afkIntervalMinTicks, afkIntervalMaxTicks);
		checkInt(errors, "afkDurationMinTicks", afkDurationMinTicks, 1, 100);
		checkInt(errors, "afkDurationMaxTicks", afkDurationMaxTicks, 1, 100);
		checkMinMax(errors, "AFK-Dauer", afkDurationMinTicks, afkDurationMaxTicks);
		if (maxRepairRounds < 1 || maxRepairRounds > 5) {
			errors.add("maxRepairRounds: Wert " + maxRepairRounds + " außerhalb 1..5");
		}
		checkInt(errors, "checkpointStructureBlocks", checkpointStructureBlocks, 5, 10);
		checkInt(errors, "checkpointSensitiveBlocks", checkpointSensitiveBlocks, 5, 10);
		if (checkpointSensitiveBlocks > checkpointStructureBlocks) {
			errors.add("Checkpoint: sensibles Limit (" + checkpointSensitiveBlocks
					+ ") darf das Struktur-Limit (" + checkpointStructureBlocks
					+ ") nicht überschreiten");
		}
		checkDouble(errors, "hudScale", hudScale, 0.75, 1.5);
		checkInt(errors, "hudOpacity", hudOpacity, 50, 100);
		if (hudCorner == null) {
			errors.add("HUD-Ecke: kein Wert gewählt");
		}
		return errors;
	}

	private static void checkInt(List<String> errors, String name, int v, int lo, int hi) {
		if (v < lo || v > hi) {
			errors.add(name + ": Wert " + v + " außerhalb " + lo + ".." + hi);
		}
	}

	private static void checkDouble(List<String> errors, String name, double v, double lo, double hi) {
		if (!Double.isFinite(v) || v < lo || v > hi) {
			errors.add(name + ": Wert " + v + " außerhalb " + lo + ".." + hi);
		}
	}

	private static void checkMinMax(List<String> errors, String name, int min, int max) {
		if (min > max) {
			errors.add(name + ": Minimum " + min + " größer als Maximum " + max);
		}
	}

	/** Keeps every value inside sane bounds; invalid entries fall back to defaults. */
	public void clamp() {
		minPlaceIntervalTicks = clampInt(minPlaceIntervalTicks, 2, 100, 3);
		confirmationTimeoutTicks = clampInt(confirmationTimeoutTicks, 20, 1200, 60);
		settleTicks = clampInt(settleTicks, 2, 100, 8);
		hotbarSwitchTicks = clampInt(hotbarSwitchTicks, 1, 20, 4);
		mainRefillTicks = clampInt(mainRefillTicks, 1, 20, 8);
		maxYawSpeed = clampDouble(maxYawSpeed, 10.0, 800.0, 380.0);
		maxPitchSpeed = clampDouble(maxPitchSpeed, 10.0, 720.0, 300.0);
		maxAngularAccel = clampDouble(maxAngularAccel, 50.0, 12000.0, 4000.0);
		maxPlanNodes = clampInt(maxPlanNodes, 256, 65536, 4096);
		if (scaffoldBlock == null || !scaffoldBlock.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
			scaffoldBlock = "minecraft:dirt";
		}
		microCorrectionStrength = clampDouble(microCorrectionStrength, 0.0, 1.0, 0.6);
		shortPauseMinActions = clampInt(shortPauseMinActions, 1, 100, 4);
		shortPauseMaxActions = clampInt(shortPauseMaxActions, 1, 100, 10);
		if (shortPauseMaxActions < shortPauseMinActions) {
			shortPauseMaxActions = shortPauseMinActions;
		}
		shortPauseMinTicks = clampInt(shortPauseMinTicks, 1, 200, 8);
		shortPauseMaxTicks = clampInt(shortPauseMaxTicks, 1, 200, 24);
		if (shortPauseMaxTicks < shortPauseMinTicks) {
			shortPauseMaxTicks = shortPauseMinTicks;
		}
		afkIntervalMinTicks = clampInt(afkIntervalMinTicks, 20, 72000, 2400);
		afkIntervalMaxTicks = clampInt(afkIntervalMaxTicks, 20, 72000, 6000);
		if (afkIntervalMaxTicks < afkIntervalMinTicks) {
			afkIntervalMaxTicks = afkIntervalMinTicks;
		}
		afkDurationMinTicks = clampInt(afkDurationMinTicks, 1, 100, 40);
		afkDurationMaxTicks = clampInt(afkDurationMaxTicks, 1, 100, 100);
		if (afkDurationMaxTicks < afkDurationMinTicks) {
			afkDurationMaxTicks = afkDurationMinTicks;
		}
		maxRepairRounds = Math.max(1, Math.min(5, maxRepairRounds));
		checkpointStructureBlocks = clampInt(checkpointStructureBlocks, 5, 10, 10);
		checkpointSensitiveBlocks = clampInt(checkpointSensitiveBlocks, 5, 10, 5);
		if (checkpointSensitiveBlocks > checkpointStructureBlocks) {
			checkpointSensitiveBlocks = checkpointStructureBlocks;
		}
		hudScale = clampDouble(hudScale, 0.75, 1.5, 1.0);
		hudOpacity = clampInt(hudOpacity, 50, 100, 85);
		if (hudCorner == null) {
			hudCorner = HudCorner.TOP_LEFT;
		}
	}

	private static int clampInt(int v, int lo, int hi, int def) {
		return (v < lo || v > hi) ? def : v;
	}

	private static double clampDouble(double v, double lo, double hi, double def) {
		return (!Double.isFinite(v) || v < lo || v > hi) ? def : v;
	}
}
