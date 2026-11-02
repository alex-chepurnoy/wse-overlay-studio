/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wowza.wms.plugin.overlaystudio.rest.ObjectConfig;
import com.wowza.wms.plugin.overlaystudio.rest.OverlayConfig;

/** An overlay draws only the part of its canvas its layers can paint, and hands over that part in the right place. */
class BoundingBoxTest
{
	static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	static ObjectConfig layer(Map<String, Object> m)
	{
		ObjectConfig o = JSON.convertValue(m, ObjectConfig.class);
		o.applyDefaults();
		return o;
	}

	static OverlayImage image(int x, int y, int w, int h, ObjectConfig... objects)
	{
		OverlayConfig config = new OverlayConfig();
		config.setOpacity(100);
		for(ObjectConfig o: objects)
		{
			config.addObject(o);
		}
		return new OverlayImage(x, y, w, h, config, false);
	}

	/** The published record laid onto a full canvas, as the transcoder places it. */
	static BufferedImage onCanvas(OverlayImage.Published p, int x0, int y0, int w, int h)
	{
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		for(int y = 0; y < p.height(); y++)
		{
			for(int x = 0; x < p.width(); x++)
			{
				int i = (y * p.width() + x) * 4;
				// B, G, R, A, as WSE takes an overlay frame
				int b = p.buffer()[i] & 0xff, g = p.buffer()[i + 1] & 0xff, r = p.buffer()[i + 2] & 0xff, a = p.buffer()[i + 3] & 0xff;
				int cx = p.x() - x0 + x, cy = p.y() - y0 + y;
				if(cx >= 0 && cy >= 0 && cx < w && cy < h)
				{
					out.setRGB(cx, cy, (a << 24) | (r << 16) | (g << 8) | b);
				}
			}
		}
		return out;
	}

	@Test
	void aSmallShapeIsDrawnInASmallBoxAtItsPlace()
	{
		OverlayImage img = image(100, 50, 1280, 720, layer(Map.of("id", "box", "type", "rectangle", "x", 41, "y", 40, "w", 100, "h", 20,
				"fillColor", "rgb(255,0,0)", "color", "rgb(255,0,0)", "stroke", 1)));
		OverlayImage.Published p = img.current(1.0);
		assertTrue(p.width() < 120 && p.height() < 40, "far smaller than the 1280 x 720 canvas: " + p.width() + " x " + p.height());
		assertEquals(0, p.x() % 2, "even origin");
		assertEquals(0, p.y() % 2, "even origin");
		BufferedImage c = onCanvas(p, 100, 50, 1280, 720);
		assertEquals(0xffff0000, c.getRGB(60, 50), "the fill lands where the layer says");
		assertEquals(0, c.getRGB(30, 50) >>> 24, "nothing outside it");

		OverlayImage.Published half = img.current(0.5);
		assertTrue(half.width() <= p.width() / 2 + 8, "the box scales with the output");
		assertEquals(0xffff0000, onCanvas(half, 50, 25, 640, 360).getRGB(30, 25));
	}

	// a proportional-font clock never clips as its width changes: text counts as the full width over its line height
	@Test
	void textSpansTheWholeWidthOverItsLineHeight()
	{
		ObjectConfig clock = layer(Map.of("id", "clock", "type", "string", "text", "{time:HH:mm:ss}", "x", 60, "y", 120,
				"font", "SansSerif", "fontSize", 64, "color", "rgb(255,255,255)"));
		Rectangle b = image(0, 0, 1280, 720, clock).drawBox(java.util.List.of(clock), 1.0);
		assertEquals(0, b.x);
		assertEquals(1280, b.width);
		assertTrue(b.y < 120 - 40 && b.y + b.height > 120, "the line around the baseline: " + b);
		assertTrue(b.height < 120, "only the line, not the canvas: " + b);
	}

	@Test
	void aLayerThatMayPaintAnywhereTakesTheWholeCanvas()
	{
		// rotation returns with shapes (plan 3.4) and will count here too
		ObjectConfig sizelessPage = layer(Map.of("id", "p", "type", "webPage", "url", "https://example.com/"));
		for(ObjectConfig o: new ObjectConfig[] { sizelessPage })
		{
			assertEquals(new Rectangle(0, 0, 640, 360), image(0, 0, 640, 360, o).drawBox(java.util.List.of(o), 1.0), o.getId());
		}
	}

	@Test
	void theSameLayersReuseTheirBuffers()
	{
		ObjectConfig box = layer(Map.of("id", "box", "type", "rectangle", "x", 40, "y", 40, "w", 100, "h", 20, "fillColor", "red"));
		OverlayImage img = image(0, 0, 1280, 720, box);
		BufferedImage first = img.getBufferedImage(1.0, 120, 40);
		assertSame(first, img.getBufferedImage(1.0, 120, 40));
	}
}
