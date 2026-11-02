/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import com.wowza.wms.plugin.overlaystudio.OverlayStudioLog;
import com.wowza.wms.plugin.overlaystudio.templates.TemplateFiles;
import com.wowza.wms.rest.WMSConfig;

/**
 * POST {"name": "...", "overwrite": false} writes a starter into the vhost's transcoder/templates directory. A
 * template the module did not write is never overwritten.
 */
@WMSConfig(restLocation = "/v1/{server}/plugin/overlaystudio/vhosts/{vhostName}/template-starters/{starter}", restVerbs = "POST", xmlFilename = "")
public class TemplateStarterConfig extends VHostResourceBase
{
	@Override
	protected Object doPost(Map<String, String> path, Map<String, Object> body) throws Exception
	{
		String starter = path.get("starter");
		String name = str(body, "name");
		boolean overwrite = Boolean.parseBoolean(str(body, "overwrite"));
		Path dir = vhostDir(path.get("vhostName")).resolve("transcoder").resolve("templates");
		Path written = TemplateFiles.writeStarter(dir, starter, name == null ? starter : name, overwrite);
		String overlay = str(body, "overlay");
		if(body.containsKey("overlays"))
		{
			String file = written.getFileName().toString();
			TemplateFiles.setOverlays(dir, file.substring(0, file.length() - 4), TemplateFiles.slotsFrom(body.get("overlays")));
		}
		else if(overlay != null && !overlay.isBlank())
		{
			List<String> encodes = new ArrayList<>();
			if(body.get("encodes") instanceof List)
			{
				for(Object o: (List<?>)body.get("encodes"))
				{
					encodes.add(String.valueOf(o));
				}
			}
			String file = written.getFileName().toString();
			TemplateFiles.setOverlay(dir, file.substring(0, file.length() - 4), overlay, encodes);
		}
		OverlayStudioLog.change(this, "wrote starter " + starter + " as " + written);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("success", true);
		m.put("starter", starter);
		m.put("template", written.getFileName().toString());
		return m;
	}
}
