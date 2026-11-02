/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

/** Date and time tokens in text (plan 3.3), as listed in the plan's module tests (section 7). */
class TextTokensTest
{
	static final ZoneId NY = ZoneId.of("America/New_York");
	static final long T = OffsetDateTime.parse("2027-03-14T12:00:00-04:00").toInstant().toEpochMilli();

	static TextTokens.Context at(long now, Long target, String endText, long streamStart)
	{
		return new TextTokens.Context(now, NY, Locale.US, target, endText, streamStart);
	}

	static String render(String text, TextTokens.Context ctx)
	{
		return TextTokens.render(text, ctx);
	}

	@Test
	void bracesAndTokens()
	{
		assertEquals(List.of(new TextTokens.Part("time", "HH 'o}clock'")), TextTokens.parse("{time:HH 'o}clock'}", Locale.US), "a quoted } is inside the token");
		assertEquals("{12}", render("{{{time:HH}}}", at(T, null, null, 0)), "{{ and }} draw braces");
		assertEquals("Live from Boston, 12:00 PM", render("Live from Boston, {time:h:mm a}", at(T, null, null, 0)));
		for(String bad: List.of("a { b", "a } b", "{time:HH}}", "{time:HH", "{date:yyyy}", "{time:}"))
		{
			assertThrows(IllegalArgumentException.class, () -> TextTokens.parse(bad, Locale.US), bad);
		}
		assertThrows(IllegalArgumentException.class, () -> TextTokens.parse("{time:HH:mm:ss.SSS}", Locale.US), "finer than a tenth is refused");
		assertThrows(IllegalArgumentException.class, () -> TextTokens.parse("{time:qqqqqqq}", Locale.US), "a bad DateTimeFormatter pattern");
		assertThrows(IllegalArgumentException.class, () -> TextTokens.parse("{countdown:x:mm}", Locale.US), "not a duration letter");
	}

	// the largest unit is a total, the rest remainders; doubled letters pad
	@Test
	void durationGrammar()
	{
		long threeDays = 3 * 86_400_000L, ninetyMinutes = 90 * 60_000L;
		assertEquals("72:00:00", TextTokens.formatDuration("H:mm:ss", threeDays));
		assertEquals("3d 00:00:00", TextTokens.formatDuration("d'd' HH:mm:ss", threeDays));
		assertEquals("90:00", TextTokens.formatDuration("mm:ss", ninetyMinutes));
		assertEquals("1:30:00", TextTokens.formatDuration("H:mm:ss", ninetyMinutes));
		assertEquals("05.3", TextTokens.formatDuration("ss.S", 5_300));
		assertEquals("it's 2", TextTokens.formatDuration("'it''s' m", 120_000));
	}

	@Test
	void countdownCountupAndStreamTime()
	{
		long target = T + 3 * 86_400_000L;
		assertEquals("72:00:00", render("{countdown:H:mm:ss}", at(T, target, "Live!", 0)));
		assertEquals("0:00:01", render("{countdown:H:mm:ss}", at(target - 400, target, "Live!", 0)), "the last second shows 1, not 0");
		assertEquals("Live!", render("{countdown:H:mm:ss}", at(target, target, "Live!", 0)), "the end text at the target");
		assertEquals("Live!", render("{countdown:H:mm:ss}", at(target + 5000, target, "Live!", 0)), "and past it");
		assertEquals("", render("{countdown:H:mm:ss}", at(T, null, null, 0)), "no target: the empty end text");
		assertEquals("0:00", render("{countup:m:ss}", at(T - 9000, T, null, 0)), "a count-up before its target is zero");
		assertEquals("1:05", render("{countup:m:ss}", at(T + 65_000, T, null, 0)));
		assertEquals("00:42", render("{streamtime:mm:ss}", at(T + 42_000, null, null, T)));
		assertEquals("00:00", render("{streamtime:mm:ss}", at(T, null, null, 0)), "start not known yet: zero");
	}

	@Test
	void eachTokenChangesAtItsOwnUnit()
	{
		assertEquals(T + 1000, TextTokens.nextChange("{time:HH:mm:ss}", at(T + 300, null, null, 0)));
		assertEquals(T + 60_000, TextTokens.nextChange("{time:HH:mm}", at(T + 300, null, null, 0)));
		assertEquals(T + 400, TextTokens.nextChange("{time:ss.S}", at(T + 300, null, null, 0)));
		long target = T + 10_500;
		assertEquals(T + 500, TextTokens.nextChange("{countdown:ss}", at(T, target, null, 0)), "steps on the target's half second");
		assertEquals(Long.MAX_VALUE, TextTokens.nextChange("{countdown:ss}", at(target, target, null, 0)), "past the target: no more change");
		assertEquals(T, TextTokens.nextChange("{countup:ss}", at(T - 5000, T, null, 0)), "a count-up starts at its target");
		assertEquals(Long.MAX_VALUE, TextTokens.nextChange("plain text", at(T, null, null, 0)));
		assertEquals(Math.min(T + 1000, T + 60_000), TextTokens.nextChange("{time:HH:mm} {time:ss}", at(T, null, null, 0)), "the soonest token");
	}

	// a zone with a daylight saving change: the boundary after 01:59:59 local is the next local second either way
	@Test
	void daylightSavingChange()
	{
		long beforeSpring = OffsetDateTime.parse("2027-03-14T01:59:59-05:00").toInstant().toEpochMilli();
		assertEquals("03:00", render("{time:HH:mm}", at(beforeSpring + 1000, null, null, 0)), "02:00 does not exist in New York that night");
		assertEquals(beforeSpring + 1000, TextTokens.nextChange("{time:HH:mm:ss}", at(beforeSpring, null, null, 0)));
	}

	@Test
	void samplesStepByTheLargerOfTheUnitAndOneSecond()
	{
		assertEquals(1000, TextTokens.sampleStepMs("time", "ss.S"));
		assertEquals(60_000, TextTokens.sampleStepMs("time", "HH:mm"));
		assertEquals(1000, TextTokens.sampleStepMs("countdown", "H:mm:ss"));
		assertTrue(TextTokens.hasTokens("x {time:HH} y"));
		assertFalse(TextTokens.hasTokens("x {{time}} y"));
	}
}
