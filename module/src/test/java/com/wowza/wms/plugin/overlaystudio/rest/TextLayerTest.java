/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.wowza.wms.plugin.overlaystudio.templates.OverlayStore;
import com.wowza.wms.plugin.overlaystudio.templates.TemplateFiles;
import com.wowza.wms.rest.ConfigBase;

/** Text layers (plan 3.2, 3.3): layout, background, outline, fit, tokens drawn, their checks, and golden images. */
class TextLayerTest
{
	static final long T = OffsetDateTime.parse("2027-03-14T12:34:56-04:00").toInstant().toEpochMilli();

	static ObjectConfig text(Map<String, Object> fields)
	{
		Map<String, Object> m = new LinkedHashMap<>(Map.of("id", "t", "type", "string", "x", 20, "y", 60, "font", "Fira Sans", "fontStyle", "plain",
				"fontSize", 32, "color", "rgb(255,255,255)", "timeZone", "America/New_York"));
		m.putAll(fields);
		return ObjectDefectsTest.object(m);
	}

	static BufferedImage draw(ObjectConfig o, int w, int h, double scale)
	{
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
		o.draw(g, scale, false, 1.0, T, T - 42_000);
		g.dispose();
		return img;
	}

	/** {left, top, right, bottom} of pixels whose alpha is over 64, or null for none. */
	static int[] ink(BufferedImage img, java.util.function.IntPredicate color)
	{
		int l = Integer.MAX_VALUE, t = Integer.MAX_VALUE, r = -1, b = -1;
		for(int y = 0; y < img.getHeight(); y++)
		{
			for(int x = 0; x < img.getWidth(); x++)
			{
				int p = img.getRGB(x, y);
				if((p >>> 24) > 64 && color.test(p & 0xffffff))
				{
					l = Math.min(l, x);
					t = Math.min(t, y);
					r = Math.max(r, x);
					b = Math.max(b, y);
				}
			}
		}
		return r < 0 ? null : new int[] { l, t, r, b };
	}

	static boolean white(int rgb)
	{
		return ((rgb >> 16) & 0xff) > 200 && ((rgb >> 8) & 0xff) > 200 && (rgb & 0xff) > 200;
	}

	@Test
	void tokensAreDrawnForTheDrawsMoment()
	{
		BufferedImage withToken = draw(text(Map.of("text", "Live {time:HH:mm:ss} on {streamtime:mm:ss}")), 600, 100, 1.0);
		BufferedImage literal = draw(text(Map.of("text", "Live 12:34:56 on 00:42")), 600, 100, 1.0);
		assertTrue(java.util.Arrays.equals(withToken.getRGB(0, 0, 600, 100, null, 0, 600), literal.getRGB(0, 0, 600, 100, null, 0, 600)),
				"the token draws exactly as the text it stands for, in the layer's zone, and stream time from the stream's start");
	}

	@Test
	void aBoxWrapsAtItsWidthAlignsAndClipsAtItsHeight()
	{
		String words = "one two three four five six seven eight nine ten eleven twelve";
		ObjectConfig left = text(Map.of("text", words, "box", true, "x", 20, "y", 10, "w", 200, "h", 300));
		int[] l = ink(draw(left, 400, 400, 1.0), TextLayerTest::white);
		assertTrue(l[2] <= 220, "wrapped inside the box's width: " + java.util.Arrays.toString(l));
		assertTrue(l[3] - l[1] > 100, "onto several lines");

		int[] r = ink(draw(text(Map.of("text", "end", "box", true, "align", "right", "x", 20, "y", 10, "w", 300, "h", 60)), 400, 100, 1.0), TextLayerTest::white);
		assertTrue(r[2] >= 312 && r[2] <= 320, "right-aligned against the box's right edge: " + java.util.Arrays.toString(r));
		int[] c = ink(draw(text(Map.of("text", "mid", "box", true, "align", "center", "x", 20, "y", 10, "w", 300, "h", 60)), 400, 100, 1.0), TextLayerTest::white);
		assertTrue(Math.abs((c[0] + c[2]) / 2 - 170) <= 3, "centered in the box: " + java.util.Arrays.toString(c));

		int[] clipped = ink(draw(text(Map.of("text", words, "box", true, "x", 20, "y", 10, "w", 200, "h", 50)), 400, 400, 1.0), TextLayerTest::white);
		assertTrue(clipped[3] < 60, "nothing below the box: " + java.util.Arrays.toString(clipped));
	}

