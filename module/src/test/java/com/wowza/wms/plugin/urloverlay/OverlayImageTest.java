/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import org.junit.jupiter.api.Test;

import com.wowza.wms.plugin.urloverlay.rest.OverlayConfig;

/** Background drawing: a failed draw does not stop later ones, and a finished draw is handed over whole. */
class OverlayImageTest
{
	static void awaitIdle(OverlayImage img) throws Exception
	{
		long end = System.currentTimeMillis() + 10000;
		while(img.isDrawing() && System.currentTimeMillis() < end)
		{
			Thread.sleep(10);
		}
		assertFalse(img.isDrawing(), "the draw thread finished");
	}

	@Test
	void aDrawThatFailsDoesNotBlockTheNext() throws Exception
	{
		// a zero-size overlay cannot be drawn: BufferedImage refuses it
		OverlayImage img = new OverlayImage(0, 0, 0, 0, new OverlayConfig(), true);
		img.getCurrentImageBuffer(1.0);
		awaitIdle(img);
		img.getCurrentImageBuffer(1.0);
		awaitIdle(img);
	}

	@Test
	void aFinishedDrawIsHandedOverAndTheNextGoesIntoTheOtherBuffer() throws Exception
	{
		OverlayConfig config = new OverlayConfig();
		OverlayImage img = new OverlayImage(0, 0, 16, 8, config, true);
		img.getCurrentImageBuffer(1.0);
		awaitIdle(img);
		byte[] first = img.getCurrentImageBuffer(1.0);
		assertNotNull(first, "drawn for the current content");
		assertEquals(16 * 8 * 4, first.length);
		config.resetUuid();
		img.getCurrentImageBuffer(1.0);
		awaitIdle(img);
		byte[] second = img.getCurrentImageBuffer(1.0);
		assertNotNull(second);
		assertNotSame(first, second, "the transcoder's copy is never written while it may be read");
	}
}
