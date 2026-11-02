/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import java.util.LinkedHashMap;
import java.util.Map;

import com.wowza.wms.plugin.overlaystudio.OverlayStudioLog;
import com.wowza.wms.plugin.overlaystudio.OverlayStudioModule;
import com.wowza.wms.plugin.overlaystudio.templates.TemplateFiles;
import com.wowza.wms.rest.WMSConfig;

/**
 * POST restarts a running stream so its transcoder is built again from its template (OverlayStudioModule.restartStream):
 * a pulled stream is reset, a pushed one's publisher is disconnected and its encoder reconnects.
 */
@WMSConfig(restLocation = "/v1/{server}/plugin/overlaystudio/vhosts/{vhostName}/applications/{appName}/instances/{instance}/streams/{stream}/restart", restVerbs = "POST", xmlFilename = "")
public class StreamRestartConfig extends VHostResourceBase
{
	@Override
	protected Object doPost(Map<String, String> path, Map<String, Object> body) throws Exception
	{
		String stream = TemplateFiles.safeName(path.get("stream"));
		String how = OverlayStudioModule.restartStream(path.get("vhostName"), path.get("appName"), path.get("instance"), stream);
		OverlayStudioLog.change(this, "restarted stream " + path.get("appName") + "/" + stream + " (" + how + ") to load its template again");
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("success", true);
		m.put("stream", stream);
		m.put("how", how);
		return m;
	}
}
