/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import java.util.LinkedHashMap;
import java.util.Map;

import com.wowza.wms.plugin.overlaystudio.OverlayStudioLog;
import com.wowza.wms.plugin.overlaystudio.templates.ImageLibrary;
import com.wowza.wms.rest.WMSConfig;

/**
 * GET lists the images a template's image overlays can use (ImageLibrary). POST {"name", "data"} uploads one, as
 * base64, into content/overlaystudio/; POST {"delete": "overlaystudio/name"} removes an upload (VHostResourceBase hands a
 * DELETE the path only, so the file goes in the body).
 */
@WMSConfig(restLocation = "/v1/{server}/plugin/overlaystudio/vhosts/{vhostName}/images", restVerbs = "GET,POST", xmlFilename = "")
public class ImagesConfig extends VHostResourceBase
{
	@Override
	protected Object doGet(Map<String, String> path, Map<String, String> query) throws Exception
	{
		Map<String, Object> m = new LinkedHashMap<>();
		if(query.get("file") != null)
		{
			// one image whole, for the editor's canvas to draw an image layer with (plan 4.4)
			m.putAll(ImageLibrary.data(vhostDir(path.get("vhostName")), query.get("file")));
			return m;
		}
		m.put("images", ImageLibrary.list(vhostDir(path.get("vhostName"))));
		return m;
	}

	@Override
	protected Object doPost(Map<String, String> path, Map<String, Object> body) throws Exception
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("success", true);
		String delete = str(body, "delete");
		if(delete != null)
		{
			ImageLibrary.delete(vhostDir(path.get("vhostName")), delete);
			OverlayStudioLog.change(this, "deleted image " + delete);
			return m;
		}
		String file = ImageLibrary.save(vhostDir(path.get("vhostName")), str(body, "name"), str(body, "data"));
		OverlayStudioLog.change(this, "uploaded image " + file);
		m.put("file", file);
		return m;
	}
}
