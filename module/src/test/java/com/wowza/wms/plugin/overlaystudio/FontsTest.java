/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wowza.wms.plugin.overlaystudio.rest.ObjectConfig;
import com.wowza.wms.plugin.overlaystudio.rest.OverlayConfig;

/** The bundled fonts (plan 3.7): they register, every style draws, and the style comes from fontStyle (D2, D3). */
class FontsTest
{
	static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	/** The ink of a word drawn white on transparent: {left, right, total ink}. */
	static int[] ink(Font f, String word)
	{
		BufferedImage img = new BufferedImage(600, 120, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setFont(f);
		g.setColor(java.awt.Color.WHITE);
		g.drawString(word, 20, 90);
		g.dispose();
		int left = Integer.MAX_VALUE, right = -1, total = 0;
		for(int y = 0; y < img.getHeight(); y++)
		{
			for(int x = 0; x < img.getWidth(); x++)
			{
				int a = img.getRGB(x, y) >>> 24;
				if(a > 64)
				{
					left = Math.min(left, x);
					right = Math.max(right, x);
					total += a;
				}
			}
		}
		return new int[] { left, right, total };
	}

	/** How far the ink in the top fifth of an upright stroke ("l") sits right of the ink in its bottom fifth: a slant. */
	static int slant(Font f)
	{
		BufferedImage img = new BufferedImage(200, 160, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setFont(f);
		g.setColor(java.awt.Color.WHITE);
		g.drawString("l", 80, 130);
		g.dispose();
		double[] top = { 0, 0 }, bottom = { 0, 0 };
		for(int y = 0; y < img.getHeight(); y++)
		{
			for(int x = 0; x < img.getWidth(); x++)
			{
				int a = img.getRGB(x, y) >>> 24;
				if(a > 64)
				{
					double[] t = y < 70 ? top : y > 110 ? bottom : null;
					if(t != null)
					{
						t[0] += x;
						t[1]++;
					}
				}
			}
		}
		return (int)Math.round(top[0] / top[1] - bottom[0] / bottom[1]);
	}

	@Test
	void everyBundledFamilyRegistersAndIsWhatJavaUses()
	{
		assertNull(Fonts.unavailable(), "this machine has fonts");
		for(String family: List.of("Fira Sans", "Fira Sans Condensed", "Fira Mono", "PT Serif"))
		{
			Font f = Fonts.font(family, "plain", 48);
			assertEquals(family, f.getFamily(), "drawn with the bundled face, not a fallback");
			assertEquals(48f, f.getSize2D());
		}
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> bundled = (List<Map<String, Object>>)Fonts.list().get("bundled");
		assertEquals(4, bundled.size());
		assertTrue(bundled.stream().allMatch(b -> b.get("shadowedBy") == null), bundled.toString());
	}

	@Test
	void everyStyleDrawsAndDiffers()
	{
		for(String family: List.of("Fira Sans", "Fira Sans Condensed", "Fira Mono", "PT Serif"))
		{
			int plain = ink(Fonts.font(family, "plain", 48), "Hamburg")[2];
			int bold = ink(Fonts.font(family, "bold", 48), "Hamburg")[2];
			assertTrue(bold > plain * 1.15, family + " bold has more ink than plain: " + bold + " vs " + plain);
			// against the plain face's own shape (Fira's l curls right at its foot)
			int upright = slant(Fonts.font(family, "plain", 96)), uprightBold = slant(Fonts.font(family, "bold", 96));
			assertTrue(slant(Fonts.font(family, "italic", 96)) > upright + 3, family + " italic leans right of plain");
			assertTrue(slant(Fonts.font(family, "bolditalic", 96)) > uprightBold + 3, family + " bold italic leans right of bold");
		}
	}

	// D3: the family name is only a family; "bold" in it does nothing, fontStyle decides
	@Test
	void theStyleComesFromFontStyleNotTheName()
	{
		ObjectConfig named = text(Map.of("font", "Fira Sans", "fontStyle", "plain"));
		ObjectConfig bold = text(Map.of("font", "Fira Sans", "fontStyle", "bold"));
		assertNotEquals(named.awt_font().getFontName(), bold.awt_font().getFontName());
		assertTrue(bold.awt_font().getFontName().contains("Bold"), bold.awt_font().getFontName());
		ObjectConfig boldInName = text(Map.of("font", "Fira Sans Bold", "fontStyle", "plain"));
		assertFalse(boldInName.awt_font().getFontName().contains("Fira Sans Bold"), "no style parsed out of the name");
	}

	// D2: the font is built from its arguments: each scale gets its own size, and the cache never hands back another
	@Test
	void eachScaleGetsItsOwnSize()
	{
		ObjectConfig t = text(Map.of("font", "Fira Mono", "fontStyle", "bold", "fontSize", 40));
		assertEquals(40f, t.awt_font().getSize2D());
		assertEquals(20f, t.awt_font(0.5).getSize2D());
		assertEquals(26.666666f, t.awt_font(2.0 / 3.0).getSize2D(), 0.001f);
		assertEquals("Fira Mono", t.awt_font(0.5).getFamily());
		assertEquals(40f, t.awt_font().getSize2D(), "unchanged by the scaled ones");
	}

	@Test
	void logicalAndUnknownFamiliesStillDraw()
	{
		assertEquals("Serif", Fonts.font("Serif", "bold", 20).getName());
		assertNotNull(Fonts.font("No Such Family", "plain", 20), "Java falls back as it always has");
		assertEquals("Fira Sans", Fonts.font(null, "plain", 20).getFamily(), "no family: the default");
	}

	@Test
	void checkNamesTheCharactersAFontCannotDraw()
	{
		Map<String, Object> c = Fonts.check("Fira Sans", "plain", "Hello Привет Γειά 你好 ✓");
		assertEquals("Fira Sans", c.get("uses"));
		@SuppressWarnings("unchecked")
		List<String> missing = (List<String>)c.get("missing");
		assertTrue(missing.containsAll(List.of("你", "好")), "no CJK in Fira Sans: " + missing);
		assertFalse(missing.contains("П") || missing.contains("Γ") || missing.contains("H"), "Latin, Cyrillic and Greek are covered: " + missing);
	}

	// with no fonts on the server (Linux without fontconfig) text is reported unavailable and the rest still draws
	@Test
	void aServerWithoutFontsDrawsEverythingButText()
	{
		Fonts.State broken = Fonts.register(f -> { throw new InternalError("Fontconfig head is null, check your fonts or fonts configuration"); }, f -> true);
		assertNotNull(broken.unavailable());
		assertTrue(broken.unavailable().contains("fontconfig"), broken.unavailable());

		OverlayConfig config = new OverlayConfig();
		config.setOpacity(100);
		ObjectConfig box = JSON.convertValue(Map.of("id", "box", "type", "rectangle", "x", 0, "y", 0, "w", 20, "h", 20, "fillColor", "rgb(255,0,0)",
				"color", "rgb(255,0,0)"), ObjectConfig.class);
		box.applyDefaults();
		config.addObject(box);
		ObjectConfig unknown = text(Map.of("font", "Fira Sans", "fontStyle", "plain"));
		config.addObject(unknown);
		OverlayImage img = new OverlayImage(0, 0, 64, 64, config, false);
		assertNotNull(img.current(1.0), "the overlay still draws");
	}

	static ObjectConfig text(Map<String, Object> m)
	{
		java.util.Map<String, Object> all = new java.util.LinkedHashMap<>(Map.of("id", "t", "type", "string", "text", "Hamburg", "x", 10, "y", 50,
				"fontSize", 32, "color", "rgb(255,255,255)"));
		all.putAll(m);
		ObjectConfig o = JSON.convertValue(all, ObjectConfig.class);
		o.applyDefaults();
		return o;
	}
}
