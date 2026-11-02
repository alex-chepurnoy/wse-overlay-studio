/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wowza.wms.plugin.overlaystudio.rest.ObjectConfig;
import com.wowza.wms.plugin.overlaystudio.rest.OverlayConfig;

/** D1, D13 and D14 from docs/12-v2-plan.md: when an overlay redraws, per output, and at which scale. */
class SchedulerTest
{
	static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	static ObjectConfig dateText(String pattern)
	{
		ObjectConfig o = JSON.convertValue(Map.of("id", "clock", "type", "string", "text", pattern, "useDate", true, "x", 0, "y", 20), ObjectConfig.class);
		o.applyDefaults();
		return o;
	}

	static OverlayImage image(ObjectConfig... objects)
	{
		OverlayConfig config = new OverlayConfig();
		config.setOpacity(100);
		for(ObjectConfig o: objects)
		{
			config.addObject(o);
		}
		return new OverlayImage(0, 0, 64, 32, config, true);
	}

	// D1: a clock with no web page on the overlay redraws every second, with no save and no refresh rate
	@Test
	void aClockWithNoPageTicksEverySecond() throws Exception
	{
		OverlayImage img = image(dateText("HH:mm:ss"));
		img.start();
		try
		{
			Set<UUID> seen = new HashSet<>();
			long end = System.currentTimeMillis() + 2600;
			while(System.currentTimeMillis() < end)
			{
				seen.add(img.getCurrentImageUuid());
				Thread.sleep(20);
			}
			assertTrue(seen.size() >= 3, "a new content id at least at two second boundaries, saw " + seen.size());
		}
		finally
		{
			img.stop();
		}
	}

	@Test
	void eachPatternWakesAtItsSmallestUnitAndAStaticOverlayNever()
	{
		long now = 1_767_225_600_000L + 12_345;
		assertEquals(1_767_225_613_000L, ObjectConfigAccess.boundary("HH:mm:ss", now), "the next second");
		assertEquals(1_767_225_660_000L, ObjectConfigAccess.boundary("HH:mm", now) - zoneMinuteShift(now), "the next minute");
		assertEquals(now - now % 100 + 100, ObjectConfigAccess.boundary("ss.SSS", now), "tenths at the finest");
		assertEquals(ObjectConfigAccess.boundary("HH 'o''clock'", now), ObjectConfigAccess.boundary("HH", now), "quoted letters do not count");

		OverlayImage still = image(JSON.convertValue(Map.of("id", "box", "type", "rectangle", "w", 10, "h", 10), ObjectConfig.class));
		assertEquals(Long.MAX_VALUE, still.nextWakeAt(now), "nothing changes by itself");
	}

	// zones whose offset is not whole minutes shift minute boundaries; none of the usual ones do, so this is 0
	private static long zoneMinuteShift(long now)
	{
		int offsetSeconds = java.time.ZoneId.systemDefault().getRules().getOffset(java.time.Instant.ofEpochMilli(now)).getTotalSeconds();
		return Math.floorMod(-offsetSeconds, 60) * 1000L;
	}

	@Test
	void redrawsAreCappedAtTenASecond()
	{
		OverlayImage img = image(dateText("ss.SSS"));
		long now = 1_767_225_600_000L;
		img.nextWakeAt(now);
		img.wakeUp(now + 100);
		assertTrue(img.nextWakeAt(now + 101) >= now + 200, "never sooner than 100 ms after the last change");
	}

	@Test
	void aShowWindowWakesAtItsStartAndEnd()
	{
		java.time.Instant start = java.time.Instant.parse("2027-01-01T10:00:00Z"), stop = java.time.Instant.parse("2027-01-01T10:05:00Z");
		ObjectConfig box = JSON.convertValue(Map.of("id", "box", "type", "rectangle", "w", 10, "h", 10,
				"showFrom", "2027-01-01T10:00:00Z", "hideAt", "2027-01-01T10:05:00Z"), ObjectConfig.class);
		OverlayImage img = image(box);
		assertEquals(start.toEpochMilli(), img.nextWakeAt(start.toEpochMilli() - 60_000));
		assertEquals(stop.toEpochMilli(), img.nextWakeAt(start.toEpochMilli() + 1));
		assertEquals(Long.MAX_VALUE, img.nextWakeAt(stop.toEpochMilli() + 1));
	}

	/** A layer whose draw at scale 1.0 waits until released; other scales draw at once. */
	static final class SlowAtFullScale extends ObjectConfig
	{
		final CountDownLatch release = new CountDownLatch(1);

		@Override
		public void draw(Graphics2D g, double scaled, boolean evenCoords, double timing)
		{
			if(scaled == 1.0)
			{
				try
				{
					release.await(10, TimeUnit.SECONDS);
				}
				catch(InterruptedException e)
				{
					Thread.currentThread().interrupt();
				}
			}
		}
	}

	// D13: one output's slow draw does not hold up another's
	@Test
	void aSlowDrawAtOneScaleDoesNotDelayAnother() throws Exception
	{
		SlowAtFullScale slow = new SlowAtFullScale();
		slow.setId("slow");
		slow.setType("rectangle");
		OverlayImage img = image(slow);
		try
		{
			img.current(1.0);
			OverlayImage.Published half = null;
			long end = System.currentTimeMillis() + 3000;
			while(half == null && System.currentTimeMillis() < end)
			{
				half = img.current(0.5);
				Thread.sleep(10);
			}
			assertNotNull(half, "the 0.5 output was drawn while the 1.0 draw was still running");
		}
		finally
		{
			slow.release.countDown();
		}
		OverlayImageTest.awaitIdle(img);
	}

	// D14: an encode scales by the source, whatever size the overlay or a save gives it
	@Test
	void encodesScaleBySourceAndAnOverlayKeepsItsOwnCanvas()
	{
		assertEquals(0.5, OverlayStudioModule.encodeScale(640, 360, false, 1280, 720));
		assertEquals(0.5, OverlayStudioModule.encodeScale(640, 0, false, 1280, 720), "by width when the height is not set");
		assertEquals(1.0, OverlayStudioModule.encodeScale(640, 360, true, 1280, 720), "match source");
		assertEquals(1.0, OverlayStudioModule.encodeScale(640, 360, false, 0, 0), "source not known yet");
		assertEquals(640, OverlayStudioModule.canvasSize(640, 1280), "an overlay with its own size keeps it");
		assertEquals(1280, OverlayStudioModule.canvasSize(-1, 1280), "one without takes the source's");
	}

	// static overlays never ask for a redraw, and the scheduler is quiet for them
	@Test
	void aStaticOverlayIsNeverRedrawnByTheScheduler() throws Exception
	{
		OverlayImage img = image(JSON.convertValue(Map.of("id", "box", "type", "rectangle", "w", 10, "h", 10), ObjectConfig.class));
		UUID before = img.getCurrentImageUuid();
		img.start();
		Thread.sleep(400);
		img.stop();
		assertEquals(before, img.getCurrentImageUuid());
		assertFalse(img.getOverlayConfig().hasNewFrame());
		assertNotEquals(null, before);
	}
}
