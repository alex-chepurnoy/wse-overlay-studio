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
 * POST {"overlays": [{"name": "Scoreboard", "encodes": ["720p"], "slot": 1}, ...]} sets the overlays a template the
 * module wrote carries, up to four (plan 3.9), each on its listed encodes (none: on the source, so every encode);
 * an empty list takes them all off. {"overlay": "Scoreboard", "encodes": [...]} sets slot 1 alone and keeps the others
 * ({"overlay": null} takes slot 1 off). Running streams whose transcoder loaded this file show the change at once
 * (OverlayStudioModule.reloadTemplateFile).
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
		if(body.containsKey("overlays"))
		{
			List<TemplateValidator.Slot> slots = TemplateFiles.slotsFrom(body.get("overlays"));
			TemplateFiles.setOverlays(dir, t, slots);
			OverlayStudioLog.change(this, slots.isEmpty() ? "took every overlay off template " + t : "template " + t + " carries overlays " + describe(slots));
		}
		else
		{
			TemplateFiles.setOverlay(dir, t, overlay == null || overlay.isBlank() ? null : overlay, encodes);
			OverlayStudioLog.change(this, overlay == null || overlay.isBlank() ? "took the overlay in slot 1 off template " + t
					: "template " + t + " carries overlay " + overlay + " in slot 1" + (encodes.isEmpty() ? " on every encode" : " on " + encodes));
		}
		TemplateUseConfig.touch(dir.resolve(t + ".xml"));
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

	/** For the change log: slot, name and encodes of each. */
	static String describe(List<TemplateValidator.Slot> slots)
	{
		List<String> out = new ArrayList<>();
		for(TemplateValidator.Slot x: slots)
		{
			out.add(x.slot() + ": " + x.name() + (x.encodes().isEmpty() ? " on every encode" : " on " + x.encodes()));
		}
		return String.join("; ", out);
	}
}
