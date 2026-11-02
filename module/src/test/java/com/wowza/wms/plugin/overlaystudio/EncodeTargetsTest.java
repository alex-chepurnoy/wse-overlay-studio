/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * overlayEncodes entries name encodes as the overlays plugin's README documents, and output stream names as its code
 * matched. Both must work: ["uo360"] once matched nothing, because only "m1enc_uo360" was compared.
 */
class EncodeTargetsTest
{
	@Test
	void vifSessionsAreRecognizedAndLeftAlone()
	{
		org.junit.jupiter.api.Assertions.assertTrue(OverlayStudioModule.isVifSession(java.util.List.of("videoIntelligence", "360p")));
		org.junit.jupiter.api.Assertions.assertFalse(OverlayStudioModule.isVifSession(java.util.List.of("720p", "360p")));
	}

	@Test
	void encodeNameMatches()
	{
		assertTrue(EncodeTargets.matches(List.of("uo360"), "uo360", "m1enc_uo360"));
		assertFalse(EncodeTargets.matches(List.of("uo360"), "uo1080", "m1enc_uo1080"));
		// the output stream name matches too
		{
			assertTrue(EncodeTargets.matches(List.of("m1enc_uo360"), "uo360", "m1enc_uo360"));
		}
		// empty, null or * means every encode
		{
			assertTrue(EncodeTargets.matches(null, "uo360", "s_uo360"));
			assertTrue(EncodeTargets.matches(new ArrayList<>(), "uo360", "s_uo360"));
			assertTrue(EncodeTargets.matches(List.of("*"), "uo360", "s_uo360"));
		}
	}

	@Test
	void regexIsWholeString()
	{
		assertTrue(EncodeTargets.matches(List.of("uo.*"), "uo360", "m1enc_uo360"));
		assertTrue(EncodeTargets.matches(List.of(".*_uo360"), "uo360", "m1enc_uo360"));
		// "360" alone is not a whole-string match for either name
		assertFalse(EncodeTargets.matches(List.of("360"), "uo360", "m1enc_uo360"));
	}

	@Test
	void invalidRegexFallsBackToExactComparison()
	{
		assertTrue(EncodeTargets.matches(List.of("uo[360"), "uo[360", "s"));
		assertFalse(EncodeTargets.matches(List.of("uo[360"), "uo360", "s_uo360"));
	}
}
