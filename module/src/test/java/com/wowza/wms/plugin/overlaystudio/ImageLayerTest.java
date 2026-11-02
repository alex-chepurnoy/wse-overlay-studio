/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wowza.wms.plugin.overlaystudio.rest.ObjectConfig;
import com.wowza.wms.plugin.overlaystudio.rest.OverlayConfig;

/** Image layers drawn from files (plan 3.5): sizing, a shared decoded copy, replacement, missing files. */
class ImageLayerTest
{
	static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	@TempDir
	Path content;

	@BeforeEach
	void useTempContent()
	{
		ImageFiles.clearForTest();
		ImageFiles.contentDirOf = vhost -> content;
	}

	@AfterEach
	void restore()
	{
		ImageFiles.clearForTest();
		ImageFiles.keepBytes = ImageFiles.KEEP_BYTES;
	}

	/** A w x h picture, red, with a blue left half, written to content/name. */
	Path picture(String name, int w, int h) throws Exception
	{
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setColor(Color.RED);
		g.fillRect(0, 0, w, h);
		g.setColor(Color.BLUE);
		g.fillRect(0, 0, w / 2, h);
		g.dispose();
		Path f = content.resolve(name);
		ImageIO.write(img, "png", f.toFile());
		return f;
	}

	static ObjectConfig layer(Map<String, Object> fields)
	{
		Map<String, Object> m = new LinkedHashMap<>(Map.of("id", "img", "type", "image", "x", 10, "y", 10, "opacity", 100));
		m.putAll(fields);
		return JSON.convertValue(m, ObjectConfig.class);
	}

	static BufferedImage draw(ObjectConfig o, double scale)
	{
		BufferedImage canvas = new BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = canvas.createGraphics();
		o.draw(g, scale, false);
		g.dispose();
		return canvas;
	}

	static int[] ink(BufferedImage img)
	{
		int l = Integer.MAX_VALUE, t = Integer.MAX_VALUE, r = -1, b = -1;
		for(int y = 0; y < img.getHeight(); y++)
		{
			for(int x = 0; x < img.getWidth(); x++)
			{
				if((img.getRGB(x, y) >>> 24) > 200)
				{
					l = Math.min(l, x);
					t = Math.min(t, y);
					r = Math.max(r, x);
					b = Math.max(b, y);
				}
			}
		}
		return r < 0 ? null : new int[] { l, t, r - l + 1, b - t + 1 };
	}

	@Test
	void drawnIntoItsBoxFittedOrStretched() throws Exception
	{
		picture("logo.png", 100, 50);
		// fitted inside 120 x 120, centered: 120 x 60 at y 10 + 30
		assertArrayEquals4(new int[] { 10, 40, 120, 60 }, ink(draw(layer(Map.of("imageFile", "logo.png", "w", 120, "h", 120)), 1.0)), "lockAspect on by default");
		assertArrayEquals4(new int[] { 10, 10, 120, 120 }, ink(draw(layer(Map.of("imageFile", "logo.png", "w", 120, "h", 120, "lockAspect", false)), 1.0)), "stretched");
		assertArrayEquals4(new int[] { 10, 10, 80, 40 }, ink(draw(layer(Map.of("imageFile", "logo.png", "w", 80)), 1.0)), "the other side from the aspect");
		assertArrayEquals4(new int[] { 5, 5, 50, 25 }, ink(draw(layer(Map.of("imageFile", "logo.png")), 0.5)), "native size, scaled with the output");
	}

	static void assertArrayEquals4(int[] want, int[] got, String what)
	{
		assertNotNull(got, what + ": nothing drawn");
		for(int i = 0; i < 4; i++)
		{
			assertTrue(Math.abs(want[i] - got[i]) <= 1, what + ": want " + java.util.Arrays.toString(want) + ", got " + java.util.Arrays.toString(got));
		}
	}

	// 12 streams on one overlay hold one decoded copy of each image
	@Test
	void streamsShareOneDecodedCopy() throws Exception
	{
		Path f = picture("bug.png", 64, 64);
		List<ObjectConfig> layers = new ArrayList<>();
		for(int i = 0; i < 12; i++)
		{
			ObjectConfig o = layer(Map.of("imageFile", "bug.png", "w", 64, "h", 64));
			draw(o, 1.0);
			layers.add(o);
		}
		ImageFiles.Key k = ImageFiles.keyOf(f);
		assertEquals(12, ImageFiles.holders(k));
		assertEquals(1, ImageFiles.stats().get("entries"));
		assertEquals(64L * 64 * 4, ImageFiles.stats().get("heldBytes"));
		layers.forEach(ObjectConfig::dispose);
		assertEquals(0, ImageFiles.holders(k), "every hold let go");
		assertEquals(1, ImageFiles.stats().get("entries"), "kept for reuse while there is room");
	}

