/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.wowza.wms.plugin.overlaystudio.OverlayStudioLog;
import com.wowza.wms.plugin.overlaystudio.OverlayStudioModule;
import com.wowza.wms.plugin.overlaystudio.templates.TemplateBuilder;
import com.wowza.wms.plugin.overlaystudio.templates.TemplateFiles;
import com.wowza.wms.rest.WMSConfig;

/**
 * POST the builder's spec to rewrite a template the module wrote, outputs and all. A
 * customer's template that had overlays switched on in place is refused: rewriting it would drop what its owner wrote.
 */
@WMSConfig(restLocation = "/v1/{server}/plugin/overlaystudio/vhosts/{vhostName}/templates/{templateName}/spec", restVerbs = "POST", xmlFilename = "")
public class TemplateSpecConfig extends VHostResourceBase
{
	static final String IN_PLACE = "overlays switched on in place";

	@Override
	protected Object doPost(Map<String, String> path, Map<String, Object> body) throws Exception
	{
		Path dir = vhostDir(path.get("vhostName")).resolve("transcoder").resolve("templates");
		String t = TemplateFiles.safeName(path.get("templateName"));
		Path file = dir.resolve(t + ".xml");
		if(!Files.isRegularFile(file))
		{
			throw new TemplateFiles.Refused(404, "No template '" + t + "'");
		}
		if(Files.readString(file, StandardCharsets.UTF_8).contains(IN_PLACE))
		{
			throw new TemplateFiles.Refused(409, t + ".xml is a template of your own with overlays switched on; its outputs are changed in WSE Manager, or make a copy here.");
		}
		TemplateBuilder.Spec spec = TemplateBuilder.parse(t, body);
		Path written = TemplateFiles.writeBuilt(dir, spec, true);
		OverlayStudioLog.change(this, "rebuilt template " + written.getFileName() + " with " + spec.outputs().size() + " outputs");
		Map<String, Object> m = TemplatesBuildConfig.answer(written);
		// the overlay changes on running streams now; their outputs when they restart (outputsChanged)
		m.put("live", OverlayStudioModule.reloadTemplateFile(path.get("vhostName"), written));
		return m;
	}
}
