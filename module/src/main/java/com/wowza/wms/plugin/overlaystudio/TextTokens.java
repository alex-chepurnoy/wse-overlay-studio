/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Date and time inside any text (plan 3.3). A text may hold tokens:
 *
 * {time:pattern}        now, in the layer's zone, a java.time DateTimeFormatter pattern
 * {countdown:pattern}   time left to the layer's target; its endText at and past it, and with no target
 * {countup:pattern}     time since the target; zero before it; endText with no target
 * {streamtime:pattern}  time since the stream's transcoder started
 *
 * Literal braces are {{ and }}. A token runs from { to the first } outside single quotes, so {time:HH 'o}clock'} is
 * one token; any other brace is refused. The three durations use their own grammar: d days, H or HH hours, m or mm
 * minutes, s or ss seconds, S tenths, text in single quotes as is. Doubled letters pad to two digits. The largest unit
 * in a pattern is a total and the others are remainders: three days in H:mm:ss is 72:00:00.
 */
public final class TextTokens
{
	public static final List<String> KINDS = List.of("time", "countdown", "countup", "streamtime");

	/** A piece of text: literal, or a token of one kind with its pattern. */
	public record Part(String kind, String text)
	{
		boolean literal()
		{
			return kind == null;
		}
	}

	/** What a token needs to draw: now, the layer's zone, locale, target and endText, and when the stream started (0: not known). */
	public record Context(long now, ZoneId zone, Locale locale, Long target, String endText, long streamStart)
	{
	}

	private TextTokens()
	{
	}

	/** Splits text into literals and tokens. Throws IllegalArgumentException with the reason for a stray brace or a bad pattern. */
	public static List<Part> parse(String text, Locale locale)
	{
		List<Part> parts = new ArrayList<>();
		StringBuilder lit = new StringBuilder();
		int i = 0, n = text.length();
		while(i < n)
		{
			char c = text.charAt(i);
			if(c == '{' && i + 1 < n && text.charAt(i + 1) == '{')
			{
				lit.append('{');
				i += 2;
			}
			else if(c == '}' && i + 1 < n && text.charAt(i + 1) == '}')
			{
				lit.append('}');
				i += 2;
			}
			else if(c == '}')
			{
				throw new IllegalArgumentException("A } at character " + (i + 1) + " closes nothing; write }} for a brace");
			}
			else if(c == '{')
			{
				int end = -1;
				boolean quoted = false;
				for(int j = i + 1; j < n; j++)
				{
					char d = text.charAt(j);
					if(d == '\'')
					{
						quoted = !quoted;
					}
					else if(d == '}' && !quoted)
					{
						end = j;
						break;
					}
				}
				if(end < 0)
				{
					throw new IllegalArgumentException("The { at character " + (i + 1) + " is never closed; write {{ for a brace");
				}
				String body = text.substring(i + 1, end);
				int colon = body.indexOf(':');
				String kind = colon < 0 ? body : body.substring(0, colon);
				String pattern = colon < 0 ? "" : body.substring(colon + 1);
				if(!KINDS.contains(kind))
				{
					throw new IllegalArgumentException("Unknown token {" + kind + "}: use {time:...}, {countdown:...}, {countup:...} or {streamtime:...}");
				}
				if(pattern.isEmpty())
				{
					throw new IllegalArgumentException("{" + kind + "} needs a pattern, as in {" + kind + ":" + (kind.equals("time") ? "HH:mm:ss" : "H:mm:ss") + "}");
				}
				checkPattern(kind, pattern, locale);
				if(lit.length() > 0)
				{
					parts.add(new Part(null, lit.toString()));
					lit.setLength(0);
				}
				parts.add(new Part(kind, pattern));
				i = end + 1;
			}
			else
			{
				lit.append(c);
				i++;
			}
		}
		if(lit.length() > 0)
		{
			parts.add(new Part(null, lit.toString()));
		}
		return parts;
	}

	/** Whether the text has any token (parsing it; a text that does not parse has none). */
	public static boolean hasTokens(String text)
	{
		try
		{
			return text != null && parse(text, Locale.US).stream().anyMatch(p -> !p.literal());
		}
		catch(IllegalArgumentException e)
		{
			return false;
		}
	}

