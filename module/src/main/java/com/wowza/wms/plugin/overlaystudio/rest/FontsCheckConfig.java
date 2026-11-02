/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import java.util.Map;

import com.wowza.wms.plugin.overlaystudio.Fonts;
import com.wowza.wms.rest.WMSConfig;

/**
 * GET .../fonts/check?font=&style=&chars=: which of the characters (a text's distinct characters, at most 512) the font
 * cannot draw, and the family Java actually uses for it, for the warnings beside a text layer (plan 4.2a).
 */
@WMSConfig(restLocation = "/v1/{server}/plugin/overlaystudio/fonts/check", restVerbs = "GET", xmlFilename = "")
public class FontsCheckConfig extends VHostResourceBase
{
	@Override
	protected Object doGet(Map<String, String> path, Map<String, String> query)
	{
		return Fonts.check(query.get("font"), query.get("style"), query.get("chars"));
	}
}
