/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.rest;

import java.util.LinkedHashMap;
import java.util.Map;

import com.wowza.wms.plugin.urloverlay.templates.TemplateFiles;
import com.wowza.wms.rest.WMSConfig;

/** The starter templates bundled with the module, each with its validation and XML. */
@WMSConfig(restLocation = "/v1/{server}/plugin/urloverlay/vhosts/{vhostName}/template-starters", restVerbs = "GET", xmlFilename = "")
public class TemplateStartersConfig extends VHostResourceBase
{
	@Override
	protected Object doGet(Map<String, String> path, Map<String, String> query) throws Exception
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("starters", TemplateFiles.describeStarters());
		return m;
	}
}
