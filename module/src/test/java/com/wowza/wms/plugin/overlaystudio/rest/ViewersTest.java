/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Viewers on this server are summed over the stream, its outputs, the adaptive groups and the SMIL files that hold
 * them, with each requested name counted once, and nothing from another stream.
 */
class ViewersTest
{
	/** Session counts as WSE would report them, set by each test. */
	static class Fake implements Viewers.Sessions
	{
		final Map<String, Integer> play = new HashMap<>(), rtp = new HashMap<>(), http = new HashMap<>();
		final Map<String, List<String>> outputs = new HashMap<>();
		final Map<String, Set<String>> groups = new HashMap<>(), smils = new HashMap<>();
		int smilReads = 0;

		@Override
		public Map<String, Integer> play()
		{
			return play;
		}

		@Override
		public Map<String, Integer> rtp()
		{
			return rtp;
		}

		@Override
		public Map<String, Integer> http()
		{
			return http;
		}

		@Override
		public List<String> outputs(String stream)
		{
			return outputs.getOrDefault(stream, List.of());
		}

		@Override
		public Set<String> groups(String name)
		{
			return groups.getOrDefault(name, Set.of());
		}

		@Override
		public Set<String> smilMembers(String smilName)
		{
			smilReads++;
			return smils.getOrDefault(smilName, Set.of());
		}
	}

	private static Fake twoCameras()
	{
		Fake f = new Fake();
		f.outputs.put("camera2", List.of("mp4:camera2_720p", "mp4:camera2_360p"));
		f.outputs.put("camera1", List.of("camera1_720p"));
		f.groups.put("camera2_720p", Set.of("camera2_all"));
		f.groups.put("camera2_360p", Set.of("camera2_all", "camera2_mobile"));
		return f;
	}

	@Test
	void everyKindOfSessionOnTheStreamCounts()
	{
		Fake f = twoCameras();
		f.play.put("camera2", 2);
		f.rtp.put("camera2", 3);
		f.http.put("camera2", 4);
		assertEquals(9, Viewers.count(List.of("camera2"), f).get("camera2"));
	}

	@Test
	void outputsGroupsAndSmilCount()
	{
		Fake f = twoCameras();
		f.http.put("camera2_360p", 1);
		f.rtp.put("camera2_720p", 2);
		f.http.put("camera2_all", 5);
		f.http.put("camera2_mobile", 1);
		f.http.put("camera2.smil", 6);
		f.smils.put("camera2.smil", Set.of("camera2_720p", "camera2_360p"));
		assertEquals(15, Viewers.count(List.of("camera2"), f).get("camera2"));
	}

	@Test
	void aGroupInTwoOutputsCountsOnce()
	{
		Fake f = twoCameras();
		f.http.put("camera2_all", 5);
		assertEquals(5, Viewers.count(List.of("camera2"), f).get("camera2"), "camera2_all lists both outputs");
	}

	@Test
	void anotherStreamsViewersDoNotCount()
	{
		Fake f = twoCameras();
		f.http.put("camera1", 10);
		f.http.put("camera1_720p", 10);
		f.http.put("camera2_backup", 10);
		f.http.put("camera1.smil", 10);
		f.smils.put("camera1.smil", Set.of("camera1_720p"));
		Map<String, Integer> v = Viewers.count(List.of("camera1", "camera2"), f);
		assertEquals(30, v.get("camera1"));
		// camera2_backup starts with camera2_ but is no output or group of camera2
		assertEquals(0, v.get("camera2"));
	}

	@Test
	void aSmilFileIsReadOncePerCount()
	{
		Fake f = twoCameras();
		f.http.put("both.smil", 1);
		f.smils.put("both.smil", Set.of("camera1_720p", "mp4:ignored"));
		Viewers.count(List.of("camera1", "camera2"), f);
		assertEquals(1, f.smilReads);
	}

	@Test
	void noSessionsIsZero()
	{
		assertEquals(Map.of("camera2", 0), Viewers.count(List.of("camera2"), twoCameras()));
	}

	@Test
	void namesLoseTheirTypePrefix()
	{
		assertEquals("camera2_360p", Viewers.bare("mp4:camera2_360p"));
		assertEquals("camera2.smil", Viewers.bare("smil:camera2.smil"));
		assertEquals("camera2", Viewers.bare("camera2"));
		assertTrue(Viewers.isSmil("Camera2.SMIL"));
	}

	@Test
	void smilSourcesAreRead(@TempDir Path dir) throws Exception
	{
		Path smil = dir.resolve("camera2.smil");
		Files.writeString(smil, """
				<smil><body><switch>
				<video src="mp4:camera2_720p" system-bitrate="2000000"/>
				<video src="camera2_360p" system-bitrate="800000"/>
				</switch></body></smil>""");
		assertEquals(Set.of("camera2_720p", "camera2_360p"), Viewers.smilSources(smil.toFile()));
		Path bad = dir.resolve("bad.smil");
		Files.writeString(bad, "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><smil><video src=\"&e;\"/></smil>");
		assertEquals(Set.of(), Viewers.smilSources(bad.toFile()), "a DOCTYPE is refused, not expanded");
	}
}
