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

import com.wowza.wms.plugin.overlaystudio.OverlayStudioLog;
import com.wowza.wms.plugin.overlaystudio.OverlayStudioModule;
import com.wowza.wms.plugin.overlaystudio.templates.TemplateFiles;
import com.wowza.wms.rest.WMSConfig;

/**
 * The streams that have their own copy of this template (made by assign). GET lists them; POST {"streams": [...]}
 * replaces those copies with the template as it is now. Copies are never updated without being asked.
 */
@WMSConfig(restLocation = "/v1/{server}/plugin/overlaystudio/vhosts/{vhostName}/templates/{templateName}/copies", restVerbs = "GET,POST", xmlFilename = "")
public class TemplateCopiesConfig extends VHostResourceBase
{
	private static Path dir(Map<String, String> path)
	{
		return vhostDir(path.get("vhostName")).resolve("transcoder").resolve("templates");
	}

	@Override
	protected Object doGet(Map<String, String> path, Map<String, String> query) throws Exception
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("template", TemplateFiles.safeName(path.get("templateName")));
		m.put("copies", TemplateFiles.copiesOf(dir(path), path.get("templateName")));
		return m;
	}

	@Override
	@SuppressWarnings("unchecked")
	protected Object doPost(Map<String, String> path, Map<String, Object> body) throws Exception
	{
		String base = TemplateFiles.safeName(path.get("templateName"));
		List<String> known = new ArrayList<>();
		for(Map<String, String> c: TemplateFiles.copiesOf(dir(path), base))
		{
			known.add(c.get("stream"));
		}
		List<String> updated = new ArrayList<>();
		Object streams = body.get("streams");
		if(!(streams instanceof List))
		{
			throw new TemplateFiles.Refused(400, "streams must be a list");
		}
		for(Object o: (List<Object>)streams)
		{
			String s = TemplateFiles.safeName(String.valueOf(o));
			if(!known.contains(s))
			{
				throw new TemplateFiles.Refused(409, s + ".xml is not a copy of " + base + "; it is not replaced.");
			}
		}
		for(Object o: (List<Object>)streams)
		{
			String s = TemplateFiles.safeName(String.valueOf(o));
			TemplateFiles.copyForStream(dir(path), base, s, true);
			updated.add(s);
		}
		OverlayStudioLog.change(this, "updated stream copies of template " + base + ": " + updated);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("success", true);
		m.put("template", base);
		m.put("updated", updated);
		List<Map<String, Object>> live = new ArrayList<>();
		for(String s: updated)
		{
			live.addAll(OverlayStudioModule.reloadTemplateFile(path.get("vhostName"), dir(path).resolve(s + ".xml")));
		}
		m.put("live", live);
		return m;
	}
}