	private static void checkPattern(String kind, String pattern, Locale locale)
	{
		if(kind.equals("time"))
		{
			String letters = unquoted(pattern);
			if(letters.matches(".*(SS|[nNA]).*"))
			{
				throw new IllegalArgumentException("{time:" + pattern + "} is finer than a tenth of a second; use S at the finest");
			}
			try
			{
				DateTimeFormatter.ofPattern(pattern, locale);
			}
			catch(IllegalArgumentException e)
			{
				throw new IllegalArgumentException("{time:" + pattern + "}: " + e.getMessage());
			}
			return;
		}
		durationUnits(kind, pattern);
	}

	/** The letters of a pattern outside single quotes. */
	static String unquoted(String pattern)
	{
		return pattern.replaceAll("'[^']*'", "");
	}

	// duration units, largest first, in milliseconds
	private static final String UNITS = "dHmsS";
	private static final long[] UNIT_MS = { 86_400_000L, 3_600_000L, 60_000L, 1000L, 100L };

	/** The duration letters a pattern uses (checked: only d H m s S, at most two of a letter, S alone). */
	private static String durationUnits(String kind, String pattern)
	{
		StringBuilder used = new StringBuilder();
		boolean quoted = false;
		for(int i = 0; i < pattern.length(); )
		{
			char c = pattern.charAt(i);
			if(c == '\'')
			{
				quoted = !quoted;
				i++;
				continue;
			}
			if(quoted || !Character.isLetter(c))
			{
				i++;
				continue;
			}
			if(UNITS.indexOf(c) < 0)
			{
				throw new IllegalArgumentException("{" + kind + ":" + pattern + "}: '" + c + "' is not a duration letter; use d, H, m, s or S, and quote text");
			}
			int run = 1;
			while(i + run < pattern.length() && pattern.charAt(i + run) == c)
			{
				run++;
			}
			if(run > 2 || (c == 'S' && run > 1))
			{
				throw new IllegalArgumentException("{" + kind + ":" + pattern + "}: " + String.valueOf(c).repeat(run) + " is too long; write " + c + (c == 'S' ? "" : " or " + c + c));
			}
			if(used.indexOf(String.valueOf(c)) < 0)
			{
				used.append(c);
			}
			i += run;
		}
		if(quoted)
		{
			throw new IllegalArgumentException("{" + kind + ":" + pattern + "}: a quote is never closed");
		}
		if(used.length() == 0)
		{
			throw new IllegalArgumentException("{" + kind + ":" + pattern + "} shows no unit; use d, H, m, s or S");
		}
		return used.toString();
	}

	/** Formats a duration in ms by a duration pattern: the largest unit used is a total, the rest remainders. */
	public static String formatDuration(String pattern, long ms)
	{
		String used = durationUnits("countdown", pattern);
		int largest = UNITS.length();
		for(char c: used.toCharArray())
		{
			largest = Math.min(largest, UNITS.indexOf(c));
		}
		long rest = Math.max(0, ms);
		StringBuilder out = new StringBuilder();
		boolean quoted = false;
		for(int i = 0; i < pattern.length(); )
		{
			char c = pattern.charAt(i);
			if(c == '\'')
			{
				if(quoted && i + 1 < pattern.length() && pattern.charAt(i + 1) == '\'')
				{
					out.append('\'');
					i += 2;
					continue;
				}
				quoted = !quoted;
				i++;
				continue;
			}
			int u = quoted ? -1 : UNITS.indexOf(c);
			if(u < 0)
			{
				out.append(c);
				i++;
				continue;
			}
			int run = 1;
			while(i + run < pattern.length() && pattern.charAt(i + run) == c)
			{
				run++;
			}
			long value = rest / UNIT_MS[u];
			if(u > largest)
			{
				value %= UNIT_MS[u - 1] / UNIT_MS[u];
			}
			String v = Long.toString(value);
			out.append(run == 2 && v.length() < 2 ? "0" + v : v);
			i += run;
		}
		return out.toString();
	}

	/** The text with every token drawn for this moment. Throws IllegalArgumentException as parse does. */
	public static String render(String text, Context ctx)
	{
		StringBuilder out = new StringBuilder();
		for(Part p: parse(text, ctx.locale()))
		{
			out.append(p.literal() ? p.text() : token(p, ctx));
		}
		return out.toString();
	}

