/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import java.util.LinkedHashMap;
import java.util.Map;

import com.wowza.wms.plugin.overlaystudio.CaptureClient;
import com.wowza.wms.plugin.overlaystudio.CaptureLimit;
import com.wowza.wms.plugin.overlaystudio.OverlayStudioLog;
import com.wowza.wms.plugin.overlaystudio.capture.Wire;
import com.wowza.wms.plugin.overlaystudio.templates.TemplateFiles;
import com.wowza.wms.rest.WMSConfig;

/**
 * The number of pages captured at once, server wide. GET answers the limit in force, the one capture.properties set,
 * the one set here and the highest allowed. POST {"maxCaptures": n} changes it in the running capture service, with no
 * restart, and keeps it for the service's next start (CaptureLimit).
 */
@WMSConfig(restLocation = "/v1/{server}/plugin/overlaystudio/capture/limit", restVerbs = "GET,POST", xmlFilename = "")
public class CaptureLimitConfig extends VHostResourceBase
{
	/** The same ceiling the capture service enforces (CaptureService.MAX_CAPTURES_CEILING). */
	public static final int CEILING = 32;

	@Override
	protected Object doGet(Map<String, String> path, Map<String, String> query)
	{
		Map<String, Object> svc = CaptureClient.get().serviceStatus();
		Map<String, Object> m = new LinkedHashMap<>();
		boolean running = Boolean.TRUE.equals(svc.get("running"));
		m.put("running", running);
		m.put("limit", running ? svc.get("limit") : null);
		m.put("startLimit", running ? svc.get("startLimit") : null);
		m.put("set", CaptureLimit.wanted());
		// the service would not take the limit kept here when it started again, and runs at capture.properties' value
		m.put("refused", CaptureLimit.refused());
		// the service's own memory cap may hold fewer than CEILING; an older service does not say
		m.put("ceiling", running && svc.get("limitCeiling") instanceof Number c ? c : CEILING);
		m.put("memoryMaxMB", running ? svc.get("memoryMaxMB") : null);
		m.put("cpuLimit", running ? svc.get("cpuLimit") : null);
		m.put("maxBrowserMemoryMB", running ? svc.get("maxBrowserMemoryMB") : null);
		m.put("active", running ? svc.get("active") : 0);
		return m;
	}

	@Override
	protected Object doPost(Map<String, String> path, Map<String, Object> body) throws Exception
	{
		Object v = body.get("maxCaptures");
		// checked as a double first: a huge number must not wrap into range when it is made an int
		if(!(v instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.doubleValue() < 1 || n.doubleValue() > CEILING)
		{
			throw new TemplateFiles.Refused(400, "maxCaptures must be a whole number from 1 to " + CEILING);
		}
		int want = n.intValue();
		// kept first and sent under the lock, so a connection opened meanwhile sends this value, never the old one;
		// put back if the service does not take it
		synchronized(CaptureLimit.LOCK)
		{
			Integer before = CaptureLimit.wanted();
			CaptureLimit.save(want);
			Wire.Message answer;
			try
			{
				answer = CaptureClient.get().setLimit(want);
			}
			catch(CaptureClient.ServiceDown e)
			{
				CaptureLimit.restore(before);
				throw new TemplateFiles.Refused(503, "The capture service is not running, so the limit was not changed: " + e.getMessage());
			}
			if(!answer.ok())
			{
				CaptureLimit.restore(before);
				throw new TemplateFiles.Refused(409, "The capture service refused the limit: " + answer.str("error"));
			}
			CaptureLimit.applied();
		}
		OverlayStudioLog.change(this, "pages captured at once set to " + want);
		return doGet(path, Map.of());
	}
}
