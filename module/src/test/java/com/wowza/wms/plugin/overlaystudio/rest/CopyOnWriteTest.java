/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wowza.wms.plugin.overlaystudio.OverlayImage;
import com.wowza.wms.plugin.overlaystudio.WebPageCapture;

/** Edits replace objects rather than change them, and what a draw hands to the transcoder is one record. */
class CopyOnWriteTest
{
	static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	static ObjectConfig page(int x)
	{
		ObjectConfig o = JSON.convertValue(Map.of("id", "score", "type", "webPage", "url", "https://scores.example/cow", "x", x, "y", 0, "w", 64, "h", 36), ObjectConfig.class);
		o.applyDefaults();
		return o;
	}

	static WebPageCapture captureOf(ObjectConfig o) throws Exception
	{
		Field f = ObjectConfig.class.getDeclaredField("webPageCapture");
		f.setAccessible(true);
		return (WebPageCapture)f.get(o);
	}

	static WebPageCapture open(ObjectConfig o) throws Exception
	{
		Method m = ObjectConfig.class.getDeclaredMethod("getWebPageCapture");
		m.setAccessible(true);
		return (WebPageCapture)m.invoke(o);
	}

	// a page layer edited in place of the live one keeps the one capture: the new object shares it, none is opened anew
	@Test
	void anEditedPageLayerSharesItsCaptureAndOpensNone() throws Exception
	{
		OverlayConfig config = new OverlayConfig();
		ObjectConfig live = page(0);
		config.addObject(live);
		new OverlayImage(0, 0, 128, 72, config, false);
		WebPageCapture c = open(live);
		assertEquals(1, WebPageCapture.POOL.users(c));

		ObjectConfig edited = page(10);
		edited.shareCaptureOf(live);
		assertEquals(2, WebPageCapture.POOL.users(c), "the edit took its own reference");
		config.putObject(edited);
		assertSame(c, captureOf(edited));
		assertEquals(1, WebPageCapture.POOL.users(c), "the replaced layer let go of its reference, none was opened");
		assertNull(captureOf(live));
		assertEquals(List.of(edited), config.getObjects());
		edited.dispose();
	}

	// an edit gives the overlay a new list and a new uuid together; the list a draw took is never changed
	@Test
	void anEditSwapsTheListAndTheUuidTogether()
	{
		OverlayConfig config = new OverlayConfig();
		config.addObject(page(0));
		new OverlayImage(0, 0, 128, 72, config, false);
		List<ObjectConfig> taken = config.getObjects();
		UUID before = config.getUuid();
		config.putObject(page(20));
		assertNotSame(taken, config.getObjects());
		assertEquals(0, taken.get(0).getX(), "the list a draw took is unchanged");
		assertTrue(!before.equals(config.getUuid()), "the uuid changed with the list");
	}

	// the record the transcoder gets pairs the buffer with the size, place and content it was drawn for
	@Test
	void aPublishedRecordIsWholeAndForTheCurrentContent()
	{
		OverlayConfig config = new OverlayConfig();
		config.setOpacity(100);
		OverlayImage img = new OverlayImage(8, 4, 40, 20, config, false);
		for(double scale: new double[] { 1.0, 0.5 })
		{
			OverlayImage.Published p = img.current(scale);
			assertNotNull(p);
			assertEquals(p.width() * p.height() * 4, p.buffer().length);
			// an empty overlay publishes its smallest box at the overlay's own place
			assertEquals(2, p.width());
			assertEquals((int)(8 * scale), p.x());
			assertEquals(config.getUuid(), p.uuid());
		}
		OverlayImage.Published first = img.current(1.0);
		config.resetUuid();
		OverlayImage.Published second = img.current(1.0);
		assertEquals(config.getUuid(), second.uuid(), "redrawn for the new content");
		assertNotSame(first.buffer(), second.buffer(), "into the other buffer");
	}
}
