/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wowza.wms.plugin.overlaystudio.CaptureClient;
import com.wowza.wms.plugin.overlaystudio.WebPageCapture;
import com.wowza.wms.plugin.overlaystudio.templates.TemplateFiles;
import com.wowza.wms.rest.WMSConfig;

/**
 * A capture of a page as the editor has it now, saved or not, so the canvas shows a draft before Save. Body:
 * { "page": { ... } }, a webPage object as an overlay holds it. Writes nothing.
 * Goes through the shared pool, so a draft matching a saved page reuses its browser and every draft counts against the
 * capture service's browser limit; the same URL rules apply as on save. Returns the capture keyed and cropped, scaled to
 * "width" when given, and with "raw": true also the whole page at full size, for picking the key color and the crop.
 * "refreshRate" is the overlay's capture interval as drafted; a screenshot younger than 80% of it is reused.
 */
@WMSConfig(restLocation = "/v1/{server}/plugin/overlaystudio/vhosts/{vhostName}/applications/{appName}/overlays/{overlayName}/preview", restVerbs = "POST", xmlFilename = "")
public class NamedOverlayPreviewConfig extends VHostResourceBase
{
	private static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	@Override
	@SuppressWarnings("unchecked")
	protected Object doPost(Map<String, String> path, Map<String, Object> body) throws Exception
	{
		TemplateFiles.safeName(path.get("overlayName"));
		Object p = body.get("page");
		if(!(p instanceof Map) || !"webPage".equals(((Map<String, Object>)p).get("type")))
		{
			throw new TemplateFiles.Refused(400, "Give the page to preview as { \"page\": { \"type\": \"webPage\", ... } }");
		}
		Map<String, Object> page = (Map<String, Object>)p;
		String problem = ObjectConfig.validateUrl(page.get("url") == null ? null : String.valueOf(page.get("url")), CaptureClient.get().rules());
		if(problem != null)
		{
			throw new TemplateFiles.Refused(400, problem);
		}
		ObjectConfig obj = JSON.convertValue(page, ObjectConfig.class);
		WebPageCapture capture = WebPageCapture.POOL.acquire(obj.webPageSettings());
		BufferedImage img;
		Map<String, Object> status;
		try
		{
			capture.usePageDelay(obj::getPageDelay);
			img = capture.captureWaiting(CaptureConfig.maxAgeMs(body.get("refreshRate")), CaptureConfig.FIRST_FRAME_WAIT_MS);
			status = capture.status();
		}
		finally
		{
			WebPageCapture.POOL.release(capture);
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", page.get("id"));
		m.put("capture", status);
		m.put("width", img == null ? null : img.getWidth());
		m.put("height", img == null ? null : img.getHeight());
		if(Boolean.TRUE.equals(body.get("raw")))
		{
			m.put("rawPng", img == null ? null : CaptureConfig.toBase64Png(img));
		}
		// png as a stream would draw it, keyed and cropped, at the size the page shows it
		m.put("png", img == null ? null : CaptureConfig.toBase64Png(CaptureConfig.fit(obj.cropped(ObjectConfig.keyOut(img, obj.awt_keyColor(), obj.keyTolerance)), CaptureConfig.width(body.get("width")))));
		return m;
	}
}
