/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.wowza.wms.plugin.overlaystudio.templates.TemplateFiles;
import com.wowza.wms.plugin.overlaystudio.templates.TemplateValidator;
import com.wowza.wms.rest.WMSConfig;

/** Whether a template in the vhost's transcoder/templates directory can carry an overlay, and why not. */
@WMSConfig(restLocation = "/v1/{server}/plugin/overlaystudio/vhosts/{vhostName}/templates/{templateName}/validate", restVerbs = "GET", xmlFilename = "")
public class TemplateValidateConfig extends VHostResourceBase
{
	@Override
	protected Object doGet(Map<String, String> path, Map<String, String> query) throws Exception
	{
		String name = TemplateFiles.safeName(path.get("templateName"));
		Path file = vhostDir(path.get("vhostName")).resolve("transcoder").resolve("templates").resolve(name + ".xml");
		if(!Files.isRegularFile(file))
		{
			throw new TemplateFiles.Refused(404, "No template '" + name + "'");
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("template", name + ".xml");
		try(InputStream in = Files.newInputStream(file))
		{
			m.putAll(TemplateValidator.validate(in).toMap());
		}
		return m;
	}
}
