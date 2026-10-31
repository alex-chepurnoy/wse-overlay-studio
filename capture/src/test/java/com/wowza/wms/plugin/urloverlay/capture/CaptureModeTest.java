/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** How a page's frames are taken: the probe picks screencast or screenshots, and a screencast is capped and kept current. */
class CaptureModeTest
{
	private long probe, firstFrame;

	@BeforeEach
	void shorten()
	{
		probe = WebPageCapture.PROBE_MS;
		firstFrame = WebPageCapture.CAST_FIRST_FRAME_MS;
		// long enough that a test's frames land inside the probe however slow the machine
		WebPageCapture.PROBE_MS = 1000;
		WebPageCapture.CAST_FIRST_FRAME_MS = 1500;
	}

	@AfterEach
	void restore()
	{
		WebPageCapture.PROBE_MS = probe;
		WebPageCapture.CAST_FIRST_FRAME_MS = firstFrame;
	}

	static void waitFor(BooleanSupplier ok, long ms, String what) throws InterruptedException
	{
		long end = System.currentTimeMillis() + ms;
		while(!ok.getAsBoolean())
		{
			assertTrue(System.currentTimeMillis() < end, "timed out waiting: " + what);
			Thread.sleep(10);
		}
	}

	@Test
	void aQuietPageIsCapturedByScreencastCappedAtTheInterval() throws Exception
	{
		byte[] png = BackgroundLoopTest.png(320, 180);
		FakePage page = new FakePage(png);
		WebPageCapture c = new WebPageCapture("http://example.com/", 320, 180, () -> 0, null);
		try
		{
			c.attachForTest(page);
			c.interval(200);
			c.startLoop();
			waitFor(() -> page.casts.get() == 1, 3000, "the probe starts a screencast");
			// the frame a new screencast sends at once is not a repaint
			page.frame(png);
			waitFor(() -> "screencast".equals(c.quickStatus().get("captureMode")), 3000, "screencast picked");
			assertEquals(1, page.acks.size(), "every frame is acknowledged at once while probing");
			assertEquals(0, page.shots.get() > 1 ? 1 : 0, "no screenshots once the screencast runs: " + page.shots.get());
			int shotsThen = page.shots.get();

			// a burst of repaints: frames are kept, but acknowledged once per interval, for the latest
			long seq0 = c.seq();
			for(int i = 0; i < 5; i++)
			{
				page.frame(png);
			}
			assertEquals(seq0 + 5, c.seq(), "every frame Chrome sends is kept");
			waitFor(() -> page.acks.size() >= 2, 1000, "the capped acknowledgment");
			Thread.sleep(400);
			assertEquals(2, page.acks.size(), "one acknowledgment for the burst, not five: " + page.acks);
			assertEquals(6, (int)page.acks.get(1), "for the latest frame");

			// nothing changes on the page: the frame stays current
			Instant before = c.lastCaptureAt();
			Thread.sleep(500);
			assertTrue(c.lastCaptureAt().isAfter(before), "lastCaptureAt moves on while the screencast runs");
			assertEquals(shotsThen, page.shots.get(), "still no screenshots");
			assertEquals(-1L, c.captureMs(), "a screencast frame has no screenshot time");
		}
		finally
		{
			c.close();
		}
	}

	@Test
	void aBusyPageIsCapturedByScreenshots() throws Exception
	{
		byte[] png = BackgroundLoopTest.png(320, 180);
		FakePage page = new FakePage(png);
		WebPageCapture c = new WebPageCapture("http://example.com/", 320, 180, () -> 0, null);
		try
		{
			c.attachForTest(page);
			c.interval(1000);
			c.startLoop();
			waitFor(() -> page.casts.get() == 1, 3000, "the probe starts a screencast");
			// the first frame and then 40 repaints in a 1 s probe: far over 1.5 per 1000 ms interval
			for(int i = 0; i < 41; i++)
			{
				page.frame(png);
			}
			waitFor(() -> "screenshots".equals(c.quickStatus().get("captureMode")), 3000, "screenshots picked");
			assertEquals(1, page.stops.get(), "the screencast is stopped");
			// 40 repaints over the time the probe really ran, a little over its 1 s
			double rate = ((Number)c.quickStatus().get("repaintsPerSecond")).doubleValue();
			assertTrue(rate > 30 && rate <= 40, String.valueOf(c.quickStatus()));
			assertTrue(c.seq() <= 3, "while probing at most one frame per interval is passed on, and the latest at its end: " + c.seq());
			int shots = page.shots.get();
			waitFor(() -> page.shots.get() > shots, 3000, "screenshots are taken again");
		}
		finally
		{
			c.close();
		}
	}