	@Test
	void unheldPicturesAreEvictedFirstAndHeldOnesNever() throws Exception
	{
		ImageFiles.keepBytes = 3 * 100L * 100 * 4;
		ObjectConfig kept = layer(Map.of("imageFile", "a.png"));
		picture("a.png", 100, 100);
		draw(kept, 1.0);
		for(String n: List.of("b.png", "c.png", "d.png", "e.png"))
		{
			picture(n, 100, 100);
			ObjectConfig o = layer(Map.of("imageFile", n));
			draw(o, 1.0);
			o.dispose();
		}
		assertTrue((long)ImageFiles.stats().get("decodedBytes") <= ImageFiles.keepBytes, ImageFiles.stats().toString());
		assertEquals(1, ImageFiles.holders(ImageFiles.keyOf(content.resolve("a.png"))), "the held picture stayed");
		kept.dispose();
	}

	@Test
	void aReplacedFileIsNoticedAndRedrawn() throws Exception
	{
		Path f = picture("score.png", 40, 20);
		OverlayConfig config = new OverlayConfig();
		config.setOpacity(100);
		ObjectConfig o = layer(Map.of("imageFile", "score.png", "w", 40, "h", 20));
		config.addObject(o);
		OverlayImage img = new OverlayImage(0, 0, 200, 100, config, false);
		assertNotNull(img.current(1.0));
		assertFalse(config.imagesChanged());
		ImageFiles.Key before = ImageFiles.keyOf(f);

		picture("score.png", 80, 20);
		Files.setLastModifiedTime(f, FileTime.fromMillis(before.modified() + 5000));
		assertTrue(config.imagesChanged(), "a new version on disk");
		long now = 1_767_225_600_000L;
		assertTrue(img.nextWakeAt(now) <= now + OverlayImage.IMAGE_CHECK_MS, "looked for every 10 s");
		java.util.UUID u = config.getUuid();
		img.wakeUp(now);
		assertFalse(u.equals(config.getUuid()), "redrawn");
		assertNotNull(img.current(1.0));
		assertEquals(0, ImageFiles.holders(before), "the old version let go");
		assertFalse(config.imagesChanged());
	}

	@Test
	void aMissingFileDrawsNothingAndSaysWhy() throws Exception
	{
		OverlayConfig config = new OverlayConfig();
		config.setOpacity(100);
		ObjectConfig o = layer(Map.of("imageFile", "gone.png", "w", 40, "h", 20));
		config.addObject(o);
		OverlayImage img = new OverlayImage(0, 0, 200, 100, config, false);
		assertNotNull(img.current(1.0), "the overlay still draws");
		assertNull(ink(draw(o, 1.0)));
		assertEquals(1, img.imageProblems().size());
		assertTrue(img.imageProblems().get(0).get("problem").toString().contains("gone.png"), img.imageProblems().toString());

		ObjectConfig none = layer(Map.of("w", 40, "h", 20));
		assertNull(ink(draw(none, 1.0)), "no file: nothing to draw, as the Corner bug preset");
		assertNull(none.imageProblem(), "and nothing wrong");
	}

	@Test
	void anEditedImageLayerTakesItsOwnHold() throws Exception
	{
		Path f = picture("p.png", 30, 30);
		OverlayConfig config = new OverlayConfig();
		ObjectConfig live = layer(Map.of("imageFile", "p.png"));
		config.addObject(live);
		new OverlayImage(0, 0, 100, 100, config, false);
		draw(live, 1.0);
		ImageFiles.Key k = ImageFiles.keyOf(f);
		assertEquals(1, ImageFiles.holders(k));
		ObjectConfig edited = layer(Map.of("imageFile", "p.png", "x", 40));
		com.wowza.wms.plugin.overlaystudio.rest.ObjectConfigTestAccess.shareCaptureOf(edited, live);
		assertEquals(2, ImageFiles.holders(k));
		config.putObject(edited);
		assertEquals(1, ImageFiles.holders(k), "the replaced layer let go once no draw used it");
		assertSame(edited, config.getObjects().get(0));
		edited.dispose();
	}
}