	@Test
	void autoFitShrinksTheTextUntilItFits()
	{
		Map<String, Object> f = new LinkedHashMap<>(Map.of("text", "A headline that is far too long", "box", true, "x", 0, "y", 0, "w", 300, "h", 44, "fontSize", 40));
		int[] clipped = ink(draw(text(f), 400, 200, 1.0), TextLayerTest::white);
		f.put("autoFit", true);
		int[] fitted = ink(draw(text(f), 400, 200, 1.0), TextLayerTest::white);
		assertTrue(fitted[3] - fitted[1] < clipped[3] - clipped[1] || fitted[2] < clipped[2], "smaller once fitted");
		assertTrue(fitted[3] < 44, "inside the box's height: " + java.util.Arrays.toString(fitted));
	}

	@Test
	void backgroundOutlineAndLineHeight()
	{
		// without a box the background is the text's extent plus padding
		ObjectConfig badge = text(Map.of("text", "LIVE", "background", "rgb(200,0,0)", "padding", 10));
		int[] bg = ink(draw(badge, 300, 120, 1.0), rgb -> rgb == 0xc80000);
		int[] letters = ink(draw(text(Map.of("text", "LIVE")), 300, 120, 1.0), TextLayerTest::white);
		assertTrue(bg[0] <= letters[0] - 9 && bg[2] >= letters[2] + 9, "padding around the letters: " + java.util.Arrays.toString(bg) + " vs " + java.util.Arrays.toString(letters));

		int[] outlined = ink(draw(text(Map.of("text", "LIVE", "outlineColor", "rgb(0,0,255)", "outlineWidth", 4)), 300, 120, 1.0), rgb -> (rgb & 0xff) > 200 && (rgb >> 16 & 0xff) < 60);
		assertTrue(outlined[0] < letters[0] && outlined[2] > letters[2], "the outline shows outside the letters");

		int[] tight = ink(draw(text(Map.of("text", "a\nb", "lineHeight", 1.0)), 300, 200, 1.0), TextLayerTest::white);
		int[] loose = ink(draw(text(Map.of("text", "a\nb", "lineHeight", 2.0)), 300, 200, 1.0), TextLayerTest::white);
		assertTrue(loose[3] - tight[3] >= 30, "the second line moves down by a font size: " + tight[3] + " vs " + loose[3]);
	}

	@Test
	void aBoxsBoundsAreTheBoxAndFreeTextSpansTheWidth()
	{
		java.awt.Rectangle b = text(Map.of("text", "x", "box", true, "x", 30, "y", 40, "w", 200, "h", 50)).drawBounds(1.0);
		assertTrue(b.x <= 30 && b.y <= 40 && b.x + b.width >= 230 && b.y + b.height >= 90 && b.width < 220, b.toString());
		java.awt.Rectangle free = text(Map.of("text", "two\nlines", "background", "red", "padding", 8)).drawBounds(1.0);
		assertTrue(free.width >= ObjectConfig.ANY_WIDTH, "the full width, padded: " + free);
		assertTrue(free.height > 32 * 1.2 + 16, "both lines and the padding: " + free);
	}

	@Test
	void textFieldsAreChecked() throws Exception
	{
		assertNull(ObjectConfig.textProblem(Map.of("text", "{time:HH:mm} {{x}}", "timeZone", "Europe/Paris", "locale", "fr-FR", "align", "center",
				"lineHeight", 1.5, "padding", 12, "outlineWidth", 3, "target", "2027-01-01T18:00:00-05:00", "fontStyle", "bolditalic")));
		Object[][] bad = { { "text", "{time:HH}}" }, { "text", "x".repeat(2001) }, { "timeZone", "Mars/Base" }, { "target", "tomorrow" }, { "align", "justify" },
				{ "lineHeight", 9 }, { "outlineWidth", 30 }, { "fontSize", 0 }, { "fontStyle", "heavy" }, { "box", "yes" } };
		for(Object[] b: bad)
		{
			String[] p = ObjectConfig.textProblem(Map.of((String)b[0], b[1]));
			assertNotNull(p, b[0] + " " + b[1]);
			assertEquals(b[0], p[0]);
		}
		ConfigBase.ConfigBaseException e = assertThrows(ConfigBase.ConfigBaseException.class, text(Map.of("text", "{nope:x}"))::saveNewObject);
		assertTrue(e.errors.containsKey("text"), e.errors.toString());
	}

	@TempDir
	Path dir;

