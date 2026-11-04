/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wowza.wms.plugin.overlaystudio.rest.ObjectConfig;
import com.wowza.wms.plugin.overlaystudio.rest.OverlayConfig;

/** D11 and D12: shared caches across draw threads, and edits while a draw runs. */
class ThreadSafetyTest
{
	static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	static ObjectConfig rect(String id, int x, int y)
	{
		ObjectConfig o = JSON.convertValue(Map.of("id", id, "type", "rectangle", "x", x, "y", y, "w", 10, "h", 10, "fillColor", "red", "color", "red", "stroke", 1), ObjectConfig.class);
		o.applyDefaults();
		return o;
	}

	static int cacheSize(String field) throws Exception
	{
		Field f = ObjectConfig.class.getDeclaredField(field);
		f.setAccessible(true);
		return ((Map<?, ?>)f.get(null)).size();
	}

	// D11: many threads, many distinct colors and strokes: no failure, and the caches stay bounded
	@Test
	void stylesCachesAreSafeAcrossThreadsAndBounded() throws Exception
	{
		ObjectConfig o = rect("a", 0, 0);
		AtomicReference<Throwable> failed = new AtomicReference<>();
		List<Thread> threads = new ArrayList<>();
		for(int t = 0; t < 8; t++)
		{
			int base = t;
			Thread th = new Thread(() -> {
				try
				{
					for(int i = 0; i < 2000; i++)
					{
						o.awt_color("rgb(" + base + "," + (i % 256) + "," + (i / 256) + ")");
					}
				}
				catch(Throwable e)
				{
					failed.compareAndSet(null, e);
				}
			});
			threads.add(th);
			th.start();
		}
		for(Thread th: threads)
		{
			th.join();
		}
		assertEquals(null, failed.get());
		assertTrue(cacheSize("colorCache") <= 256, "color cache bounded: " + cacheSize("colorCache"));
	}

	// D11: a gradient holds its own start and end points, so it is built per draw and never cached by position
	@Test
	void noGradientCacheAndEachShapeGetsItsOwnGradient() throws Exception
	{
		assertTrue(java.util.Arrays.stream(ObjectConfig.class.getDeclaredFields()).noneMatch(f -> f.getName().equals("gradientPaintCache")));
		BufferedImage img = new BufferedImage(60, 40, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		for(int x: new int[] { 0, 30 })
		{
			ObjectConfig o = JSON.convertValue(Map.ofEntries(Map.entry("id", "g" + x), Map.entry("type", "rectangle"), Map.entry("x", x), Map.entry("y", 0),
					Map.entry("w", 20), Map.entry("h", 40), Map.entry("fillColor", "rgb(255,0,0)"), Map.entry("gradientColor", "rgb(0,0,255)"),
					Map.entry("gradientDirection", "down"), Map.entry("color", "rgb(255,0,0)"), Map.entry("stroke", 0)), ObjectConfig.class);
			o.applyDefaults();
			o.draw(g, 1.0, false);
		}
		g.dispose();
		for(int x: new int[] { 10, 40 })
		{
			int top = img.getRGB(x, 2), bottom = img.getRGB(x, 37);
			assertTrue(((top >> 16) & 0xff) > ((bottom >> 16) & 0xff), "red at the top of the shape at " + x);
			assertTrue((bottom & 0xff) > (top & 0xff), "blue at the bottom of the shape at " + x);
		}
	}

	// D12: objects added and removed while draws run never break a draw
	@Test
	void editsWhileDrawingNeverBreakADraw() throws Exception
	{
		OverlayConfig config = new OverlayConfig();
		config.setOpacity(100);
		// enough layers that a draw is still walking the list when an edit lands
		for(int i = 0; i < 300; i++)
		{
			config.addObject(rect("base" + i, i % 50, (i * 3) % 50));
		}
		OverlayImage img = new OverlayImage(0, 0, 64, 64, config, false);
		AtomicReference<Throwable> failed = new AtomicReference<>();
		AtomicBoolean stop = new AtomicBoolean();
		Thread draws = new Thread(() -> {
			try
			{
				while(!stop.get())
				{
					img.drawImage(config, 1.0);
				}
			}
			catch(Throwable e)
			{
				failed.compareAndSet(null, e);
			}
		});
		draws.start();
		long end = System.currentTimeMillis() + 1500;
		int i = 0;
		while(System.currentTimeMillis() < end && failed.get() == null)
		{
			config.addObject(rect("r" + (i % 20), i % 50, (i * 7) % 50));
			config.deleteObject("r" + ((i + 10) % 20));
			i++;
		}
		stop.set(true);
		draws.join();
		assertEquals(null, failed.get(), "a draw failed during edits");
	}

	/** A layer whose draw waits until released, and which records being disposed. */
	static final class HeldRect extends ObjectConfig
	{
		final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		volatile boolean disposedWhileDrawing, drawing, disposed;

		@Override
		public void draw(Graphics2D g, double scaled, boolean evenCoords, double timing, long now, long streamStart)
		{
			drawing = true;
			entered.countDown();
			try
			{
				release.await(10, TimeUnit.SECONDS);
			}
			catch(InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
			drawing = false;
		}

		@Override
		public void dispose()
		{
			disposedWhileDrawing |= drawing;
			disposed = true;
			super.dispose();
		}
	}

	// D12: replacing a layer while a draw is using it neither waits for that draw nor disposes the layer under it
	@Test
	void aReplacementDuringADrawDoesNotWaitAndDisposesTheOldLayerAfterIt() throws Exception
	{
		OverlayConfig config = new OverlayConfig();
		config.setOpacity(100);
		HeldRect held = new HeldRect();
		held.setId("layer");
		held.setType("rectangle");
		config.addObject(held);
		OverlayImage img = new OverlayImage(0, 0, 32, 32, config, false);
		Thread draw = new Thread(() -> img.drawImage(config, 1.0));
		draw.start();
		assertTrue(held.entered.await(5, TimeUnit.SECONDS), "the draw reached the layer");

		OverlayConfig update = new OverlayConfig();
		update.addObject(rect("layer", 4, 4));
		Thread save = new Thread(() -> img.mergeOverlayConfig(update));
		save.start();
		save.join(1000);
		boolean waited = save.isAlive();
		held.release.countDown();
		draw.join();
		save.join();
		assertFalse(waited, "the replacement waited for the running draw");
		assertFalse(held.disposedWhileDrawing, "the old layer was disposed while a draw used it");
		assertTrue(held.disposed, "the old layer is disposed once the draw ends");
	}
}
