/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.wowza.wms.plugin.overlaystudio.TextTokens;
import com.wowza.wms.rest.WMSConfig;

/**
 * GET .../format?pattern=&kind=&zone=&locale=&target=&now=&streamStart=&count= (plan 3.7): count samples (at most
 * 120) of one token, one per step from now, the step being the larger of the pattern's smallest unit and one second,
 * with the validation error if any. The browser cannot format java.time patterns or the duration grammar in the
 * server's zone, so the page asks here for samples and checks before a save. Every answer carries the server's
 * default zone and locale. Writes nothing; read-only users may call it.
 */
@WMSConfig(restLocation = "/v1/{server}/plugin/overlaystudio/format", restVerbs = "GET", xmlFilename = "")
public class FormatConfig extends VHostResourceBase
{
	public static final int MAX_SAMPLES = 120;

	@Override
	protected Object doGet(Map<String, String> path, Map<String, String> query)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("defaultZone", ZoneId.systemDefault().getId());
		m.put("defaultLocale", "en-US");
		String kind = query.getOrDefault("kind", "time");
		String pattern = query.get("pattern");
		m.put("kind", kind);
		m.put("pattern", pattern);
		List<String> samples = new ArrayList<>();
		m.put("samples", samples);
		m.put("error", null);
		try
		{
			if(pattern == null || pattern.isEmpty())
			{
				throw new IllegalArgumentException("pattern is required");
			}
			ZoneId zone = query.get("zone") == null || query.get("zone").isBlank() ? ZoneId.systemDefault() : ZoneId.of(query.get("zone"));
			Locale locale = ObjectConfig.textLocale(query.get("locale"));
			Long target = query.get("target") == null || query.get("target").isBlank() ? null : OffsetDateTime.parse(query.get("target").trim()).toInstant().toEpochMilli();
			long now = query.get("now") == null ? System.currentTimeMillis() : Long.parseLong(query.get("now"));
			long streamStart = query.get("streamStart") == null ? 0 : Long.parseLong(query.get("streamStart"));
			int count = query.get("count") == null ? 1 : Math.max(1, Math.min(MAX_SAMPLES, Integer.parseInt(query.get("count"))));
			String text = "{" + kind + ":" + pattern + "}";
			TextTokens.parse(text, locale);
			long step = TextTokens.sampleStepMs(kind, pattern);
			m.put("stepMs", step);
			for(int i = 0; i < count; i++)
			{
				samples.add(TextTokens.render(text, new TextTokens.Context(now + i * step, zone, locale, target, query.get("endText"), streamStart)));
			}
		}
		catch(RuntimeException e)
		{
			// a bad pattern, zone, locale, target or number: the reason, and no samples
			samples.clear();
			m.put("error", e.getMessage());
		}
		return m;
	}
}
