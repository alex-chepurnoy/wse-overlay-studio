/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wowza.wms.plugin.overlaystudio.OverlayStudioLog;
import com.wowza.wms.plugin.overlaystudio.OverlayStudioModule;
import com.wowza.wms.plugin.overlaystudio.templates.TemplateFiles;
import com.wowza.wms.plugin.overlaystudio.templates.TemplateValidator;
import com.wowza.wms.rest.WMSConfig;

/**
 * POST {"overlay": "Scoreboard", "encodes": ["720p"]} makes a template the module wrote carry that overlay, on the
 * listed encodes (none: on the source, so every encode). {"overlay": null} takes it off. Running streams whose
 * transcoder loaded this file show the change at once (OverlayStudioModule.reloadTemplateFile).
 */
@WMSConfig(restLocation = "/v1/{server}/plugin/overlaystudio/vhosts/{vhostName}/templates/{templateName}/overlay", restVerbs = "POST", xmlFilename = "")
public class TemplateOverlayConfig extends VHostResourceBase
{
	@Override
	@SuppressWarnings("unchecked")
	protected Object doPost(Map<String, String> path, Map<String, Object> body) throws Exception
	{
		Path dir = vhostDir(path.get("vhostName")).resolve("transcoder").resolve("templates");
		String overlay = str(body, "overlay");
		List<String> encodes = new ArrayList<>();
		if(body.get("encodes") instanceof List)
		{
			for(Object o: (List<Object>)body.get("encodes"))
			{
				encodes.add(String.valueOf(o));
			}
		}
		String t = TemplateFiles.safeName(path.get("templateName"));
		TemplateFiles.setOverlay(dir, t, overlay == null || overlay.isBlank() ? null : overlay, encodes);
		TemplateUseConfig.touch(dir.resolve(t + ".xml"));
		OverlayStudioLog.change(this, overlay == null || overlay.isBlank() ? "took the overlay off template " + t
				: "template " + t + " carries overlay " + overlay + (encodes.isEmpty() ? " on every encode" : " on " + encodes));
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("success", true);
		m.put("template", t + ".xml");
		try(InputStream in = Files.newInputStream(dir.resolve(t + ".xml")))
		{
			m.putAll(TemplateValidator.validate(in).toMap());
		}
		// running streams that loaded this file show the change now
		m.put("live", OverlayStudioModule.reloadTemplateFile(path.get("vhostName"), dir.resolve(t + ".xml")));
		return m;
	}
}
