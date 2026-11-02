/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wowza.wms.plugin.overlaystudio.rest.ObjectConfig;
import com.wowza.wms.plugin.overlaystudio.rest.OverlayConfig;
import com.wowza.wms.plugin.overlaystudio.templates.OverlayStore;
import com.wowza.wms.plugin.overlaystudio.templates.TemplateFiles;

/** Live values (plan 3.6): the store, its sidecar and rate, renames, and the swap into a running overlay. */
class LiveValuesTest
{
	static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
	static final LiveValues.Key KEY = new LiveValues.Key("_defaultVHost_", "live", "Scoreboard");

	@TempDir
	Path dir;
	final AtomicLong now = new AtomicLong(1_767_225_600_000L);

	@BeforeEach
	void fresh()
	{
		LiveValues.forgetForTest();
		LiveValues.clock = now::get;
		ImageFiles.clearForTest();
		ImageFiles.contentDirOf = vhost -> dir;
	}

	@AfterEach
	void restore() throws Exception
	{
		// a flush planned later writes nothing once its entry is forgotten; one running now is waited for
		LiveValues.forgetForTest();
		LiveValues.drainForTest();
		LiveValues.clock = System::currentTimeMillis;
		ImageFiles.clearForTest();
	}

	/** One write a tick, inside the rate. */
	Map<String, Object> set(String id, Map<String, Object> v) throws TemplateFiles.Refused
	{
		now.addAndGet(100);
		return LiveValues.set(KEY, dir, id, v);
	}

	@Test
	void setMergesClearOneOrAll() throws Exception
	{
		assertTrue(LiveValues.get(KEY, dir).isEmpty());
		set("score", Map.of("text", "HOME 1 - 0 AWAY"));
		assertEquals(Map.of("text", "HOME 1 - 0 AWAY", "color", "red"), set("score", Map.of("color", "red")), "merged");
		set("ticker", Map.of("hidden", true));
		assertEquals(Set.of("score", "ticker"), LiveValues.get(KEY, dir).keySet());
		assertTrue(LiveValues.clear(KEY, dir, "ticker"));
		assertFalse(LiveValues.clear(KEY, dir, "ticker"), "nothing left to clear");
		assertEquals(Set.of("score"), LiveValues.get(KEY, dir).keySet());
		LiveValues.clearAll(KEY, dir);
		assertTrue(LiveValues.get(KEY, dir).isEmpty());
	}

	@Test
	void theSidecarSurvivesARestartNeverListsAndGoesWithItsOverlay() throws Exception
	{
		OverlayStore.write(dir, "Scoreboard", Map.of("objects", List.of(Map.of("id", "score", "type", "string"))), u -> null);
		set("score", Map.of("text", "2 - 1"));
		LiveValues.flushForTest();
		Path side = LiveValues.sidecar(dir, "Scoreboard");
		assertTrue(Files.isRegularFile(side));
		assertEquals(List.of("Scoreboard"), OverlayStore.list(dir), "the sidecar is not an overlay");

		LiveValues.forgetForTest();
		assertEquals(Map.of("score", Map.of("text", "2 - 1")), LiveValues.get(KEY, dir), "read back after a restart");

		LiveValues.delete(KEY, dir);
		assertFalse(Files.exists(side));
		assertTrue(LiveValues.get(KEY, dir).isEmpty());
	}

	@Test
	void aFeedFasterThanTheRateIsRefused() throws Exception
	{
		for(int i = 0; i < LiveValues.RATE; i++)
		{
			LiveValues.set(KEY, dir, "score", Map.of("text", "n" + i));
		}
		TemplateFiles.Refused r = assertThrows(TemplateFiles.Refused.class, () -> LiveValues.set(KEY, dir, "score", Map.of("text", "one too many")));
		assertEquals(429, r.status());
		assertEquals("n19", LiveValues.get(KEY, dir).get("score").get("text"), "the refused write changed nothing");
		now.addAndGet(1000);
		LiveValues.set(KEY, dir, "score", Map.of("text", "later"));
	}

