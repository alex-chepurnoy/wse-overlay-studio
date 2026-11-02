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
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
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

/** Shapes (plan 3.4): rotation either way (D10), dashed and dotted outlines, arrow heads, their checks, goldens. */
class ShapeLayerTest
{
	static ObjectConfig shape(Map<String, Object> fields)
	{
		Map<String, Object> m = new LinkedHashMap<>(Map.of("id", "s", "opacity", 100, "color", "rgb(255,255,255)", "stroke", 4));
		m.putAll(fields);
		return ObjectDefectsTest.object(m);
	}

	static BufferedImage draw(ObjectConfig o, int w, int h, double scale)
	{
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		o.draw(g, scale, false);
		g.dispose();
		return img;
	}

	static int alpha(BufferedImage img, int x, int y)
	{
		return img.getRGB(x, y) >>> 24;
	}

	// D10: any sign turns the shape, about its center; 1.x applied only values above 0
	@Test
	void rotationEitherWayAboutTheCenter()
	{
		Map<String, Object> bar = Map.of("type", "rectangle", "x", 40, "y", 90, "w", 120, "h", 20, "fillColor", "rgb(255,0,0)", "color", "rgb(255,0,0)");
		BufferedImage flat = draw(shape(bar), 200, 200, 1.0);
		assertTrue(alpha(flat, 50, 100) > 200 && alpha(flat, 100, 50) == 0, "a level bar");
		for(int deg: new int[] { 90, -90 })
		{
			Map<String, Object> m = new LinkedHashMap<>(bar);
			m.put("rotation", deg);
			BufferedImage turned = draw(shape(m), 200, 200, 1.0);
			assertTrue(alpha(turned, 100, 50) > 200, deg + ": now upright through the center");
			assertEquals(0, alpha(turned, 50, 100), deg + ": not where it lay");
			Rectangle b = shape(m).drawBounds(1.0);
			assertTrue(b.width < b.height && b.contains(100, 45) && b.contains(100, 155), deg + ": bounds turn with it: " + b);
		}
		Map<String, Object> slight = new LinkedHashMap<>(bar);
		slight.put("rotation", -15);
		Rectangle b = shape(slight).drawBounds(1.0);
		assertTrue(b.height > 40, "a slight turn widens the area: " + b);
	}

	@Test
	void dashedAndDottedOutlinesHaveGaps()
	{
		for(String dash: List.of("dashed", "dotted"))
		{
			BufferedImage img = draw(shape(Map.of("type", "line", "x", 10, "y", 50, "x2", 190, "y2", 50, "dash", dash)), 200, 100, 1.0);
			int on = 0, off = 0;
			for(int x = 12; x < 188; x++)
			{
				if(alpha(img, x, 50) > 128) on++; else off++;
			}
			assertTrue(on > 10 && off > 10, dash + " has marks and gaps: " + on + " on, " + off + " off");
		}
		BufferedImage solid = draw(shape(Map.of("type", "line", "x", 10, "y", 50, "x2", 190, "y2", 50)), 200, 100, 1.0);
		for(int x = 12; x < 188; x++)
		{
			assertTrue(alpha(solid, x, 50) > 128, "solid at " + x);
		}
	}

	@Test
	void arrowHeadsAtTheEndOrBoth()
	{
		Map<String, Object> line = Map.of("type", "line", "x", 20, "y", 50, "x2", 180, "y2", 50, "stroke", 2);
		int[] spread = { 0, 0, 0 };
		for(String a: List.of("none", "end", "both"))
		{
			Map<String, Object> m = new LinkedHashMap<>(line);
			m.put("arrowHead", a);
			BufferedImage img = draw(shape(m), 200, 100, 1.0);
			// how tall the ink is near each end: an arrow head is wider than the 2 px line
			// 6 px back from each end, inside a head
			spread[0] = height(img, 174);
			spread[1] = height(img, 26);
			if(a.equals("none")) assertTrue(spread[0] <= 3 && spread[1] <= 3, "a plain line");
			if(a.equals("end")) assertTrue(spread[0] > 4 && spread[1] <= 3, "a head at the end only: " + spread[0] + ", " + spread[1]);
			if(a.equals("both")) assertTrue(spread[0] > 4 && spread[1] > 4, "a head at each end");
		}
		Map<String, Object> m = new LinkedHashMap<>(line);
		m.put("arrowHead", "end");
		Rectangle b = shape(m).drawBounds(1.0);
		assertTrue(b.y < 50 - 4 && b.y + b.height > 50 + 4, "the head is inside the drawn area: " + b);
	}