	@Test
	void aScreencastThatSendsNothingFallsBackToScreenshots() throws Exception
	{
		FakePage page = new FakePage(BackgroundLoopTest.png(320, 180));
		WebPageCapture c = new WebPageCapture("http://example.com/", 320, 180, () -> 0, null);
		try
		{
			c.attachForTest(page);
			c.interval(100);
			c.startLoop();
			waitFor(() -> page.casts.get() == 1, 3000, "the probe starts a screencast");
			// no frame at all: rate 0 picks screencast, and the screencast never sends one
			waitFor(() -> "screencast".equals(c.quickStatus().get("captureMode")), 3000, "screencast picked");
			waitFor(() -> "screenshots".equals(c.quickStatus().get("captureMode")), 3000, "fell back");
			int shots = page.shots.get();
			waitFor(() -> page.shots.get() > shots + 2, 3000, "screenshots are taken");
		}
		finally
		{
			c.close();
		}
	}

	@Test
	void aStillPageStaysOnScreencastWhenProbedAgain() throws Exception
	{
		byte[] png = BackgroundLoopTest.png(320, 180);
		FakePage page = new FakePage(png);
		WebPageCapture c = new WebPageCapture("http://example.com/", 320, 180, () -> 0, null);
		try
		{
			c.attachForTest(page);
			c.interval(200);
			c.startLoop();
			waitFor(() -> page.casts.get() == 1, 3000, "the probe starts a screencast");
			page.frame(png);
			waitFor(() -> "screencast".equals(c.quickStatus().get("captureMode")), 3000, "screencast picked");
			// an interval change probes again; the running screencast sends nothing, since the page does not change
			c.interval(250);
			waitFor(() -> "probing".equals(c.quickStatus().get("captureMode")), 3000, "probed again");
			waitFor(() -> "screencast".equals(c.quickStatus().get("captureMode")), 3000, "screencast again");
			Thread.sleep(WebPageCapture.CAST_FIRST_FRAME_MS + 300);
			assertEquals("screencast", c.quickStatus().get("captureMode"), "no fall back to screenshots for a page that does not change");
			assertEquals(1, page.casts.get(), "the screencast was not started again");
		}
		finally
		{
			c.close();
		}
	}

	@Test
	void thePagesLastPictureDuringAProbeIsKept() throws Exception
	{
		byte[] first = BackgroundLoopTest.png(320, 180), settled = BackgroundLoopTest.png(320, 181);
		FakePage page = new FakePage(first);
		WebPageCapture c = new WebPageCapture("http://example.com/", 320, 180, () -> 0, null);
		try
		{
			c.attachForTest(page);
			c.interval(1000);
			c.startLoop();
			waitFor(() -> page.casts.get() == 1, 3000, "the probe starts a screencast");
			page.frame(first);
			// the page settles 50 ms later, inside the interval, and then never repaints again
			Thread.sleep(50);
			page.frame(settled);
			waitFor(() -> "screencast".equals(c.quickStatus().get("captureMode")), 3000, "screencast picked");
			org.junit.jupiter.api.Assertions.assertArrayEquals(settled, c.lastPng(), "the settled picture is the one kept, not the first");
		}
		finally
		{
			c.close();
		}
	}

	@Test
	void aHungPageIsClosedAndReopened() throws Exception
	{
		FakePage page = new FakePage(BackgroundLoopTest.png(320, 180));
		WebPageCapture c = new WebPageCapture("http://example.com/", 320, 180, () -> 0, null);
		try
		{
			c.attachForScreenshots(page);
			page.hung = true;
			c.startLoop();
			waitFor(() -> page.closes.get() == 1, 5000, "the hung page is closed");
			assertTrue(String.valueOf(c.status().get("lastError")).contains("did not answer"), String.valueOf(c.status().get("lastError")));
		}
		finally
		{
			c.close();
		}
	}

	@Test
	void aCrashedPageIsClosedAndReopenedLater() throws Exception
	{
		FakePage page = new FakePage(BackgroundLoopTest.png(320, 180));
		WebPageCapture c = new WebPageCapture("http://example.com/", 320, 180, () -> 0, null);
		try
		{
			c.attachForTest(page);
			page.crash();
			waitFor(() -> page.closes.get() == 1, 3000, "the crashed page is closed");
			assertEquals("error", c.status().get("state"));
			assertTrue(String.valueOf(c.status().get("lastError")).contains("crashed"), String.valueOf(c.status().get("lastError")));
			assertTrue(((Number)c.status().get("retryInMs")).longValue() <= 1000, "opened again after the first backoff");
			page.crash();
			Thread.sleep(100);
			assertEquals(1, page.closes.get(), "a page already replaced is not failed twice");
		}
		finally
		{
			c.close();
		}
	}

	@Test
	void aPageThatLeftItsAddressIsLoadedAgain() throws Exception
	{
		FakePage page = new FakePage(BackgroundLoopTest.png(320, 180));
		WebPageCapture c = new WebPageCapture("http://example.com/", 320, 180, () -> 0, null);
		try
		{
			c.attachForTest(page);
			page.address = "chrome-error://chromewebdata/";
			c.startLoop();
			waitFor(() -> page.address.equals("http://example.com/"), 15_000, "navigated back to its address");
		}
		finally
		{
			c.close();
		}
	}
}
