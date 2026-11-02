/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.templates;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;

/**
 * Lists the applications in a vhost's conf directory, from disk, so stopped applications are included, each with
 * whether it loads the Overlay Studio module ("installed"); the plugin page offers to add it to the others. conf/Application.xml itself is WSE's default for new applications and is skipped.
 */
public final class AppScanner
{
	public static final String MODULE_CLASS = "com.wowza.wms.plugin.overlaystudio.OverlayStudioModule";

	private AppScanner()
	{
	}

	// what each Application.xml says, kept while its size and time stay the same: scans run on every state change
	private record Read(long size, long modified, Map<String, Object> map)
	{
	}
	private static final java.util.concurrent.ConcurrentHashMap<Path, Read> READ = new java.util.concurrent.ConcurrentHashMap<>();

	private static Map<String, Object> read(Path xml) throws Exception
	{
		long size = Files.size(xml), modified = Files.getLastModifiedTime(xml).toMillis();
		Read r = READ.get(xml);
		if(r == null || r.size() != size || r.modified() != modified)
		{
			try(InputStream in = Files.newInputStream(xml))
			{
				Document doc = TemplateValidator.newBuilder().parse(in);
				XPath xp = XPathFactory.newInstance().newXPath();
				String moduleCount = xp.evaluate("count(/Root/Application/Modules/Module[normalize-space(Class)='" + MODULE_CLASS + "'])", doc);
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("installed", Double.parseDouble(moduleCount) >= 1);
				m.put("streamType", xp.evaluate("normalize-space(/Root/Application/Streams/StreamType)", doc));
				m.put("liveStreamTranscoder", xp.evaluate("normalize-space(/Root/Application/Transcoder/LiveStreamTranscoder)", doc));
				m.put("templates", xp.evaluate("normalize-space(/Root/Application/Transcoder/Templates)", doc));
				r = new Read(size, modified, java.util.Collections.unmodifiableMap(m));
			}
			READ.put(xml, r);
		}
		return r.map();
	}

	public static List<Map<String, Object>> scan(Path vhostDir)
	{
		List<Map<String, Object>> out = new ArrayList<>();
		File[] dirs = vhostDir.resolve("conf").toFile().listFiles(File::isDirectory);
		if(dirs == null)
		{
			return out;
		}
		java.util.Arrays.sort(dirs);
		for(File d: dirs)
		{
			Path xml = d.toPath().resolve("Application.xml");
			if(!Files.isRegularFile(xml))
			{
				continue;
			}
			try
			{
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("name", d.getName());
				m.putAll(read(xml));
				List<Map<String, String>> saved = new ArrayList<>();
				File[] instances = ModuleFiles.appDir(vhostDir, d.getName()).resolve(ModuleFiles.STREAMS).toFile().listFiles(File::isDirectory);
				if(instances != null)
				{
					java.util.Arrays.sort(instances);
					for(File inst: instances)
					{
						File[] files = inst.listFiles((dir, n) -> n.endsWith(".json"));
						if(files == null)
						{
							continue;
						}
						java.util.Arrays.sort(files);
						for(File sf: files)
						{
							saved.add(Map.of("instance", inst.getName(), "stream", sf.getName().substring(0, sf.getName().length() - ".json".length())));
						}
					}
				}
				m.put("savedStreams", saved);
				out.add(m);
			}
			catch(Exception e)
			{
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("name", d.getName());
				m.put("error", "Application.xml could not be read: " + e.getMessage());
				out.add(m);
			}
		}
		return out;
	}
}
