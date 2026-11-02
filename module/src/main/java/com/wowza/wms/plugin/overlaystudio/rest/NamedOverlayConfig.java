/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wowza.wms.plugin.overlaystudio.CaptureClient;
import com.wowza.wms.plugin.overlaystudio.OverlayStudioLog;
import com.wowza.wms.plugin.overlaystudio.OverlayStudioModule;
import com.wowza.wms.plugin.overlaystudio.templates.OverlayStore;
import com.wowza.wms.plugin.overlaystudio.templates.TemplateFiles;
import com.wowza.wms.rest.WMSConfig;

/**
 * One named overlay. GET reads it; POST saves it (creating it if new) and applies it at once to every running stream
 * whose template names it; DELETE removes it, refused while a template still names it.
 */
@WMSConfig(restLocation = "/v1/{server}/plugin/overlaystudio/vhosts/{vhostName}/applications/{appName}/overlays/{overlayName}", restVerbs = "GET,POST,DELETE", xmlFilename = "")
public class NamedOverlayConfig extends VHostResourceBase
{
	static Path dir(Map<String, String> path)
	{
		return OverlayStore.dir(vhostDir(path.get("vhostName")), path.get("appName"));
	}

	@Override
	protected Object doGet(Map<String, String> path, Map<String, String> query) throws Exception
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("name", TemplateFiles.safeName(path.get("overlayName")));
		m.putAll(OverlayStore.read(dir(path), path.get("overlayName")));
		return m;
	}

	@Override
	protected Object doPost(Map<String, String> path, Map<String, Object> body) throws Exception
	{
		String name = TemplateFiles.safeName(path.get("overlayName"));
		Map<String, Object> saved = OverlayStore.write(dir(path), name, body, url -> ObjectConfig.validateUrl(url, CaptureClient.get().rules()));
		List<String> updated = OverlayStudioModule.reapplyNamedOverlay(path.get("vhostName"), path.get("appName"), name);
		OverlayStudioLog.change(this, "saved overlay " + name + " in " + path.get("vhostName") + "/" + path.get("appName")
				+ (updated.isEmpty() ? "" : ", applied to running streams " + updated));
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("success", true);
		m.put("name", name);
		m.putAll(saved);
		m.put("updatedStreams", updated);
		return m;
	}

	@Override
	protected Object doDelete(Map<String, String> path) throws Exception
	{
		String name = TemplateFiles.safeName(path.get("overlayName"));
		List<String> using = TemplateFiles.templatesUsing(vhostDir(path.get("vhostName")).resolve("transcoder").resolve("templates"), name);
		if(!using.isEmpty())
		{
			throw new TemplateFiles.Refused(409, "Templates still carry overlay " + name + ": " + String.join(", ", using) + ". Take it off them first.");
		}
		OverlayStore.delete(dir(path), name);
		OverlayStudioLog.change(this, "deleted overlay " + name + " in " + path.get("vhostName") + "/" + path.get("appName"));
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("success", true);
		m.put("name", name);
		return m;
	}
}
