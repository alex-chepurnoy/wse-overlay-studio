/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import java.util.Map;

import com.wowza.wms.plugin.overlaystudio.OverlayStudioModule;
import com.wowza.wms.rest.WMSConfig;

/** Per-stream health: transcoder state and encodes, draw timing, and each webPage capture's state and Chrome process. */
@WMSConfig(restLocation = "/v1/{server}/plugin/overlaystudio/vhosts/{vhostName}/applications/{appName}/instances/{instance}/streams/{stream}/status", restVerbs = "GET", xmlFilename = "")
public class StatusConfig extends StreamResourceBase
{
	@Override
	protected boolean load(OverlayStudioModule module, Map<String, Object> body)
	{
		body.putAll(module.streamStatus(streamName));
		return true;
	}
}
