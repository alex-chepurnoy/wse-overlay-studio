/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** The service takes frames in the background at the refresh it is given; a request only waits for one. */
class BackgroundLoopTest
{
	static byte[] png(int w, int h)
	{
		byte[] b = new byte[32];
		b[0] = (byte)0x89;
		b[1] = 'P';
		b[2] = 'N';
		b[3] = 'G';
		ByteBuffer.wrap(b, 16, 8).putInt(w).putInt(h);
		return b;
	}

	@Test
	void framesAreTakenAtTheIntervalAndNumbered() throws Exception
	{
		byte[] png = png(320, 180);
		FakePage fake = new FakePage(png);
		AtomicInteger shots = fake.shots;
		WebPageCapture c = new WebPageCapture("http://example.com/", 320, 180, () -> 0, null);
		try
		{
			c.attachForScreenshots(fake);
			c.interval(60);
			assertEquals(0, c.seq());
			c.startLoop();
			c.startLoop();
			long first = c.awaitFrame(0, 3000);
			assertTrue(first >= 1, "a frame arrives without anyone asking for one");
			assertArrayEquals(png, c.lastPng(), "kept as Chrome encoded it");
			// No wall-clock window that a slow machine can miss. One loop sleeps at least the interval minus the shot's own
			// time between shots, so five new frames span four intervals after any moment before the first of them, less
			// how much longer the first shot took than the last: three intervals is a floor with room for that. A second loop
			// would bring them closer. Waiting for them has a generous timeout only.
			long start = System.nanoTime();
			long s0 = c.seq();
			long got = c.awaitFrame(s0 + 4, 10_000);
			long elapsedMs = (System.nanoTime() - start) / 1_000_000;
			assertTrue(got >= s0 + 5, "the loop keeps taking frames: " + got + " after " + s0);
			assertTrue(elapsedMs >= 3 * 60, "frames come no faster than the interval: 5 frames in " + elapsedMs + " ms");
			// the frame number and the screenshot count change together under the capture's lock, so a count read
			// before and after reading the frame number brackets it exactly
			long before = shots.get();
			long later = c.seq();
			long after = shots.get();
			assertTrue(before <= later && later <= after, "one screenshot per frame: " + before + " <= " + later + " <= " + after);
			long t0 = System.currentTimeMillis();
			c.awaitFrame(c.seq() + 1000, 300);
			assertTrue(System.currentTimeMillis() - t0 >= 250, "a wait for a frame that does not come times out");
		}
		finally
		{
			c.close();
		}
		long t0 = System.currentTimeMillis();
		c.awaitFrame(Long.MAX_VALUE - 1, 2000);
		assertTrue(System.currentTimeMillis() - t0 < 500, "a closed capture does not keep a request waiting");
		long stopped = shots.get();
		Thread.sleep(200);
		assertEquals(stopped, shots.get(), "the loop stops with the capture");
	}

	@Test
	void theSizeComesFromThePngHeader()
	{
		assertArrayEquals(new int[] { 1280, 720 }, WebPageCapture.pngSize(png(1280, 720)));
		assertNull(WebPageCapture.pngSize(new byte[] { 1, 2, 3 }));
	}
}
