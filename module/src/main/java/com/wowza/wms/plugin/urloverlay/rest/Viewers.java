/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.rest;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.wowza.wms.application.IApplicationInstance;
import com.wowza.wms.httpstreamer.model.IHTTPStreamerSession;
import com.wowza.wms.stream.IMediaStream;
import com.wowza.wms.stream.MediaStreamMapGroup;
import com.wowza.wms.stream.livetranscoder.ILiveStreamTranscoder;
import com.wowza.wms.transcoder.model.LiveStreamTranscoder;
import com.wowza.wms.transcoder.model.TranscoderStream;
import com.wowza.wms.transcoder.model.TranscoderStreamDestination;

/**
 * Viewers on this server of each publishing stream: RTMP players, RTSP and WebRTC sessions and HTTP streamer sessions,
 * summed over the stream's own name, its transcoder outputs, the adaptive groups (ngrp) holding any of those, and the
 * SMIL files listing any of those. WSE keys HTTP sessions by the name each player requested, with a prefix such as
 * ngrp: or smil: already removed, so a group or SMIL viewer is counted under the group or file name and not under a
 * stream name. Each name is counted once per stream, so an output that is also in a group is not counted twice.
 */
public final class Viewers
{
	/** What WSE knows about one application instance, behind an interface so the sum can be tested without WSE. */
	public interface Sessions
	{
		/** RTMP players by the name played. */
		Map<String, Integer> play();

		/** RTSP and WebRTC sessions by name; WSE keeps both in one map, so WebRTC is not asked for separately. */
		Map<String, Integer> rtp();

		/** HTTP streamer sessions of every protocol by the name requested. */
		Map<String, Integer> http();

		/** The output stream names the stream's transcoder makes, without a stream type prefix. */
		List<String> outputs(String stream);

		/** The names of the adaptive groups that list this stream name. */
		Set<String> groups(String name);

		/** The stream names a SMIL file lists, without stream type prefixes; empty when it cannot be read. */
		Set<String> smilMembers(String smilName);
	}

	private Viewers()
	{
	}

	/** Viewers of each stream, in the order given. */
	public static Map<String, Integer> count(List<String> streams, Sessions s)
	{
		Map<String, Integer> play = s.play(), rtp = s.rtp(), http = s.http();
		Map<String, Integer> out = new TreeMap<>();
		Map<String, Set<String>> smils = new HashMap<>();
		for(String stream: streams)
		{
			Set<String> names = names(stream, s);
			Set<String> counted = new LinkedHashSet<>(names);
			for(String n: names)
			{
				counted.addAll(s.groups(n));
			}
			for(String key: http.keySet())
			{
				if(isSmil(key))
				{
					Set<String> members = smils.computeIfAbsent(key, s::smilMembers);
					if(!Collections.disjoint(members, names))
					{
						counted.add(key);
					}
				}
			}
			int total = 0;
			for(String n: counted)
			{
				total += play.getOrDefault(n, 0) + rtp.getOrDefault(n, 0) + http.getOrDefault(n, 0);
			}
			out.put(stream, total);
		}
		return out;
	}

	/** The stream and its outputs. */
	static Set<String> names(String stream, Sessions s)
	{
		Set<String> names = new LinkedHashSet<>();
		names.add(stream);
		for(String o: s.outputs(stream))
		{
			names.add(bare(o));
		}
		return names;
	}

	static boolean isSmil(String name)
	{
		String n = name.toLowerCase();
		return n.endsWith(".smil") || n.endsWith(".smi");
	}

	private static final Pattern PREFIX = Pattern.compile("^[A-Za-z0-9]+:(.+)$");

	/** A stream name without a type prefix such as mp4: or smil:, as WSE stores it after resolving a request. */
	static String bare(String name)
	{
		Matcher m = PREFIX.matcher(name.trim());
		return m.matches() ? m.group(1) : name.trim();
	}

	/** The stream names in a SMIL document: every src attribute, without a type prefix. */
	static Set<String> smilSources(File f)
	{
		Set<String> out = new LinkedHashSet<>();
		try
		{
			DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
			dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			dbf.setExpandEntityReferences(false);
			DocumentBuilder db = dbf.newDocumentBuilder();
			NodeList all = db.parse(f).getElementsByTagName("*");
			for(int i = 0; i < all.getLength(); i++)
			{
				String src = ((Element)all.item(i)).getAttribute("src");
				if(src != null && !src.isBlank())
				{
					out.add(bare(src));
				}
			}
		}
		catch(Exception e)
		{
			// an unreadable SMIL file counts no one
		}
		return out;
	}

	private record Parsed(long modified, long size, Set<String> members)
	{
	}

	// parsed SMIL files by path, kept while the file's time and size stay the same
	private static final Map<String, Parsed> SMILS = new ConcurrentHashMap<>();

	/** Sessions of a running application instance. */
	public static Sessions of(IApplicationInstance ai)
	{
		return new Sessions()
		{
			@Override
			public Map<String, Integer> play()
			{
				return nonNull(ai.getPlayStreamCountsByName());
			}

			@Override
			public Map<String, Integer> rtp()
			{
				return nonNull(ai.getRTPSessionCountsByName());
			}

			@Override
			public Map<String, Integer> http()
			{
				Map<String, Integer> m = new HashMap<>();
				for(int p = 0; p < IHTTPStreamerSession.SESSIONPROTOCOL_COUNT; p++)
				{
					for(Map.Entry<String, Integer> e: nonNull(ai.getHTTPStreamerSessionCountsByName(p)).entrySet())
					{
						m.merge(e.getKey(), e.getValue(), Integer::sum);
					}
				}
				return m;
			}

			@Override
			public List<String> outputs(String stream)
			{
				List<String> out = new ArrayList<>();
				IMediaStream ms = ai.getStreams().getStream(stream);
				Map<String, ILiveStreamTranscoder> all = ms == null ? null : ms.getLiveStreamTranscoders();
				if(all == null)
				{
					return out;
				}
				for(ILiveStreamTranscoder t: all.values())
				{
					TranscoderStream ts = t instanceof LiveStreamTranscoder lst ? lst.getTranscodingStream() : null;
					if(ts == null || ts.getDestinations() == null)
					{
						continue;
					}
					for(TranscoderStreamDestination d: ts.getDestinations())
					{
						if(d.isEnable())
						{
							out.add(d.getStreamName(stream));
						}
					}
				}
				return out;
			}

			@Override
			public Set<String> groups(String name)
			{
				Set<String> out = new LinkedHashSet<>();
				Set<MediaStreamMapGroup> groups = ai.getStreams().getNameGroups(name);
				if(groups != null)
				{
					for(MediaStreamMapGroup g: groups)
					{
						out.add(g.getName());
					}
				}
				return out;
			}

			@Override
			public Set<String> smilMembers(String smilName)
			{
				String dir = ai.getStreamStorageDir();
				if(dir == null)
				{
					return Set.of();
				}
				Path root = Paths.get(dir).toAbsolutePath().normalize();
				Path p = root.resolve(smilName).normalize();
				File f = p.toFile();
				if(!p.startsWith(root) || !f.isFile())
				{
					return Set.of();
				}
				String key = p.toString();
				Parsed was = SMILS.get(key);
				if(was != null && was.modified() == f.lastModified() && was.size() == f.length())
				{
					return was.members();
				}
				Parsed now = new Parsed(f.lastModified(), f.length(), smilSources(f));
				SMILS.put(key, now);
				return now.members();
			}
		};
	}

	private static Map<String, Integer> nonNull(Map<String, Integer> m)
	{
		return m == null ? Map.of() : m;
	}
}
