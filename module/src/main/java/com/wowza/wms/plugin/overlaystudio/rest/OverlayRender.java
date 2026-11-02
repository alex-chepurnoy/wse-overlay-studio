/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wowza.wms.plugin.overlaystudio.TextTokens;
import com.wowza.wms.plugin.overlaystudio.templates.TemplateFiles;

/**
 * An overlay drawn off air (plan 3.8), by the same layer code a stream uses, at the source's scale: the draft render
 * of an unsaved overlay and the thumbnail of a saved one. Neither opens a page capture. The draft leaves page layers
 * out; the thumbnail draws them from a capture already open, or as a placeholder, and shows a countdown or count-up
 * with no target as the faded sample the canvas shows. Live values are never applied.
 */
public final class OverlayRender
{
	/** The canvas when no stream shows the overlay, so its source size is not known. */
	public static final int DEFAULT_W = 1920, DEFAULT_H = 1080;
	/** The widths a thumbnail snaps to. */
	public static final int[] THUMB_WIDTHS = { 160, 320, 640 };
	static final String NO_TARGET = "No target set";
	private static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	/** What was drawn, and whether a page frame went into it (a thumbnail with one goes stale). */
	public record Result(BufferedImage image, boolean pages)
	{
	}

	private OverlayRender()
	{
	}

	/** The thumbnail width for a requested one: the nearest of THUMB_WIDTHS at or above it, at most the largest. */
	public static int thumbWidth(Integer requested)
	{
		int want = requested == null ? 320 : requested;
		for(int w: THUMB_WIDTHS)
		{
			if(want <= w)
			{
				return w;
			}
		}
		return THUMB_WIDTHS[THUMB_WIDTHS.length - 1];
	}

	/**
	 * Draws overlay (already checked) on a canvasW x canvasH source, scaled to width (capped at canvasW), at the
	 * frozen now and streamStart. vhost finds image files.
	 */
	@SuppressWarnings("unchecked")
	public static Result draw(Map<String, Object> overlay, String vhost, int canvasW, int canvasH, int width, long now, long streamStart, boolean thumbnail)
	{
		Map<String, Object> o = new LinkedHashMap<>(overlay);
		List<Map<String, Object>> layers = new ArrayList<>();
		for(Object x: (List<Object>)overlay.getOrDefault("objects", List.of()))
		{
			Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>)x);
			if("webPage".equals(m.get("type")) && !thumbnail)
			{
				continue;
			}
			if(thumbnail && "string".equals(m.get("type")) && untargetedCounter(m))
			{
				m.put("text", NO_TARGET);
				m.put("endText", null);
				Object op = m.get("opacity");
				m.put("opacity", (int)Math.round((op instanceof Number n && n.doubleValue() >= 0 ? n.doubleValue() : 100) * 0.5));
			}
			layers.add(m);
		}
		o.put("objects", layers);
		OverlayConfig cfg = JSON.convertValue(o, OverlayConfig.class);
		cfg.vhostName = vhost;
		cfg.applyDefaults();
		List<ObjectConfig> objs = new ArrayList<>(cfg.getObjects());
		OverlayConfig.sortObjects(objs);

		int w = Math.max(16, Math.min(width, canvasW));
		double scale = (double)w / canvasW;
		int h = Math.max(1, (int)Math.round(canvasH * scale));
		BufferedImage layer = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = layer.createGraphics();
		// as OverlayImage draws a stream's overlay
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
		boolean pages = false;
		try
		{
			for(ObjectConfig obj: objs)
			{
				obj.bindVHost(vhost);
				if("webPage".equals(obj.getType()))
				{
					pages |= obj.drawPageThumbnail(g, scale);
					continue;
				}
				obj.draw(g, scale, false, obj.timingAlpha(now, streamStart), now, streamStart);
			}
		}
		finally
		{
			g.dispose();
			// lets go of the decoded pictures; the shared cache keeps them for the next draw
			objs.forEach(ObjectConfig::dispose);
		}
		int opacity = cfg.getOpacity();
		if(opacity < 0 || opacity >= 100)
		{
			return new Result(layer, pages);
		}
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D og = out.createGraphics();
		og.setComposite(AlphaComposite.SrcOver.derive(opacity / 100f));
		og.drawImage(layer, 0, 0, null);
		og.dispose();
		return new Result(out, pages);
	}

	/** A text layer whose countdown or count-up has no target to count to. */
	static boolean untargetedCounter(Map<String, Object> m)
	{
		Object t = m.get("target");
		if(!(m.get("text") instanceof String text) || (t instanceof String s && !s.isBlank()))
		{
			return false;
		}
		try
		{
			for(TextTokens.Part p: TextTokens.parse(text, Locale.US))
			{
				if("countdown".equals(p.kind()) || "countup".equals(p.kind()))
				{
					return true;
				}
			}
		}
		catch(IllegalArgumentException e)
		{
			return false;
		}
		return false;
	}

	/** At most perSecond admissions a second for each key (a user, or the whole server); 429 beyond. */
	public static final class Gate
	{
		private final int perSecond;
		private final Map<String, long[]> recent = new java.util.HashMap<>();
		private final Map<String, Integer> next = new java.util.HashMap<>();
		// tests move time
		volatile java.util.function.LongSupplier clock = System::currentTimeMillis;

		public Gate(int perSecond)
		{
			this.perSecond = perSecond;
		}

		public synchronized void admit(String key, String what) throws TemplateFiles.Refused
		{
			long now = clock.getAsLong();
			long[] r = recent.computeIfAbsent(key, k -> {
				long[] a = new long[perSecond];
				java.util.Arrays.fill(a, Long.MIN_VALUE / 2);
				return a;
			});
			int i = next.getOrDefault(key, 0);
			if(now - r[i] < 1000)
			{
				throw new TemplateFiles.Refused(429, "More than " + perSecond + " " + what + " a second; try again shortly");
			}
			r[i] = now;
			next.put(key, (i + 1) % perSecond);
		}
	}
}