	@Test
	void anOverlayWithBadTextOrUseDateIsRefused() throws Exception
	{
		for(Map<String, Object> layer: List.of(Map.<String, Object>of("id", "a", "type", "string", "text", "{time:HH"),
				Map.<String, Object>of("id", "a", "type", "string", "text", "HH:mm", "useDate", true)))
		{
			Map<String, Object> overlay = new LinkedHashMap<>();
			overlay.put("objects", new ArrayList<>(List.of(new LinkedHashMap<>(layer))));
			TemplateFiles.Refused x = assertThrows(TemplateFiles.Refused.class, () -> OverlayStore.write(dir, "o", overlay, u -> null));
			assertEquals(400, x.status(), x.getMessage());
		}
	}

	@Test
	void formatAnswersSamplesAndErrors()
	{
		FormatConfig f = new FormatConfig();
		Map<String, String> q = new LinkedHashMap<>(Map.of("pattern", "HH:mm:ss", "zone", "America/New_York", "now", Long.toString(T), "count", "3"));
		@SuppressWarnings("unchecked")
		Map<String, Object> ok = (Map<String, Object>)f.doGet(Map.of(), q);
		assertEquals(List.of("12:34:56", "12:34:57", "12:34:58"), ok.get("samples"));
		assertNull(ok.get("error"));
		assertNotNull(ok.get("defaultZone"));
		q.put("kind", "countdown");
		q.put("pattern", "H:mm:ss");
		q.put("target", "2027-03-14T13:34:56-04:00");
		q.put("count", "500");
		@SuppressWarnings("unchecked")
		Map<String, Object> down = (Map<String, Object>)f.doGet(Map.of(), q);
		assertEquals(FormatConfig.MAX_SAMPLES, ((List<?>)down.get("samples")).size(), "at most 120");
		assertEquals("1:00:00", ((List<?>)down.get("samples")).get(0));
		q.put("pattern", "HH:qq");
		@SuppressWarnings("unchecked")
		Map<String, Object> bad = (Map<String, Object>)f.doGet(Map.of(), q);
		assertNotNull(bad.get("error"));
		assertEquals(List.of(), bad.get("samples"));
	}

	// Golden images, compared exactly: the bundled fonts draw the same on Windows and Linux (spike S3). Rerun with
	// OVS_WRITE_GOLDENS=1 after an intended change, and look at what was written.
	static final Map<String, Map<String, Object>> GOLDENS = goldens();

	static Map<String, Map<String, Object>> goldens()
	{
		Map<String, Map<String, Object>> g = new LinkedHashMap<>();
		g.put("text-plain", Map.of("text", "Hamburg 2 - 1 Berlin"));
		g.put("text-bold-italic", Map.of("text", "Breaking news", "fontStyle", "bolditalic"));
		g.put("text-mono-clock", Map.of("text", "{time:HH:mm:ss}", "font", "Fira Mono", "fontSize", 48));
		g.put("text-box-wrap-center", Map.of("text", "A lower third that wraps", "box", true, "align", "center", "x", 10, "y", 10, "w", 300, "h", 90,
				"background", "rgb(0,0,0,180)", "padding", 8, "radius", 6));
		g.put("text-badge-outline", Map.of("text", "LIVE", "fontStyle", "bold", "background", "rgb(200,0,0)", "padding", 10, "radius", 8,
				"outlineColor", "rgb(0,0,0)", "outlineWidth", 2));
		g.put("text-condensed-countdown", Map.of("text", "Starts in {countdown:m:ss}", "font", "Fira Sans Condensed", "target", "2027-03-14T12:40:00-04:00"));
		g.put("text-serif-cyrillic", Map.of("text", "Новости дня", "font", "PT Serif"));
		return g;
	}

	@Test
	void goldenImages() throws Exception
	{
		Path dirOf = Path.of(System.getProperty("overlaystudio.goldens"));
		boolean write = "1".equals(System.getenv("OVS_WRITE_GOLDENS"));
		for(Map.Entry<String, Map<String, Object>> e: GOLDENS.entrySet())
		{
			for(double scale: new double[] { 1.0, 0.5 })
			{
				BufferedImage img = draw(text(e.getValue()), (int)(400 * scale), (int)(120 * scale), scale);
				Path f = dirOf.resolve(e.getKey() + (scale == 1.0 ? "" : "-half") + ".png");
				if(write)
				{
					Files.createDirectories(dirOf);
					ImageIO.write(img, "png", f.toFile());
					continue;
				}
				BufferedImage want = ImageIO.read(f.toFile());
				assertNotNull(want, "golden " + f.getFileName() + " (write them with OVS_WRITE_GOLDENS=1)");
				assertArrayEquals(want.getRGB(0, 0, want.getWidth(), want.getHeight(), null, 0, want.getWidth()),
						img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth()), f.getFileName().toString());
			}
		}
	}
}
