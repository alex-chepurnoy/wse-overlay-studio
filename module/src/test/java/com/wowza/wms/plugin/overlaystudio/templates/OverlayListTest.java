/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** What the overlay list adds per overlay (plan 4.5): kinds, imageFiles and hash. */
class OverlayListTest
{
	static Map<String, Object> layer(String type, Object... kv)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", type + kv.length);
		m.put("type", type);
		for(int i = 0; i < kv.length; i += 2)
		{
			m.put((String)kv[i], kv[i + 1]);
		}
		return m;
	}

	@Test
	void kindsForEveryType()
	{
		assertEquals(List.of("webPage"), OverlayStore.kinds(Map.of("objects", List.of(layer("webPage")))));
		assertEquals(List.of("text"), OverlayStore.kinds(Map.of("objects", List.of(layer("string", "text", "HOME 2 - 1")))));
		assertEquals(List.of("text", "datetime"), OverlayStore.kinds(Map.of("objects", List.of(layer("string", "text", "{time:HH:mm}")))));
		for(String s: List.of("rectangle", "ellipse", "line", "triangle"))
		{
			assertEquals(List.of("shape"), OverlayStore.kinds(Map.of("objects", List.of(layer(s)))), s);
		}
		assertEquals(List.of("image"), OverlayStore.kinds(Map.of("objects", List.of(layer("image")))));
		assertEquals(List.of(), OverlayStore.kinds(Map.of("objects", List.of(layer("hologram")))), "unknown types report nothing");
		// fixed order, each once
		assertEquals(OverlayStore.KINDS, OverlayStore.kinds(Map.of("objects", List.of(layer("image"), layer("ellipse"), layer("string", "text", "{countdown:m:ss}"),
				layer("webPage"), layer("rectangle")))));
		assertEquals(List.of("text"), OverlayStore.kinds(Map.of("objects", List.of(layer("string", "text", "{{literal}}")))), "escaped braces are no token");
	}

	@Test
	void imageFilesOnceEach()
	{
		assertEquals(List.of("overlaystudio/a.png", "b.png"), OverlayStore.imageFiles(Map.of("objects",
				List.of(layer("image", "imageFile", "overlaystudio/a.png"), layer("image", "imageFile", "b.png"), layer("image", "x", 1, "imageFile", "overlaystudio/a.png"), layer("image")))));
	}

	@Test
	void theHashIgnoresKeyOrderAndFieldsTheStoreDoesNotKeep()
	{
		Map<String, Object> a = new LinkedHashMap<>();
		a.put("opacity", 100);
		a.put("objects", List.of(new LinkedHashMap<>(Map.of("id", "t", "type", "string", "text", "hi"))));
		Map<String, Object> b = new LinkedHashMap<>();
		Map<String, Object> t = new LinkedHashMap<>();
		t.put("text", "hi");
		t.put("type", "string");
		t.put("id", "t");
		b.put("objects", List.of(t));
		b.put("opacity", 100);
		b.put("renamed", Map.of("x", "t"));
		b.put("name", "copied by hand");
		assertEquals(OverlayStore.hash(a), OverlayStore.hash(b));
		t.put("text", "bye");
		assertNotEquals(OverlayStore.hash(a), OverlayStore.hash(b));
		assertEquals(64, OverlayStore.hash(a).length());
	}
}
