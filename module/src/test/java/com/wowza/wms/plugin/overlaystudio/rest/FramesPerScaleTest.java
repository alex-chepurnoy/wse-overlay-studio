/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;

import com.wowza.wms.plugin.overlaystudio.OverlayImage;
import com.wowza.wms.plugin.overlaystudio.WebPageCapture;

/**
 * A page's frame counts as drawn per output scale, and only once that scale's draw is published: one scale drawing a
 * newer frame never hides it from another, and only the scales the overlay goes to now count.
 */
class FramesPerScaleTest
{
	@SuppressWarnings("unchecked")
	static void drewFrame(ObjectConfig o, double scale, long seq) throws Exception
	{
		Field f = ObjectConfig.class.getDeclaredField("pendingSeq");
		f.setAccessible(true);
		((ConcurrentHashMap<Double, Long>)f.get(o)).put(scale, seq);
	}

	@Test
	void oneScaleDrawingAFrameDoesNotHideItFromAnother() throws Exception
	{
		OverlayConfig config = new OverlayConfig();
		ObjectConfig page = CopyOnWriteTest.page(0);
		config.addObject(page);
		OverlayImage img = new OverlayImage(0, 0, 128, 72, config, false);
		WebPageCapture c = CopyOnWriteTest.open(page);
		long seq = c.frameSeq();
		try
		{
			img.setActiveScales(Set.of(1.0, 0.5));
			assertTrue(config.hasNewFrame(), "neither scale has drawn it");

			drewFrame(page, 1.0, seq);
			assertTrue(config.hasNewFrame(), "drawn at 1.0 but not published yet");
			page.commitDrawn(1.0);
			assertTrue(config.hasNewFrame(), "published at 1.0, still new for 0.5");

			drewFrame(page, 0.5, seq);
			page.commitDrawn(0.5);
			assertFalse(config.hasNewFrame(), "both outputs show it");

			// the overlay moves to the source alone: only that scale counts from this frame on
			img.setActiveScales(Set.of(1.0));
			assertFalse(config.hasNewFrame());
			img.setActiveScales(Set.of(0.25));
			assertTrue(config.hasNewFrame(), "a scale that has never drawn it");
		}
		finally
		{
			page.dispose();
		}
	}

	@Test
	void onlyPagesHaveFrames()
	{
		ObjectConfig box = ObjectDefectsTest.object(Map.of("id", "box", "type", "rectangle"));
		assertFalse(box.hasNewFrame(Set.of(1.0, 0.5)));
	}
}
