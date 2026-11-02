/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import java.util.LinkedHashMap;
import java.util.Map;

import com.wowza.wms.plugin.overlaystudio.templates.AppScanner;
import com.wowza.wms.rest.WMSConfig;

/** The vhost's applications that load the Overlay Studio module, read from disk so stopped applications show too. */
@WMSConfig(restLocation = "/v1/{server}/plugin/overlaystudio/vhosts/{vhostName}/applications", restVerbs = "GET", xmlFilename = "")
public class OverlayAppsConfig extends VHostResourceBase
{
	@Override
	protected Object doGet(Map<String, String> path, Map<String, String> query)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("applications", AppScanner.scan(vhostDir(path.get("vhostName"))));
		return m;
	}
}
