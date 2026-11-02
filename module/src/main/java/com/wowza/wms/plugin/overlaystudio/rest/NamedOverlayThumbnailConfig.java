/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.wowza.wms.plugin.overlaystudio.ImageFiles;
import com.wowza.wms.plugin.overlaystudio.OverlayStudioModule;
import com.wowza.wms.plugin.overlaystudio.templates.ImageLibrary;
import com.wowza.wms.plugin.overlaystudio.templates.OverlayStore;
import com.wowza.wms.plugin.overlaystudio.templates.TemplateFiles;
import com.wowza.wms.rest.WMSConfig;

/**
 * A small PNG of a saved overlay (plan 3.8), base64 in JSON, for the Templates picker and overlay cards. ?width= snaps
 * to 160, 320 or 640. Never opens a page capture: a page layer comes from a capture already open, else a gray box
 * with its host. Cached per overlay and width until the overlay file or an image file it uses changes, or after 60 s
 * when it has a page layer; 4 draws a second across the server, cached answers free. A GET, so read-only users use it.
 */
@WMSConfig(restLocation = "/v1/{server}/plugin/overlaystudio/vhosts/{vhostName}/applications/{appName}/overlays/{overlayName}/thumbnail", restVerbs = "GET", xmlFilename = "")
public class NamedOverlayThumbnailConfig extends VHostResourceBase
{
	static final OverlayRender.Gate GATE = new OverlayRender.Gate(4);
	static final long PAGE_STALE_MS = 60_000;
	static final int CACHE_ENTRIES = 256;

	record Cached(String stamp, long at, boolean pages, Map<String, Object> answer)
	{
	}

	static final Map<String, Cached> CACHE = new java.util.LinkedHashMap<>(16, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Cached> e)
		{
			return size() > CACHE_ENTRIES;
		}
	};

	@Override
	protected Object doGet(Map<String, String> path, Map<String, String> query) throws Exception
	{
		String vhost = path.get("vhostName"), app = path.get("appName");
		String name = TemplateFiles.safeName(path.get("overlayName"));
		Integer want = null;
		try
		{
			want = query.get("width") == null ? null : Integer.valueOf(query.get("width").trim());
		}
		catch(NumberFormatException e)
		{
			throw new TemplateFiles.Refused(400, "width must be a number", null, "width");
		}
		return thumbnail(vhost, app, name, OverlayStore.dir(vhostDir(vhost), app), ImageLibrary.contentDir(vhostDir(vhost)), OverlayRender.thumbWidth(want),
				System.currentTimeMillis());
	}

	/** The thumbnail answer, from the cache when nothing it was drawn from changed. */
	static Map<String, Object> thumbnail(String vhost, String app, String name, Path dir, Path content, int width, long now) throws Exception
	{
		Map<String, Object> overlay = OverlayStore.read(dir, name);
		int[] source = OverlayStudioModule.sourceSize(vhost, app, name);
		int cw = source == null ? OverlayRender.DEFAULT_W : source[0], ch = source == null ? OverlayRender.DEFAULT_H : source[1];
		String stamp = stamp(OverlayStore.file(dir, name), overlay, content) + "/" + cw + "x" + ch;
		String key = vhost + "/" + app + "/" + name + "/" + width;
		synchronized(CACHE)
		{
			Cached c = CACHE.get(key);
			if(c != null && c.stamp().equals(stamp) && !(c.pages() && now - c.at() > PAGE_STALE_MS))
			{
				Map<String, Object> m = new LinkedHashMap<>(c.answer());
				m.put("cached", true);
				return m;
			}
		}
		GATE.admit("server", "thumbnail draws");
		OverlayRender.Result r = OverlayRender.draw(overlay, vhost, cw, ch, width, now, now, true);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("name", name);
		m.put("width", r.image().getWidth());
		m.put("height", r.image().getHeight());
		m.put("png", CaptureConfig.toBase64Png(r.image()));
		boolean pages = OverlayStore.kinds(overlay).contains("webPage");
		synchronized(CACHE)
		{
			CACHE.put(key, new Cached(stamp, now, pages, m));
		}
		Map<String, Object> out = new LinkedHashMap<>(m);
		out.put("cached", false);
		return out;
	}

	/** Which versions the thumbnail is drawn from: the overlay file's and each image file's time and size. */
	static String stamp(Path file, Map<String, Object> overlay, Path content) throws Exception
	{
		StringBuilder b = new StringBuilder();
		b.append(Files.getLastModifiedTime(file).toMillis()).append(':').append(Files.size(file));
		for(String f: OverlayStore.imageFiles(overlay))
		{
			ImageFiles.Key k;
			try
			{
				k = ImageFiles.keyOf(content.resolve(ImageLibrary.safeFile(f)));
			}
			catch(TemplateFiles.Refused e)
			{
				k = null;
			}
			b.append('|').append(f).append('=').append(k == null ? "missing" : k.modified() + ":" + k.size());
		}
		return b.toString();
	}

	/** Empties the cache, for tests. */
	static void clearForTest()
	{
		synchronized(CACHE)
		{
			CACHE.clear();
		}
	}
}
