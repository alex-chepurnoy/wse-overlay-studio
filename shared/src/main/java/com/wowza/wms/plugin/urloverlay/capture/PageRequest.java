/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.capture;

import java.util.LinkedHashMap;
import java.util.Map;

import com.wowza.wms.plugin.urloverlay.egress.EgressRules;

/**
 * Everything the capture service needs to open a page for the module: the page and its viewport, the color its
 * background is painted for keying, a label for logs, the wait after each load, the reload rate, how often to take a
 * frame (the overlay's refresh), and WSE's egress rules.
 */
public record PageRequest(String url, int width, int height, int keyRgb, boolean paintBackground, String label, int pageDelayMs,
		double reloadRate, long intervalMs, EgressRules rules)
{
	public Map<String, Object> toMap()
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("url", url);
		m.put("width", width);
		m.put("height", height);
		m.put("keyRgb", keyRgb & 0xffffff);
		m.put("paintBackground", paintBackground);
		m.put("label", label);
		m.put("pageDelayMs", pageDelayMs);
		m.put("reloadRate", reloadRate);
		m.put("intervalMs", intervalMs);
		m.put("rules", rules == null ? EgressRules.defaults().toMap() : rules.toMap());
		return m;
	}

	/** The request in a header; IllegalArgumentException when it has no url. */
	public static PageRequest fromMap(Map<String, Object> m)
	{
		Object url = m.get("url");
		if(url == null || url.toString().isBlank())
		{
			throw new IllegalArgumentException("No page url");
		}
		return new PageRequest(url.toString(), num(m.get("width")), num(m.get("height")), num(m.get("keyRgb")) & 0xffffff,
				!Boolean.FALSE.equals(m.get("paintBackground")), m.get("label") == null ? url.toString() : m.get("label").toString(),
				num(m.get("pageDelayMs")), m.get("reloadRate") instanceof Number n ? n.doubleValue() : 0.0,
				m.get("intervalMs") instanceof Number i ? i.longValue() : 1000, EgressRules.fromMap(m.get("rules")));
	}

	private static int num(Object o)
	{
		return o instanceof Number n ? n.intValue() : 0;
	}
}
