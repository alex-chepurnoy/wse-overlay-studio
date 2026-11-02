/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.egress;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Server.xml egress settings as written, which the module sends with each capture so the capture service enforces
 * exactly what WSE is configured with.
 */
public record EgressRules(boolean allowPrivate, String allowHosts, String blockHosts, boolean blockChromeServices, String allowPorts)
{
	public static final String PROP_ALLOW_PRIVATE = "OverlayStudioAllowPrivateNetworks";
	public static final String PROP_ALLOW_HOSTS = "OverlayStudioAllowHosts";
	public static final String PROP_BLOCK_HOSTS = "OverlayStudioBlockHosts";
	public static final String PROP_BLOCK_CHROME_SERVICES = "OverlayStudioBlockChromeServices";
	public static final String PROP_ALLOW_PORTS = "OverlayStudioAllowPorts";

	public static EgressRules defaults()
	{
		return new EgressRules(false, null, null, true, null);
	}

	public AddressRules rules()
	{
		return new AddressRules(allowPrivate, allowHosts, blockHosts, blockChromeServices, allowPorts);
	}

	public Map<String, Object> toMap()
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("allowPrivate", allowPrivate);
		m.put("allowHosts", allowHosts);
		m.put("blockHosts", blockHosts);
		m.put("blockChromeServices", blockChromeServices);
		m.put("allowPorts", allowPorts);
		return m;
	}

	public static EgressRules fromMap(Object o)
	{
		if(!(o instanceof Map<?, ?> m))
		{
			return defaults();
		}
		return new EgressRules(Boolean.TRUE.equals(m.get("allowPrivate")), str(m.get("allowHosts")), str(m.get("blockHosts")),
				!Boolean.FALSE.equals(m.get("blockChromeServices")), str(m.get("allowPorts")));
	}

	private static String str(Object o)
	{
		return o == null ? null : o.toString();
	}
}
