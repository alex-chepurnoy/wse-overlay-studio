/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.wowza.wms.plugin.overlaystudio.rest.ObjectConfig;

/** Validation of saved overlays and live values (plan 3.10): every refusal names its field, the limits, renames, warnings. */
class ValidationTest
{
	@TempDir
	Path dir;

	static Map<String, Object> layer(String id, Object... kv)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", id);
		m.put("type", "string");
		for(int i = 0; i < kv.length; i += 2)
		{
			if(kv[i + 1] == null)
			{
				m.remove((String)kv[i]);
			}
			else
			{
				m.put((String)kv[i], kv[i + 1]);
			}
		}
		return m;
	}

	static Map<String, Object> overlay(List<Map<String, Object>> layers)
	{
		Map<String, Object> o = new LinkedHashMap<>();
		o.put("objects", new ArrayList<>(layers));
		return o;
	}

	TemplateFiles.Refused refused(Map<String, Object> overlay)
	{
		return assertThrows(TemplateFiles.Refused.class, () -> OverlayStore.write(dir, "o", overlay, u -> null, f -> 0L));
	}

	@Test
	void everyRefusedLayerFieldIsNamed()
	{
		Object[][] cases = { { "type", null }, { "type", "circle" }, { "color", "blue-ish" }, { "fillColor", "rgb(300,0,0)" }, { "opacity", 101 }, { "stroke", "4" },
				{ "fontSize", 0 }, { "fontStyle", "heavy" }, { "x", "10" }, { "rotation", 400 }, { "dash", "wavy" }, { "arrowHead", "start" },
				{ "imageFile", "../x.png" }, { "timeZone", "Mars/Base" }, { "locale", 7 }, { "target", "tomorrow" }, { "showFrom", "soon" }, { "blinkSeconds", 0 },
				{ "fadeIn", 3 }, { "box", "yes" }, { "align", "middle" }, { "lineHeight", 9 }, { "padding", -1 }, { "text", "{time:HH" }, { "base64Image", "iVBO" },
				{ "background", 12 }, { "outlineColor", "rgb(1,2)" } };
		for(Object[] c: cases)
		{
			TemplateFiles.Refused r = refused(overlay(List.of(layer("score", c[0], c[1]))));
			assertEquals(400, r.status(), c[0] + "=" + c[1]);
			assertEquals(c[0], r.field(), c[0] + "=" + c[1] + ": " + r.getMessage());
			assertEquals("score", r.id());
		}
	}

	@Test
	void idsAreCheckedAndUnique()
	{
		for(String bad: new String[] { "a..b", "has space", "", "-lead", "x".repeat(65), "a/b" })
		{
			TemplateFiles.Refused r = refused(overlay(List.of(layer(bad))));
			assertEquals("id", r.field(), "'" + bad + "': " + r.getMessage());
		}
		TemplateFiles.Refused twice = refused(overlay(List.of(layer("score"), layer("score"))));
		assertEquals("id", twice.field());
		assertTrue(twice.getMessage().contains("two layers"), twice.getMessage());
		assertNull(ObjectConfig.idProblem("Score_2.home-1"));
	}

	@Test
	void overlayLevelFieldsAreNamedWithNoId()
	{
		Object[][] cases = { { "color", "nope" }, { "opacity", 200 }, { "refreshRate", -1 }, { "maxDrawingTime", "fast" }, { "threadDrawing", "yes" }, { "w", 9000 },
				{ "h", 8193 }, { "fontSize", 2000 }, { "objects", "none" } };
		for(Object[] c: cases)
		{
			Map<String, Object> o = overlay(List.of(layer("a")));
			o.put((String)c[0], c[1]);
			TemplateFiles.Refused r = refused(o);
			assertEquals(c[0], r.field(), c[0] + "=" + c[1] + ": " + r.getMessage());
			assertNull(r.id());
		}
	}

	@Test
	void limits() throws Exception
	{
		List<Map<String, Object>> many = new ArrayList<>();
		for(int i = 0; i < OverlayStore.MAX_LAYERS + 1; i++)
		{
			many.add(layer("l" + i));
		}
		assertEquals("objects", refused(overlay(many)).field(), "65 layers");
		many.remove(0);
		OverlayStore.write(dir, "o", overlay(many), u -> null, f -> 0L);

		assertEquals("text", refused(overlay(List.of(layer("t", "text", "x".repeat(ObjectConfig.TEXT_MAX + 1))))).field());

		// a field the module does not know is kept, so the file size is what holds it back
		List<Map<String, Object>> big = new ArrayList<>();
		for(int i = 0; i < 60; i++)
		{
			big.add(layer("b" + i, "note", "n".repeat(20_000)));
		}
		TemplateFiles.Refused size = refused(overlay(big));
		assertEquals("objects", size.field());
		assertTrue(size.getMessage().contains("KB"), size.getMessage());

		Map<String, Object> a = layer("a", "type", "image", "imageFile", "a.png");
		Map<String, Object> a2 = layer("a2", "type", "image", "imageFile", "a.png");
		Map<String, Object> b = layer("b", "type", "image", "imageFile", "b.png");
		// one 20 million pixel picture shown twice is decoded once
		OverlayStore.write(dir, "o", overlay(List.of(a, a2)), u -> null, f -> 20_000_000L);
		TemplateFiles.Refused px = assertThrows(TemplateFiles.Refused.class, () -> OverlayStore.write(dir, "o", overlay(List.of(a, a2, b)), u -> null, f -> 20_000_000L));
		assertEquals("imageFile", px.field());
		assertEquals("b", px.id());
	}

	@Test
	void renamedMapsAreCheckedAndNeverStored() throws Exception
	{
		Set<String> before = Set.of("text-3", "logo", "clock"), after = Set.of("score", "logo", "clock", "ticker");
		assertEquals(Map.of("text-3", "score"), OverlayStore.renamed(Map.of("text-3", "score"), before, after));
		assertTrue(OverlayStore.renamed(null, before, after).isEmpty());
		Object[] bad = { Map.of("gone", "score"), Map.of("logo", "score"), Map.of("text-3", "nowhere"), Map.of("text-3", "clock"), "text-3=score", Map.of("text-3", 5) };
		for(Object b: bad)
		{
			TemplateFiles.Refused r = assertThrows(TemplateFiles.Refused.class, () -> OverlayStore.renamed(b, before, after), b.toString());
			assertEquals("renamed", r.field(), b + ": " + r.getMessage());
		}
		// two to one
		Set<String> two = Set.of("a", "b");
		assertEquals("renamed", assertThrows(TemplateFiles.Refused.class, () -> OverlayStore.renamed(Map.of("a", "c", "b", "c"), two, Set.of("c"))).field());
		// a chain: a goes to b while b goes to c
		assertEquals("renamed", assertThrows(TemplateFiles.Refused.class, () -> OverlayStore.renamed(new LinkedHashMap<>(Map.of("a", "b", "b", "c")), Set.of("a", "b"), Set.of("b", "c"))).field());

		Map<String, Object> o = overlay(List.of(layer("score")));
		o.put("renamed", Map.of("text-3", "score"));
		OverlayStore.write(dir, "o", o, u -> null, f -> 0L);
		assertFalse(Files.readString(OverlayStore.file(dir, "o")).contains("renamed"));
	}

	@Test
	void aMissingFontSavesWithAWarning() throws Exception
	{
		Map<String, Object> o = overlay(List.of(layer("t", "font", "No Such Font 9"), layer("ok", "font", "Fira Sans")));
		Map<String, Object> saved = OverlayStore.write(dir, "o", o, u -> null, f -> 0L);
		List<Map<String, Object>> w = OverlayStore.warnings(saved);
		assertEquals(1, w.size(), w.toString());
		assertEquals("t", w.get(0).get("id"));
		assertEquals("font", w.get(0).get("field"));
		assertNotNull(OverlayStore.read(dir, "o"), "saved");
	}

	@Test
	void liveFieldsFitTheirTypes()
	{
		Map<String, Object> text = layer("score"), box = layer("bar", "type", "rectangle");
		assertNull(ObjectConfig.liveProblem(text, Map.of("text", "HOME 2 - 1 AWAY", "color", "rgb(255,0,0)", "background", "black", "opacity", 50, "hidden", false)));
		assertNull(ObjectConfig.liveProblem(box, Map.of("color", "white", "hidden", true)));
		Object[][] bad = { { text, "size", 3, "size" }, { text, "text", 5, "text" }, { text, "text", "{countdown", "text" }, { text, "text", "x".repeat(2001), "text" },
				{ text, "color", "reddish", "color" }, { text, "opacity", 101, "opacity" }, { text, "hidden", "no", "hidden" }, { box, "text", "x", "text" },
				{ box, "background", "white", "background" } };
		for(Object[] b: bad)
		{
			@SuppressWarnings("unchecked")
			String[] p = ObjectConfig.liveProblem((Map<String, Object>)b[0], Map.of((String)b[1], b[2]));
			assertNotNull(p, b[1] + "=" + b[2]);
			assertEquals(b[3], p[0]);
		}
		assertNotNull(ObjectConfig.liveProblem(text, Map.of()), "an empty value");
	}
}
