/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

/**
 * What the plugin page's previews are sent: the size it shows, and a screenshot fresh enough for the capture
 * interval. Measured before this: a 1280 x 720 page sent at full size took 250 to 330 ms per answer, and a 900 ms reuse
 * window gave a new picture only every 0.9 to 2.3 s, skipping seconds of a clock.
 */
class CaptureSizeTest
{
	@Test
	void fitScalesDownKeepingTheShape()
	{
		BufferedImage img = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_ARGB);
		BufferedImage f = CaptureConfig.fit(img, 320);
		assertEquals(320, f.getWidth());
		assertEquals(180, f.getHeight());
		assertSame(img, CaptureConfig.fit(img, 0), "no width: full size");
		assertSame(img, CaptureConfig.fit(img, 2000), "never scaled up");
		// width accepts only a sensible whole number
		{
			assertEquals(320, CaptureConfig.width("320"));
			assertEquals(321, CaptureConfig.width(320.6));
			assertEquals(0, CaptureConfig.width(null));
			assertEquals(0, CaptureConfig.width("8"), "below 16 is full size");
			assertEquals(0, CaptureConfig.width("99999"));
			assertEquals(0, CaptureConfig.width("wide"));
		}
	}

	@Test
	void screenshotsAreReusedForMostOfTheCaptureInterval()
	{
		assertEquals(400, CaptureConfig.maxAgeMs(0.5), "a 0.5 s interval gets a fresh screenshot every time it asks");
		assertEquals(800, CaptureConfig.maxAgeMs("1"));
		assertEquals(400, CaptureConfig.maxAgeMs(null), "the default interval");
		assertEquals(80, CaptureConfig.maxAgeMs(0.01), "never below 0.1 s");
	}
}