	@Test
	void aSaveMovesRenamedValuesAndDropsGoneOnes() throws Exception
	{
		set("text-3", Map.of("text", "HOME"));
		set("old", Map.of("text", "x"));
		set("logo", Map.of("hidden", true));
		assertTrue(LiveValues.afterSave(KEY, dir, Set.of("score", "logo"), Map.of("text-3", "score")));
		assertEquals(Map.of("score", Map.of("text", "HOME"), "logo", Map.of("hidden", true)), LiveValues.get(KEY, dir));
		assertFalse(LiveValues.afterSave(KEY, dir, Set.of("score", "logo"), Map.of()), "nothing to move");
	}

	static ObjectConfig layer(Map<String, Object> fields)
	{
		Map<String, Object> m = new LinkedHashMap<>(Map.of("opacity", 100, "color", "rgb(255,255,255)"));
		m.putAll(fields);
		return JSON.convertValue(m, ObjectConfig.class);
	}

	@Test
	void aRunningOverlayDrawsTheValueWithNoNewImageAndGoesBackOnClear()
	{
		OverlayConfig config = new OverlayConfig();
		config.setOpacity(100);
		config.addObject(layer(Map.of("id", "score", "type", "string", "x", 10, "y", 40, "text", "HOME 0 - 0 AWAY", "fontSize", 24)));
		config.addObject(layer(Map.of("id", "bar", "type", "rectangle", "x", 0, "y", 60, "w", 50, "h", 10, "fillColor", "rgb(255,0,0)")));
		OverlayImage img = new OverlayImage(0, 0, 300, 100, config, false);
		ObjectConfig bar = config.getObject("bar");
		UUID u = config.getUuid();

		assertTrue(config.applyLive(Map.of("score", Map.of("text", "HOME 2 - 1 AWAY", "color", "rgb(255,255,0)"))));
		assertNotEquals(u, config.getUuid(), "redrawn");
		assertSame(config, img.getOverlayConfig(), "the same image and overlay: display windows run on");
		assertEquals("HOME 2 - 1 AWAY", config.getObject("score").getText());
		assertEquals("rgb(255,255,0)", config.getObject("score").getColor());
		assertSame(bar, config.getObject("bar"), "a layer with no value is not touched");
		assertFalse(config.applyLive(Map.of("score", Map.of("text", "HOME 2 - 1 AWAY", "color", "rgb(255,255,0)"))), "the same value again changes nothing");

		u = config.getUuid();
		config.applyLive(Map.of("bar", Map.of("hidden", true)));
		assertEquals("HOME 0 - 0 AWAY", config.getObject("score").getText(), "cleared: as saved");
		assertEquals("rgb(255,255,255)", config.getObject("score").getColor());
		assertNull(config.getObject("score").liveValue());
		assertNotEquals(u, config.getUuid());
		BufferedImage canvas = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = canvas.createGraphics();
		config.getObject("bar").draw(g, 1.0, false);
		g.dispose();
		assertEquals(0, canvas.getRGB(10, 65) >>> 24, "a hidden layer draws nothing");
	}

	@Test
	void aLiveCopyOfAnImageLayerKeepsOneHold() throws Exception
	{
		BufferedImage pic = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = pic.createGraphics();
		g.setColor(Color.RED);
		g.fillRect(0, 0, 20, 20);
		g.dispose();
		ImageIO.write(pic, "png", dir.resolve("logo.png").toFile());
		OverlayConfig config = new OverlayConfig();
		config.setOpacity(100);
		config.addObject(layer(Map.of("id", "logo", "type", "image", "imageFile", "logo.png")));
		new OverlayImage(0, 0, 100, 100, config, false);
		ObjectConfig before = config.getObject("logo");
		BufferedImage canvas = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
		Graphics2D cg = canvas.createGraphics();
		before.draw(cg, 1.0, false);
		cg.dispose();
		ImageFiles.Key k = ImageFiles.keyOf(dir.resolve("logo.png"));
		assertEquals(1, ImageFiles.holders(k));
		config.applyLive(Map.of("logo", Map.of("opacity", 50)));
		assertNotSame(before, config.getObject("logo"));
		assertEquals(1, ImageFiles.holders(k), "the copy took a hold and the replaced layer let go of its own");
		config.getObject("logo").dispose();
	}
}
