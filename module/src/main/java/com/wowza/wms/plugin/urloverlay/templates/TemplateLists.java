/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.templates;

import java.util.ArrayList;
import java.util.List;

/**
 * Rules for an application's transcoder template list. WSE loads the first template
 * in the list that exists and stops (LiveStreamTranscoder.init); WSE Manager reads and writes the list as
 * "${SourceStreamName}.xml" (Match source stream name) plus one fallback (TranscoderAppModel). Only that shape is
 * ever written, so the Manager can still edit it.
 */
public final class TemplateLists
{
	public static final String MATCH_STREAM = "${SourceStreamName}.xml";

	private TemplateLists()
	{
	}

	public record Change(String before, String after, String replacedFallback)
	{
		public boolean changed()
		{
			return !before.equals(after);
		}
	}

	public static List<String> entries(String list)
	{
		List<String> out = new ArrayList<>();
		if(list != null)
		{
			for(String s: list.split(","))
			{
				if(!s.trim().isEmpty())
				{
					out.add(s.trim());
				}
			}
		}
		return out;
	}

	static boolean isMatch(String entry)
	{
		return entry.equalsIgnoreCase(MATCH_STREAM);
	}

	/** Stream scope: put ${SourceStreamName}.xml first; the existing fallback stays. */
	public static Change forStream(String current)
	{
		List<String> e = entries(current);
		List<String> others = new ArrayList<>();
		for(String s: e)
		{
			if(!isMatch(s))
			{
				others.add(s);
			}
		}
		List<String> out = new ArrayList<>();
		out.add(MATCH_STREAM);
		// the Manager keeps one fallback; if a hand edit left several, keep the first, which is the one WSE used
		if(!others.isEmpty())
		{
			out.add(others.get(0));
		}
		return new Change(norm(current), String.join(",", out), others.size() > 1 ? String.join(",", others.subList(1, others.size())) : null);
	}

	/** App scope: make template the fallback for every stream without its own; keep ${SourceStreamName}.xml if present. */
	public static Change forApp(String current, String template)
	{
		String t = withXml(template);
		List<String> e = entries(current);
		boolean match = e.stream().anyMatch(TemplateLists::isMatch);
		String oldFallback = e.stream().filter(s -> !isMatch(s)).findFirst().orElse(null);
		List<String> out = new ArrayList<>();
		if(match)
		{
			out.add(MATCH_STREAM);
		}
		out.add(t);
		String replaced = oldFallback != null && !oldFallback.equalsIgnoreCase(t) ? oldFallback : null;
		return new Change(norm(current), String.join(",", out), replaced);
	}

	/**
	 * The template WSE loads for this stream: the first entry of the list, with ${SourceStreamName} replaced, that
	 * exists (LiveStreamTranscoder.init). Null when none does.
	 */
	public static String resolve(String list, String stream, java.util.function.Predicate<String> exists)
	{
		for(String e: entries(list))
		{
			String t = e.replace("${SourceStreamName}", stream);
			if(exists.test(t))
			{
				return t;
			}
		}
		return null;
	}

	public static String withXml(String name)
	{
		return name.toLowerCase(java.util.Locale.ROOT).endsWith(".xml") ? name : name + ".xml";
	}

	private static String norm(String s)
	{
		return String.join(",", entries(s));
	}
}
