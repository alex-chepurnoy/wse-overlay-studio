/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The snapshot cache decodes a key frame once, never runs more decodes than its cap, gives up waiting after its
 * timeout without losing the decode, and reports a failed decode.
 */
class SnapshotCacheTest
{
	private final ExecutorService pool = Executors.newCachedThreadPool();
	private final AtomicInteger decodes = new AtomicInteger();

	@AfterEach
	void stop()
	{
		pool.shutdownNow();
	}

	private SnapshotCache.Decode shot(long tc, long sleepMs)
	{
		return () -> {
			decodes.incrementAndGet();
			Thread.sleep(sleepMs);
			return new SnapshotCache.Shot(tc, 640, 360, new byte[] { 1, 2, 3 }, sleepMs, sleepMs, 1, System.currentTimeMillis());
		};
	}

	@Test
	void theSameKeyFrameDecodesOnce()
	{
		SnapshotCache c = new SnapshotCache(2, 2000, pool);
		Map<String, Object> first = c.get("cam1_360p/640", 1000, shot(1000, 0));
		assertEquals(false, first.get("cached"));
		assertNotNull(first.get("shot"));
		Map<String, Object> again = c.get("cam1_360p/640", 1000, shot(1000, 0));
		assertEquals(true, again.get("cached"));
		assertEquals(1, decodes.get());
		c.get("cam1_360p/640", 3000, shot(3000, 0));
		assertEquals(2, decodes.get(), "a new key frame decodes");
		c.get("cam1_360p/320", 3000, shot(3000, 0));
		assertEquals(3, decodes.get(), "another width is another picture");
	}

	@Test
	void decodesNeverExceedTheCap() throws Exception
	{
		SnapshotCache c = new SnapshotCache(2, 5000, pool);
		ExecutorService callers = Executors.newFixedThreadPool(8);
		try
		{
			List<Future<Map<String, Object>>> fs = new ArrayList<>();
			for(int i = 0; i < 8; i++)
			{
				String key = "out" + i;
				fs.add(callers.submit(() -> c.get(key, 1, shot(1, 100))));
			}
			for(Future<Map<String, Object>> f: fs)
			{
				assertNotNull(f.get(10, TimeUnit.SECONDS).get("shot"));
			}
		}
		finally
		{
			callers.shutdownNow();
		}
		assertEquals(8, decodes.get());
		assertEquals(2, c.peak(), "at most the cap at once, and the cap was reached");
		assertEquals(0, c.decoding());
	}

	@Test
	void callersOfTheSameKeyFrameShareOneDecode() throws Exception
	{
		SnapshotCache c = new SnapshotCache(1, 5000, pool);
		ExecutorService callers = Executors.newFixedThreadPool(4);
		try
		{
			List<Future<Map<String, Object>>> fs = new ArrayList<>();
			for(int i = 0; i < 4; i++)
			{
				fs.add(callers.submit(() -> c.get("cam1_360p/640", 7, shot(7, 200))));
			}
			for(Future<Map<String, Object>> f: fs)
			{
				assertNotNull(f.get(10, TimeUnit.SECONDS).get("shot"));
			}
		}
		finally
		{
			callers.shutdownNow();
		}
		assertEquals(1, decodes.get());
	}

	@Test
	void aSlowDecodeTimesOutButStillFillsTheCache() throws Exception
	{
		SnapshotCache c = new SnapshotCache(1, 150, pool);
		CountDownLatch done = new CountDownLatch(1);
		Map<String, Object> m = c.get("cam1_360p/640", 5, () -> {
			Thread.sleep(400);
			done.countDown();
			return new SnapshotCache.Shot(5, 640, 360, new byte[] { 9 }, 400, 400, 1, System.currentTimeMillis());
		});
		assertEquals(true, m.get("timedOut"));
		assertFalse(m.containsKey("shot"));
		// while it runs, the only permit is held, so another output is told the server is busy
		Map<String, Object> busy = c.get("cam2_360p/640", 5, shot(5, 0));
		assertEquals(true, busy.get("busy"));
		assertTrue(done.await(5, TimeUnit.SECONDS));
		Thread.sleep(50);
		assertEquals(true, c.get("cam1_360p/640", 5, shot(5, 0)).get("cached"), "the late picture was kept");
	}

	@Test
	void aFailedDecodeIsReportedAndFreesItsPermit()
	{
		SnapshotCache c = new SnapshotCache(1, 2000, pool);
		Map<String, Object> m = c.get("cam1_360p/640", 5, () -> {
			throw new IllegalStateException("The thumbnailer could not decode the key frame");
		});
		assertEquals("The thumbnailer could not decode the key frame", m.get("error"));
		assertNotNull(c.get("cam1_360p/640", 5, shot(5, 0)).get("shot"), "the permit came back");
	}

	@Test
	void widthIsClampedAndHeightFollowsTheOutput() throws Exception
	{
		assertEquals(640, SnapshotConfig.width(null));
		assertEquals(320, SnapshotConfig.width("100"));
		assertEquals(960, SnapshotConfig.width("4000"));
		assertEquals(360, SnapshotConfig.height(640, null), "16:9 when the output size is not known");
		assertTrue(SnapshotConfig.height(641, null) % 2 == 0);
	}

	@Test
	void aPictureBecomesAJpeg() throws Exception
	{
		BufferedImage img = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
		byte[] j = SnapshotConfig.jpeg(img);
		assertTrue(j.length > 100 && (j[0] & 0xff) == 0xFF && (j[1] & 0xff) == 0xD8, "starts with the JPEG marker");
	}
}
