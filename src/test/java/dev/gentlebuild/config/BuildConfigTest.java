package dev.gentlebuild.config;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BuildConfigTest {

	@Test
	void defaultsAreValid() {
		assertTrue(new BuildConfig().validationErrors().isEmpty());
	}

	@Test
	void legacyJsonWithoutNewFieldsGetsDefaults(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("gentlebuild.json");
		Files.writeString(file, "{\"maxYawSpeed\":400.0}");
		BuildConfig c = BuildConfig.load(file);
		assertEquals(400.0, c.maxYawSpeed);
		assertEquals(4, c.hotbarSwitchTicks);
		assertEquals(8, c.mainRefillTicks);
		assertEquals(10, c.checkpointStructureBlocks);
		assertEquals(5, c.checkpointSensitiveBlocks);
		assertTrue(c.hudEnabled);
		assertEquals(BuildConfig.HudCorner.TOP_LEFT, c.hudCorner);
		assertFalse(c.hudCompact);
		assertEquals(1.0, c.hudScale);
		assertEquals(85, c.hudOpacity);
	}

	@Test
	void copyIsIndependentButKeepsSessionFlags() {
		BuildConfig c = new BuildConfig();
		c.hotbarSwitchTicks = 7;
		c.fluidsEnabled = true;
		c.activationEnabled = true;
		BuildConfig copy = c.copy();
		copy.hotbarSwitchTicks = 9;
		copy.fluidsEnabled = false;
		assertEquals(7, c.hotbarSwitchTicks);
		assertTrue(c.fluidsEnabled);
		assertTrue(c.activationEnabled);
		assertEquals(9, copy.hotbarSwitchTicks);
		assertFalse(copy.fluidsEnabled);
	}

	@Test
	void nonFiniteDoublesAreRejectedAndClamped() {
		for (double v : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
			BuildConfig c = new BuildConfig();
			c.maxYawSpeed = v;
			assertFalse(c.validationErrors().isEmpty(), "value " + v);
			c.clamp();
			assertEquals(380.0, c.maxYawSpeed);
		}
	}

	@Test
	void minMaxPairErrorsAreReported() {
		BuildConfig c = new BuildConfig();
		c.shortPauseMinActions = 20;
		c.shortPauseMaxActions = 5;
		assertTrue(c.validationErrors().stream()
				.anyMatch(e -> e.contains("shortPauseActions")), c.validationErrors().toString());
		BuildConfig c2 = new BuildConfig();
		c2.afkDurationMinTicks = 60;
		c2.afkDurationMaxTicks = 40;
		assertTrue(c2.validationErrors().stream()
				.anyMatch(e -> e.contains("afkDuration")), c2.validationErrors().toString());
		BuildConfig c3 = new BuildConfig();
		c3.checkpointSensitiveBlocks = 9;
		c3.checkpointStructureBlocks = 7;
		assertTrue(c3.validationErrors().stream()
				.anyMatch(e -> e.contains("Checkpoint")), c3.validationErrors().toString());
	}

	@Test
	void outOfRangeAndNullCornerAreInvalid() {
		BuildConfig c = new BuildConfig();
		c.hudScale = 2.0;
		c.hudOpacity = 10;
		c.hudCorner = null;
		assertFalse(c.validationErrors().isEmpty());
		c.clamp();
		assertEquals(1.0, c.hudScale);
		assertEquals(85, c.hudOpacity);
		assertEquals(BuildConfig.HudCorner.TOP_LEFT, c.hudCorner);
	}

	@Test
	void invalidInputIsNotAcceptedSilently() {
		BuildConfig c = new BuildConfig();
		c.hotbarSwitchTicks = 0;
		c.maxPlanNodes = 10;
		assertFalse(c.validationErrors().isEmpty());
		BuildConfig ok = new BuildConfig();
		ok.hotbarSwitchTicks = 6;
		ok.mainRefillTicks = 10;
		ok.checkpointStructureBlocks = 7;
		ok.checkpointSensitiveBlocks = 6;
		ok.hudCorner = BuildConfig.HudCorner.TOP_RIGHT;
		assertTrue(ok.validationErrors().isEmpty(), ok.validationErrors().toString());
	}

	@Test
	void diskRoundTripPreservesFieldsAndDropsSessionFlags(@TempDir Path dir) {
		Path file = dir.resolve("gentlebuild.json");
		BuildConfig c = new BuildConfig();
		c.hotbarSwitchTicks = 6;
		c.mainRefillTicks = 10;
		c.checkpointStructureBlocks = 7;
		c.checkpointSensitiveBlocks = 6;
		c.hudCorner = BuildConfig.HudCorner.BOTTOM_LEFT;
		c.hudScale = 1.25;
		c.hudOpacity = 70;
		c.hudCompact = true;
		c.fluidsEnabled = true;
		c.activationEnabled = true;
		assertTrue(c.save(file));
		BuildConfig loaded = BuildConfig.load(file);
		assertEquals(6, loaded.hotbarSwitchTicks);
		assertEquals(10, loaded.mainRefillTicks);
		assertEquals(7, loaded.checkpointStructureBlocks);
		assertEquals(6, loaded.checkpointSensitiveBlocks);
		assertEquals(BuildConfig.HudCorner.BOTTOM_LEFT, loaded.hudCorner);
		assertEquals(1.25, loaded.hudScale);
		assertEquals(70, loaded.hudOpacity);
		assertTrue(loaded.hudCompact);
		// Session toggles are transient: never restored from disk.
		assertFalse(loaded.fluidsEnabled);
		assertFalse(loaded.activationEnabled);
	}

	@Test
	void failedSaveLeavesOriginalFileUntouched(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("gentlebuild.json");
		Files.writeString(file, "{\"hotbarSwitchTicks\":5}");
		// A directory at the temp path forces the temp-write to fail.
		Path temp = file.resolveSibling("gentlebuild.json.tmp");
		Files.createDirectory(temp);
		BuildConfig c = new BuildConfig();
		assertFalse(c.save(file));
		assertEquals("{\"hotbarSwitchTicks\":5}", Files.readString(file));
	}
}
