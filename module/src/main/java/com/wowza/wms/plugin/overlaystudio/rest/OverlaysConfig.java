/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wowza.wms.plugin.overlaystudio.OverlayStudioModule;
import com.wowza.wms.plugin.overlaystudio.templates.OverlayStore;
import com.wowza.wms.plugin.overlaystudio.templates.TemplateFiles;
import com.wowza.wms.rest.WMSConfig;

/**
 * The application's named overlays, each with the templates that carry it, the running streams showing it, its layer
 * kinds, the image files it uses and a hash of its content.
 */
@WMSConfig(restLocation = "/v1/{server}/plugin/overlaystudio/vhosts/{vhostName}/applications/{appName}/overlays", restVerbs = "GET", xmlFilename = "")
public class OverlaysConfig extends VHostResourceBase
{
	@Override
	protected Object doGet(Map<String, String> path, Map<String, String> query) throws Exception
	{
		return overlays(path.get("vhostName"), path.get("appName"));
	}

	/** The overlays listing, also part of the application's state (AppStateConfig). */
	@SuppressWarnings("unchecked")
	static Map<String, Object> overlays(String vhost, String app) throws Exception
	{
		Path dir = OverlayStore.dir(vhostDir(vhost), app);
		Path templates = vhostDir(vhost).resolve("transcoder").resolve("templates");
		List<Map<String, Object>> list = new ArrayList<>();
		for(String name: OverlayStore.list(dir))
		{
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", name);
			try
			{
				Map<String, Object> overlay = OverlayStore.read(dir, name);
				List<Object> objs = (List<Object>)overlay.get("objects");
				m.put("pages", objs == null ? 0 : objs.size());
				// for the Templates picker and Copy to other applications (plan 4.5)
				m.put("kinds", OverlayStore.kinds(overlay));
				m.put("imageFiles", OverlayStore.imageFiles(overlay));
				m.put("hash", OverlayStore.hash(overlay));
			}
			catch(Exception e)
			{
				m.put("error", e.getMessage());
			}
			// lets an open editor tell that the file changed elsewhere
			m.put("modified", java.nio.file.Files.getLastModifiedTime(OverlayStore.file(dir, name)).toMillis());
			m.put("templates", TemplateFiles.templatesUsing(templates, name));
			m.put("showingOn", OverlayStudioModule.streamsShowing(vhost, app, name));
			list.add(m);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("overlays", list);
		return out;
	}
}
