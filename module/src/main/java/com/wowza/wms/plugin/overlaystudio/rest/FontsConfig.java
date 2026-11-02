/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import java.util.Map;

import com.wowza.wms.plugin.overlaystudio.Fonts;
import com.wowza.wms.rest.WMSConfig;

/**
 * The fonts text layers can use on this server (plan 3.7): the bundled families with their styles and any system font
 * that shadows one, Java's logical fonts, the server's own, and whether text can be drawn at all. Read-only users may
 * read it.
 */
@WMSConfig(restLocation = "/v1/{server}/plugin/overlaystudio/fonts", restVerbs = "GET", xmlFilename = "")
public class FontsConfig extends VHostResourceBase
{
	@Override
	protected Object doGet(Map<String, String> path, Map<String, String> query)
	{
		return Fonts.list();
	}
}