	private static String token(Part p, Context ctx)
	{
		String end = ctx.endText() == null ? "" : ctx.endText();
		switch(p.kind())
		{
			case "time":
				return ZonedDateTime.ofInstant(Instant.ofEpochMilli(ctx.now()), ctx.zone()).format(DateTimeFormatter.ofPattern(p.text(), ctx.locale()));
			case "countdown":
				if(ctx.target() == null || ctx.now() >= ctx.target())
				{
					return end;
				}
				// time left rounds up, so a countdown shows 0:00:01 for its last second and its end text at the target
				return formatDuration(p.text(), ceilTo(ctx.target() - ctx.now(), smallestMs(p)));
			case "countup":
				if(ctx.target() == null)
				{
					return end;
				}
				return formatDuration(p.text(), Math.max(0, ctx.now() - ctx.target()));
			default:
				return formatDuration(p.text(), ctx.streamStart() > 0 ? Math.max(0, ctx.now() - ctx.streamStart()) : 0);
		}
	}

	private static long ceilTo(long ms, long unit)
	{
		return (ms + unit - 1) / unit * unit;
	}

	/** The smallest unit a duration token shows, in ms. */
	private static long smallestMs(Part p)
	{
		long smallest = UNIT_MS[0];
		for(char c: durationUnits(p.kind(), p.text()).toCharArray())
		{
			smallest = Math.min(smallest, UNIT_MS[UNITS.indexOf(c)]);
		}
		return smallest;
	}

	/**
	 * When the text next reads differently, after now, or Long.MAX_VALUE: each time token at the next boundary of its
	 * smallest unit in the layer's zone, each duration token when its value next steps (a countdown also at its
	 * target, a count-up at its target if before it). A text that does not parse never changes.
	 */
	public static long nextChange(String text, Context ctx)
	{
		List<Part> parts;
		try
		{
			parts = parse(text, ctx.locale());
		}
		catch(IllegalArgumentException e)
		{
			return Long.MAX_VALUE;
		}
		long now = ctx.now(), next = Long.MAX_VALUE;
		for(Part p: parts)
		{
			if(p.literal())
			{
				continue;
			}
			if(p.kind().equals("time"))
			{
				next = Math.min(next, nextTimeBoundary(p.text(), now, ctx.zone()));
				continue;
			}
			long unit = smallestMs(p);
			long origin;
			if(p.kind().equals("streamtime"))
			{
				if(ctx.streamStart() <= 0)
				{
					continue;
				}
				origin = ctx.streamStart();
			}
			else if(ctx.target() == null)
			{
				continue;
			}
			else
			{
				origin = ctx.target();
			}
			if(p.kind().equals("countdown") && now >= origin)
			{
				continue;
			}
			if(p.kind().equals("countup") && now < origin)
			{
				next = Math.min(next, origin);
				continue;
			}
			// the value steps every unit counted from the origin (the target, or the stream's start)
			long phase = Math.floorMod(now - origin, unit);
			next = Math.min(next, now + (unit - phase));
		}
		return next;
	}

	/** The next boundary of the smallest unit a time pattern shows, after now, in zone; quoted text is ignored. */
	public static long nextTimeBoundary(String pattern, long now, ZoneId zone)
	{
		String letters = unquoted(pattern);
		if(letters.indexOf('S') >= 0)
		{
			return (now / 100 + 1) * 100;
		}
		ChronoUnit unit = letters.indexOf('s') >= 0 ? ChronoUnit.SECONDS : letters.indexOf('m') >= 0 ? ChronoUnit.MINUTES
				: letters.matches(".*[HhKkaB].*") ? ChronoUnit.HOURS : ChronoUnit.DAYS;
		ZonedDateTime t = Instant.ofEpochMilli(now).atZone(zone);
		return t.truncatedTo(unit).plus(1, unit).toInstant().toEpochMilli();
	}

	/** The step between samples of a token: the larger of its smallest unit and one second. */
	public static long sampleStepMs(String kind, String pattern)
	{
		long unit;
		if(kind.equals("time"))
		{
			String l = unquoted(pattern);
			unit = l.indexOf('S') >= 0 ? 100 : l.indexOf('s') >= 0 ? 1000 : l.indexOf('m') >= 0 ? 60_000 : l.matches(".*[HhKkaB].*") ? 3_600_000 : 86_400_000;
		}
		else
		{
			unit = smallestMs(new Part(kind, pattern));
		}
		return Math.max(1000, unit);
	}
}
