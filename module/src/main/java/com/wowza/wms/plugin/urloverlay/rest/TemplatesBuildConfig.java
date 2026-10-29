/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.rest;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.wowza.wms.plugin.urloverlay.UrlOverlayLog;
import com.wowza.wms.plugin.urloverlay.templates.TemplateBuilder;
import com.wowza.wms.plugin.urloverlay.templates.TemplateFiles;
import com.wowza.wms.plugin.urloverlay.templates.TemplateValidator;
import com.wowza.wms.rest.WMSConfig;

/**
 * POST {"name", "hardware", "overlay", "outputs": [...], "abr"} writes a new template from the page's template builder
 * (TemplateBuilder) and answers 201. 409 when the file exists.
 */
@WMSConfig(restLocation = "/v1/{server}/plugin/urloverlay/vhosts/{vhostName}/templates", restVerbs = "POST", xmlFilename = "")
public class TemplatesBuildConfig extends VHostResourceBase
{
	@Override
	protected Object doPost(Map<String, String> path, Map<String, Object> body) throws Exception
	{
		Path dir = vhostDir(path.get("vhostName")).resolve("transcoder").resolve("templates");
		TemplateBuilder.Spec spec = TemplateBuilder.parse(str(body, "name"), body);
		Path written = TemplateFiles.writeBuilt(dir, spec, false);
		UrlOverlayLog.change(this, "built template " + written.getFileName() + " with " + spec.outputs().size() + " outputs");
		return answer(written);
	}

	static Map<String, Object> answer(Path written) throws Exception
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("success", true);
		m.put("template", written.getFileName().toString());
		try(InputStream in = Files.newInputStream(written))
		{
			m.putAll(TemplateValidator.validate(in).toMap());
		}
		return m;
	}
}
