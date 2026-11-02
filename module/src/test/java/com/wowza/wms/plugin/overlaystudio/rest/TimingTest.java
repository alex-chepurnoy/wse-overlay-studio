/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.wowza.wms.plugin.overlaystudio.templates.OverlayStore;
import com.wowza.wms.plugin.overlaystudio.templates.TemplateFiles;
import com.wowza.wms.rest.ConfigBase;

/** The timing fields of plan 3.3a, which replace displayStart, displayStop and flashRate (D9). */
class TimingTest
{
	static final long T = java.time.OffsetDateTime.parse("2027-01-01T10:00:00Z").toInstant().toEpochMilli();

	static ObjectConfig box(Map<String, Object> timing)
	{
		Map<String, Object> m = new LinkedHashMap<>(Map.of("id", "box", "type", "rectangle", "w", 10, "h", 10));
		m.putAll(timing);
		return ObjectDefectsTest.object(m);
	}

	// D9: a blink follows the wall clock, so it is the same however often and at however many scales the layer draws
	@Test
	void aBlinkFollowsTheClockNotTheDrawCount()
	{
		ObjectConfig b = box(Map.of("blinkSeconds", 0.5));
		assertEquals(1.0, b.timingAlpha(T, 0), "on in the first half second");
		assertEquals(1.0, b.timingAlpha(T + 499, 0));
		assertEquals(0.0, b.timingAlpha(T + 500, 0), "off in the second");
		for(int draws = 0; draws < 7; draws++)
		{
			assertEquals(0.0, b.timingAlpha(T + 700, 0), "the same however many times it draws");
		}
		assertEquals(T + 1000, b.nextChangeAt(T + 700, 0), "the next toggle");
	}

	@Test
	void aShowWindowWithFadesRampsInAndOut()
	{
		ObjectConfig b = box(Map.of("showFrom", "2027-01-01T05:00:00-05:00", "hideAt", "2027-01-01T10:00:10Z", "fadeIn", 1, "fadeOut", 2));
		assertEquals(0.0, b.timingAlpha(T - 1, 0), "before the window");
		assertEquals(0.5, b.timingAlpha(T + 500, 0), 1e-9, "halfway through the fade in");
		assertEquals(1.0, b.timingAlpha(T + 5000, 0));
		assertEquals(0.5, b.timingAlpha(T + 9000, 0), 1e-9, "halfway through the fade out");
		assertEquals(0.0, b.timingAlpha(T + 10000, 0), "after it");
		assertEquals(T, b.nextChangeAt(T - 60000, 0), "wakes at the start");
		assertEquals(T + 600, b.nextChangeAt(T + 500, 0), "every 100 ms while fading in");
		assertEquals(T + 8000, b.nextChangeAt(T + 5000, 0), "then at the start of the fade out");
		assertEquals(Long.MAX_VALUE, b.nextChangeAt(T + 10000, 0), "nothing after the window");
	}

	@Test
	void streamTimesCountFromTheTranscoderStart()
	{
		ObjectConfig b = box(Map.of("showFrom", "stream+30", "hideAt", "stream+90.5"));
		assertEquals(0.0, b.timingAlpha(T, 0), "start not known yet: not shown");
		assertEquals(0.0, b.timingAlpha(T + 29_999, T));
		assertEquals(1.0, b.timingAlpha(T + 30_000, T));
		assertEquals(0.0, b.timingAlpha(T + 90_500, T));
		assertEquals(T + 30_000, b.nextChangeAt(T, T));
	}

	@Test
	void malformedTimingIsRefusedNamingTheField() throws Exception
	{
		assertNull(ObjectConfig.timingProblem("stream+5", "2027-01-01T18:00:00-05:00", 1, 0, 2));
		Object[][] bad = { { "showFrom", "tomorrow" }, { "hideAt", "2027-01-01T18:00:00" }, { "showFrom", "stream+-5" },
				{ "blinkSeconds", 0 }, { "blinkSeconds", "1" }, { "fadeIn", 3 }, { "fadeOut", -1 } };
		for(Object[] b: bad)
		{
			Object[] args = new Object[5];
			args[List.of("showFrom", "hideAt", "blinkSeconds", "fadeIn", "fadeOut").indexOf(b[0])] = b[1];
			String[] p = ObjectConfig.timingProblem(args[0], args[1], args[2], args[3], args[4]);
			assertEquals(b[0], p == null ? null : p[0], b[0] + "=" + b[1]);
		}
		// on the per-stream REST path too
		ObjectConfig o = box(Map.of("fadeIn", 5));
		ConfigBase.ConfigBaseException e = assertThrows(ConfigBase.ConfigBaseException.class, o::saveNewObject);
		assertTrue(e.errors.containsKey("fadeIn"), e.errors.toString());
	}

	@TempDir
	Path dir;

	@Test
	void anOverlayWithA1xTimingFieldIsRefused() throws Exception
	{
		for(Map.Entry<String, String> r: ObjectConfig.REMOVED.entrySet())
		{
			Map<String, Object> layer = new LinkedHashMap<>(Map.of("id", "box", "type", "rectangle"));
			layer.put(r.getKey(), r.getKey().equals("displayStart") || r.getKey().equals("displayStop") ? "+5" : 2);
			Map<String, Object> overlay = new LinkedHashMap<>();
			overlay.put("objects", new ArrayList<>(List.of(layer)));
			TemplateFiles.Refused x = assertThrows(TemplateFiles.Refused.class, () -> OverlayStore.write(dir, "a", overlay, u -> null));
			assertEquals(400, x.status());
			assertTrue(x.getMessage().contains(r.getKey()) && x.getMessage().contains(r.getValue()), x.getMessage());
		}
	}
}
