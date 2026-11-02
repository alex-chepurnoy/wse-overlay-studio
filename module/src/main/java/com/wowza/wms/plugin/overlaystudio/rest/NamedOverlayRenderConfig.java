/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wowza.wms.plugin.overlaystudio.OverlayStudioModule;
import com.wowza.wms.plugin.overlaystudio.templates.ImageLibrary;
import com.wowza.wms.plugin.overlaystudio.templates.OverlayStore;
import com.wowza.wms.plugin.overlaystudio.templates.TemplateFiles;
import com.wowza.wms.rest.WMSConfig;

/**
 * The draft render (plan 3.8): POST {"overlay": {...}, "width", "now", "streamStart"} with the unsaved overlay, and
 * the answer is a PNG (base64, as the other image answers are) of its text, shapes and images at width, at most the
 * source width (1920 when no stream shows the overlay), drawn by the stream's own layer code at the frozen now and
 * streamStart (epoch milliseconds; now by default). Page layers are left out and no capture is opened; live values are
 * not applied; nothing is written. Checked as a save is. 4 renders a second per user.
 */
@WMSConfig(restLocation = "/v1/{server}/plugin/overlaystudio/vhosts/{vhostName}/applications/{appName}/overlays/{overlayName}/render", restVerbs = "POST", xmlFilename = "")
public class NamedOverlayRenderConfig extends VHostResourceBase
{
	static final OverlayRender.Gate GATE = new OverlayRender.Gate(4);

	@Override
	@SuppressWarnings("unchecked")
	protected Object doPost(Map<String, String> path, Map<String, Object> body) throws Exception
	{
		String name = TemplateFiles.safeName(path.get("overlayName"));
		if(!(body.get("overlay") instanceof Map))
		{
			throw new TemplateFiles.Refused(400, "Give the overlay to draw as {\"overlay\": {\"objects\": [...]}}", null, "overlay");
		}
		for(String k: new String[] { "width", "now", "streamStart" })
		{
			if(body.get(k) != null && !(body.get(k) instanceof Number))
			{
				throw new TemplateFiles.Refused(400, k + " must be a number", null, k);
			}
		}
		GATE.admit(getUser() == null ? "none" : getUser().getIdentifier(), "draft renders");
		Path content = ImageLibrary.contentDir(vhostDir(path.get("vhostName")));
		Map<String, Object> overlay = OverlayStore.checked((Map<String, Object>)body.get("overlay"), url -> null, f -> NamedOverlayConfig.pixels(content, f));
		int[] source = OverlayStudioModule.sourceSize(path.get("vhostName"), path.get("appName"), name);
		int cw = source == null ? OverlayRender.DEFAULT_W : source[0], ch = source == null ? OverlayRender.DEFAULT_H : source[1];
		long now = body.get("now") instanceof Number n ? n.longValue() : System.currentTimeMillis();
		long start = body.get("streamStart") instanceof Number n ? n.longValue() : now;
		int width = body.get("width") instanceof Number n ? n.intValue() : cw;
		OverlayRender.Result r = OverlayRender.draw(overlay, path.get("vhostName"), cw, ch, width, now, start, false);
		long pages = ((List<Object>)overlay.get("objects")).stream().filter(o -> "webPage".equals(((Map<String, Object>)o).get("type"))).count();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("width", r.image().getWidth());
		m.put("height", r.image().getHeight());
		m.put("sourceWidth", cw);
		m.put("sourceHeight", ch);
		m.put("pagesLeftOut", pages);
		m.put("png", CaptureConfig.toBase64Png(r.image()));
		return m;
	}
}
