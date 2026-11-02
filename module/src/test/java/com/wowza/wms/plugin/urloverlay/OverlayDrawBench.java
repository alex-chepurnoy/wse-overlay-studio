/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.wowza.wms.plugin.urloverlay.rest.ObjectConfig;
import com.wowza.wms.plugin.urloverlay.rest.OverlayConfig;

/**
 * Spike S2 for the 2.0 plan: what one redraw costs through today's draw path (OverlayImage.drawImage: clear, draw the
 * objects with Java2D, convert ABGR to RGBA), and whether the redraws that land on the same second boundary for many
 * streams finish within one frame interval (33 ms at 30 fps). Not a unit test; run as a main class:
 * java -cp <module classes>:<module test classes>:<WSE lib/*> com.wowza.wms.plugin.urloverlay.OverlayDrawBench
 */
public class OverlayDrawBench
{
	static final double[] SCALES = {1.0, 720.0 / 1080, 480.0 / 1080, 360.0 / 1080};

	public static void main(String[] args) throws Exception
	{
		System.out.println("java " + System.getProperty("java.version") + ", " + System.getProperty("os.name") + ", cores="
			+ Runtime.getRuntime().availableProcessors() + ", draw pool would be "
			+ Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() / 2)) + " threads");
		System.out.println("Each case: median / p95 / max ms of one drawImage, at each output scale (1080, 720, 480, 360 lines).");
		System.out.println();

		bench("empty overlay, full 1920x1080 canvas", full(), 1920, 1080);
		bench("clock HH:mm:ss, full canvas", full(clock(1500, 100)), 1920, 1080);
		bench("clock HH:mm:ss, 420x100 bounding box", full(clock(10, 80)), 420, 100);
		bench("live badge (rounded box + text), full canvas", full(badge()), 1920, 1080);
		bench("lower third (bar + two lines), full canvas", full(lowerThird()), 1920, 1080);
		bench("64 layers (32 boxes + 32 strings), full canvas", full(many(64)), 1920, 1080);

		System.out.println();
		System.out.println("Second-boundary burst: N streams each with a full-canvas clock redraw all 4 scales at once,");
		System.out.println("on a pool the size the module uses. Time from the boundary until the last draw finishes.");
		int pool = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
		for(int streams : new int[] {1, 4, 12})
		{
			burst(streams, pool, false);
			burst(streams, pool, true);
		}
	}

	static OverlayConfig full(ObjectConfig... objs)
	{
		OverlayConfig c = new OverlayConfig();
		c.setOpacity(100);
		c.setObjects(new ArrayList<>(Arrays.asList(objs)));
		c.applyDefaults();
		return c;
	}

	static ObjectConfig clock(int x, int y)
	{
		ObjectConfig o = new ObjectConfig();
		o.setType("string");
		o.setX(x);
		o.setY(y);
		o.setText("HH:mm:ss");
		o.setUseDate(true);
		o.setFont("Monospaced");
		o.setFontSize(64);
		o.setColor("white");
		o.setOpacity(100);
		return o;
	}

	static ObjectConfig box(int x, int y, int w, int h, String fill, int arc)
	{
		ObjectConfig o = new ObjectConfig();
		o.setType("rectangle");
		o.setX(x);
		o.setY(y);
		o.setW(w);
		o.setH(h);
		o.setFillColor(fill);
		o.setColor(fill);
		o.setArcWidth(arc);
		o.setArcHeight(arc);
		o.setOpacity(100);
		return o;
	}

	static ObjectConfig text(int x, int y, String t, int size)
	{
		ObjectConfig o = new ObjectConfig();
		o.setType("string");
		o.setX(x);
		o.setY(y);
		o.setText(t);
		o.setFont("SansSerif");
		o.setFontSize(size);
		o.setColor("white");
		o.setOpacity(100);
		return o;
	}

	static ObjectConfig[] badge()
	{
		return new ObjectConfig[] {box(60, 50, 200, 70, "red", 20), text(90, 100, "LIVE", 48)};
	}

	static ObjectConfig[] lowerThird()
	{
		return new ObjectConfig[] {box(100, 810, 1200, 160, "rgb(20,20,20,200)", -1), text(140, 880, "Jane Example", 56),
			text(140, 940, "Director of Something Important", 36)};
	}

	static ObjectConfig[] many(int n)
	{
		List<ObjectConfig> l = new ArrayList<>();
		for(int i = 0; i < n / 2; i++)
		{
			l.add(box(40 + (i % 8) * 230, 40 + (i / 8) * 250, 200, 60, "rgb(0,80,160,180)", 10));
			l.add(text(50 + (i % 8) * 230, 85 + (i / 8) * 250, "Layer " + i, 32));
		}
		return l.toArray(new ObjectConfig[0]);
	}

	static void bench(String name, OverlayConfig c, int w, int h)
	{
		OverlayImage img = new OverlayImage(0, 0, w, h, c, false);
		StringBuilder sb = new StringBuilder(String.format("%-48s", name));
		for(double s : SCALES)
		{
			for(int i = 0; i < 150; i++)
			{
				img.drawImage(c, s); // warm up
			}
			double[] ms = new double[400];
			for(int i = 0; i < ms.length; i++)
			{
				long t = System.nanoTime();
				img.drawImage(c, s);
				ms[i] = (System.nanoTime() - t) / 1e6;
			}
			Arrays.sort(ms);
			sb.append(String.format("  %4d: %5.2f/%5.2f/%5.2f", (int)Math.round(1080 * s), ms[ms.length / 2], ms[(int)(ms.length * 0.95)],
				ms[ms.length - 1]));
		}
		System.out.println(sb);
	}

	static void burst(int streams, int pool, boolean boxOnly) throws Exception
	{
		List<OverlayImage> imgs = new ArrayList<>();
		List<OverlayConfig> cfgs = new ArrayList<>();
		for(int i = 0; i < streams; i++)
		{
			OverlayConfig c = boxOnly ? full(clock(10, 80)) : full(clock(1500, 100));
			cfgs.add(c);
			imgs.add(boxOnly ? new OverlayImage(1490, 20, 420, 100, c, false) : new OverlayImage(0, 0, 1920, 1080, c, false));
		}
		ExecutorService ex = Executors.newFixedThreadPool(pool);
		double[] totals = new double[60];
		for(int round = -20; round < totals.length; round++)
		{
			long t0 = System.nanoTime();
			List<Future<?>> fs = new ArrayList<>();
			for(int i = 0; i < streams; i++)
			{
				OverlayImage img = imgs.get(i);
				OverlayConfig c = cfgs.get(i);
				for(double s : SCALES)
				{
					fs.add(ex.submit(() -> img.drawImage(c, s)));
				}
			}
			for(Future<?> f : fs)
			{
				f.get();
			}
			if(round >= 0)
			{
				totals[round] = (System.nanoTime() - t0) / 1e6;
			}
		}
		ex.shutdown();
		Arrays.sort(totals);
		System.out.printf("  %2d streams x 4 scales = %3d draws, %-16s pool %d: median %6.2f ms, p95 %6.2f ms, max %6.2f ms%n", streams,
			streams * 4, boxOnly ? "bounding box," : "full canvas,", pool, totals[totals.length / 2], totals[(int)(totals.length * 0.95)],
			totals[totals.length - 1]);
	}
}
