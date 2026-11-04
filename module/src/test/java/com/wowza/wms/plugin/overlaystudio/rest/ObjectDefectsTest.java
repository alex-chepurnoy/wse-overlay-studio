/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wowza.wms.rest.ConfigBase;

/** Defects D5 to D8, each as it shows on the object REST and draw paths. */
class ObjectDefectsTest
{
	static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	static ObjectConfig object(Map<String, Object> m)
	{
		return JSON.convertValue(m, ObjectConfig.class);
	}

	static Map<String, Object> page()
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", "score");
		m.put("type", "webPage");
		m.put("url", "https://scores.example.com/bug");
		m.put("x", 100);
		m.put("y", 40);
		m.put("w", 640);
		m.put("h", 360);
		m.put("viewportWidth", 1280);
		m.put("viewportHeight", 720);
		m.put("keyColor", "rgb(0,255,0)");
		m.put("keyTolerance", 30);
		m.put("paintBackground", false);
		m.put("cropX", 20);
		m.put("cropY", 10);
		m.put("cropW", 300);
		m.put("cropH", 200);
		return m;
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> asMap(ObjectConfig o)
	{
		return JSON.convertValue(o, Map.class);
	}

	// D5: a partial PUT is merged over the stored object; every field it does not name stays as it was
	@Test
	void aPartialPutKeepsViewportKeyBackgroundAndCrop()
	{
		ObjectConfig stored = object(page());
		ObjectConfig put = object(Map.of("id", "score", "x", 120));
		put.mergeOjbect(stored);
		Map<String, Object> after = asMap(put);
		assertEquals(120, after.get("x"));
		for(String k: new String[] { "viewportWidth", "viewportHeight", "keyColor", "keyTolerance", "paintBackground", "cropX", "cropY", "cropW", "cropH" })
		{
			assertEquals(page().get(k), after.get(k), k);
		}
	}

	@Test
	void aPartialPutCanSetThoseFieldsToZero()
	{
		ObjectConfig stored = object(page());
		ObjectConfig put = object(Map.of("id", "score", "cropW", 0, "cropH", 0, "keyTolerance", 0, "paintBackground", true));
		put.mergeOjbect(stored);
		Map<String, Object> after = asMap(put);
		assertEquals(0, after.get("cropW"));
		assertEquals(0, after.get("cropH"));
		assertEquals(0, after.get("keyTolerance"));
		assertEquals(true, after.get("paintBackground"));
		assertEquals(20, after.get("cropX"), "not named, kept");
	}

	// D6: a POST with no type, or one the module does not draw, is refused as a client error naming the field
	@Test
	void aPostWithoutOrWithAnUnknownTypeIsRefusedNamingType()
	{
		for(Map<String, Object> m: new Map[] { Map.of("id", "a"), Map.of("id", "a", "type", "hexagon") })
		{
			ObjectConfig o = object(m);
			ConfigBase.ConfigBaseException e = assertThrows(ConfigBase.ConfigBaseException.class, o::saveNewObject, m.toString());
			assertTrue(e.errors.containsKey("type"), e.errors.toString());
		}
	}

	// D7: no equals that compares some fields and not others, nor WSE's reflective one over runtime state
	@Test
	void objectsCompareByIdentityOnly()
	{
		ObjectConfig a = object(page()), b = object(page());
		assertNotEquals(a, b, "the same fields, two objects");
		assertEquals(a, a);
		assertEquals(System.identityHashCode(a), a.hashCode());
	}

	// D8: even coordinates are applied when drawing, never written back into the object
	@Test
	void evenCoordinatesDoNotChangeTheStoredFields()
	{
		ObjectConfig r = object(Map.of("id", "box", "type", "rectangle", "x", 5, "y", 7, "w", 9, "h", 11, "fillColor", "red", "color", "red", "stroke", 1));
		r.applyDefaults();
		BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		for(int i = 0; i < 3; i++)
		{
			r.draw(g, 1.0, true);
		}
		g.dispose();
		Map<String, Object> after = asMap(r);
		assertEquals(5, after.get("x"));
		assertEquals(7, after.get("y"));
		assertEquals(9, after.get("w"));
		assertEquals(11, after.get("h"));
		// drawn at the even position: nothing at x 5, the fill starts at x 6
		assertEquals(0, img.getRGB(5, 12) >>> 24, "x 5 is left clear");
		assertTrue((img.getRGB(7, 12) >>> 24) > 0, "the fill is drawn from x 6");
	}
}