	static int height(BufferedImage img, int x)
	{
		int n = 0;
		for(int y = 0; y < img.getHeight(); y++)
		{
			if(alpha(img, x, y) > 128) n++;
		}
		return n;
	}

	@Test
	void shapeAndImageFieldsAreChecked() throws Exception
	{
		assertNull(ObjectConfig.shapeProblem(Map.of("rotation", -45.5, "dash", "dotted", "arrowHead", "both", "lockAspect", false, "imageFile", "overlaystudio/logo.png")));
		Object[][] bad = { { "rotation", 400 }, { "rotation", "90" }, { "dash", "wavy" }, { "arrowHead", "start" }, { "lockAspect", 1 }, { "imageFile", "../secrets.png" },
				{ "imageFile", "logo.svg" } };
		for(Object[] b: bad)
		{
			String[] p = ObjectConfig.shapeProblem(Map.of((String)b[0], b[1]));
			assertNotNull(p, b[0] + " " + b[1]);
			assertEquals(b[0], p[0]);
		}
		ConfigBase.ConfigBaseException e = assertThrows(ConfigBase.ConfigBaseException.class, shape(Map.of("type", "line", "dash", "wavy"))::saveNewObject);
		assertTrue(e.errors.containsKey("dash"), e.errors.toString());
	}

	@TempDir
	Path dir;

	@Test
	void anOverlayWithBase64ImageIsRefused()
	{
		Map<String, Object> overlay = new LinkedHashMap<>();
		overlay.put("objects", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("id", "a", "type", "image", "base64Image", "iVBORw0KGgo=")))));
		TemplateFiles.Refused x = assertThrows(TemplateFiles.Refused.class, () -> OverlayStore.write(dir, "o", overlay, u -> null));
		assertTrue(x.getMessage().contains("imageFile"), x.getMessage());
	}

	static Map<String, Map<String, Object>> goldens()
	{
		Map<String, Map<String, Object>> g = new LinkedHashMap<>();
		g.put("shape-rounded-rect", Map.of("type", "rectangle", "x", 20, "y", 20, "w", 200, "h", 70, "arcWidth", 24, "arcHeight", 24, "fillColor", "rgb(30,90,200)", "stroke", 3));
		g.put("shape-ellipse-dotted", Map.of("type", "ellipse", "x", 20, "y", 15, "w", 160, "h", 90, "dash", "dotted", "stroke", 5));
		g.put("shape-line-arrows", Map.of("type", "line", "x", 20, "y", 60, "x2", 360, "y2", 40, "arrowHead", "both", "stroke", 3, "color", "rgb(255,200,0)"));
		g.put("shape-triangle-dashed", Map.of("type", "triangle", "x", 30, "y", 100, "x2", 130, "y2", 10, "x3", 230, "y3", 100, "dash", "dashed", "stroke", 3,
				"fillColor", "rgb(0,160,80)"));
		g.put("shape-rotated-minus-20", Map.of("type", "rectangle", "x", 100, "y", 40, "w", 180, "h", 40, "rotation", -20, "fillColor", "rgb(200,0,0)", "color", "rgb(200,0,0)"));
		g.put("shape-gradient-shadow", Map.of("type", "rectangle", "x", 20, "y", 20, "w", 260, "h", 70, "fillColor", "rgb(255,0,128)", "gradientColor", "rgb(0,0,128)",
				"gradientDirection", "right", "shadowColor", "rgb(0,0,0)", "shadowOffset", 6));
		return g;
	}

	@Test
	void goldenImages() throws Exception
	{
		Path d = Path.of(System.getProperty("overlaystudio.goldens"));
		boolean write = "1".equals(System.getenv("OVS_WRITE_GOLDENS"));
		for(Map.Entry<String, Map<String, Object>> e: goldens().entrySet())
		{
			for(double scale: new double[] { 1.0, 0.5 })
			{
				ObjectConfig o = shape(e.getValue());
				o.applyDefaults();
				BufferedImage img = draw(o, (int)(400 * scale), (int)(120 * scale), scale);
				Path f = d.resolve(e.getKey() + (scale == 1.0 ? "" : "-half") + ".png");
				if(write)
				{
					Files.createDirectories(d);
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
