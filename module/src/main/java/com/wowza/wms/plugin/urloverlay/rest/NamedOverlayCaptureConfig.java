/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.rest;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wowza.wms.plugin.urloverlay.WebPageCapture;
import com.wowza.wms.plugin.urloverlay.templates.OverlayStore;
import com.wowza.wms.plugin.urloverlay.templates.TemplateFiles;
import com.wowza.wms.rest.WMSConfig;

/**
 * A capture of one page of a named overlay, for placing it before any stream shows it. Uses the shared browser for
 * that page, opening it if no stream has; a browser opened only for this closes after the pool's grace period once
 * the page stops asking. ?raw=true returns the capture before keying and cropping, at full size. ?width= scales the
 * keyed capture down to that width. A screenshot is reused while younger than 80% of the overlay's capture interval,
 * as streams reuse it, so asking once per interval gets a fresh one each time.
 */
@WMSConfig(restLocation = "/v1/{server}/plugin/urloverlay/vhosts/{vhostName}/applications/{appName}/overlays/{overlayName}/objects/{id}/capture", restVerbs = "GET", xmlFilename = "")
public class NamedOverlayCaptureConfig extends VHostResourceBase
{
	private static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	@Override
	@SuppressWarnings("unchecked")
	protected Object doGet(Map<String, String> path, Map<String, String> query) throws Exception
	{
		Map<String, Object> overlay = OverlayStore.read(NamedOverlayConfig.dir(path), TemplateFiles.safeName(path.get("overlayName")));
		Map<String, Object> found = null;
		for(Object o: (List<Object>)overlay.getOrDefault("objects", List.of()))
		{
			if(o instanceof Map && path.get("id").equals(String.valueOf(((Map<String, Object>)o).get("id"))))
			{
				found = (Map<String, Object>)o;
			}
		}
		if(found == null || !"webPage".equals(found.get("type")))
		{
			throw new TemplateFiles.Refused(404, "No page '" + path.get("id") + "' in overlay " + path.get("overlayName"));
		}
		ObjectConfig obj = JSON.convertValue(found, ObjectConfig.class);
		boolean raw = CaptureConfig.isRaw(query.containsKey("raw") ? "raw=" + query.get("raw") : null);
		WebPageCapture capture = WebPageCapture.POOL.acquire(obj.webPageSettings());
		BufferedImage img;
		Map<String, Object> status;
		try
		{
			capture.usePageDelay(obj::getPageDelay);
			img = capture.captureWaiting(CaptureConfig.maxAgeMs(overlay.get("refreshRate")), CaptureConfig.FIRST_FRAME_WAIT_MS);
			status = capture.status();
		}
		finally
		{
			WebPageCapture.POOL.release(capture);
		}
		if(img != null && !raw)
		{
			// as a stream draws it: keyed, then cropped; ?raw=true is the whole page, for picking the key color and the crop
			img = CaptureConfig.fit(obj.cropped(ObjectConfig.keyOut(img, obj.awt_keyColor(), obj.keyTolerance)), CaptureConfig.width(query.get("width")));
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", path.get("id"));
		m.put("raw", raw);
		m.put("capture", status);
		m.put("width", img == null ? null : img.getWidth());
		m.put("height", img == null ? null : img.getHeight());
		m.put("png", img == null ? null : CaptureConfig.toBase64Png(img));
		return m;
	}
}
