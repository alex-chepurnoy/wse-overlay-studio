/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.rest;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.wowza.wms.plugin.urloverlay.UrlOverlayModule;
import com.wowza.wms.plugin.urloverlay.templates.AppScanner;
import com.wowza.wms.plugin.urloverlay.templates.ModuleFiles;
import com.wowza.wms.plugin.urloverlay.templates.OverlayStore;
import com.wowza.wms.plugin.urloverlay.templates.TemplateFiles;
import com.wowza.wms.plugin.urloverlay.templates.TemplateLists;
import com.wowza.wms.plugin.urloverlay.templates.TemplateValidator;
import com.wowza.wms.rest.WMSConfig;

/**
 * Everything the plugin page shows for one application, with a revision. The revision is
 * computed on each call from the names, sizes and modification times of the application's files and the transcoder
 * templates, the application's settings file, the streams publishing, which streams show each overlay, and each
 * publishing stream's viewers on this server (Viewers); so an edit from another browser, by REST or by hand changes
 * it, with no file watcher, and so does a viewer joining or leaving. ?rev= with the current revision gets a short
 * "unchanged" answer.
 */
@WMSConfig(restLocation = "/v1/{server}/plugin/urloverlay/vhosts/{vhostName}/applications/{appName}/state", restVerbs = "GET", xmlFilename = "")
public class AppStateConfig extends VHostResourceBase
{
	@Override
	protected Object doGet(Map<String, String> path, Map<String, String> query) throws Exception
	{
		String vhost = path.get("vhostName"), app = path.get("appName");
		Path vhostDir = vhostDir(vhost);
		List<String> publishing = AppStreamsConfig.publishing(vhost, app);
		Map<String, Integer> viewers = viewers(vhost, app, publishing);
		String rev = revision(vhostDir, app, publishing, showing(vhostDir, vhost, app), viewers);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("revision", rev);
		if(rev.equals(query.get("rev")))
		{
			m.put("unchanged", true);
			return m;
		}
		m.put("overlays", OverlaysConfig.overlays(vhost, app).get("overlays"));
		List<Map<String, Object>> templates = templates(vhostDir.resolve("transcoder").resolve("templates"));
		listedBy(templates, AppScanner.scan(vhostDir));
		m.put("templates", templates);
		Map<String, Object> streams = AppStreamsConfig.streams(path);
		addViewers(streams.get("streams"), viewers);
		m.put("streams", streams.get("streams"));
		m.put("appTemplates", streams.get("templates"));
		m.put("liveStreamTranscoder", streams.get("liveStreamTranscoder"));
		return m;
	}

	/** Viewers on this server of each publishing stream of the default instance; empty when the application is not loaded. */
	static Map<String, Integer> viewers(String vhost, String app, List<String> publishing)
	{
		com.wowza.wms.application.IApplicationInstance ai = AppStreamsConfig.instance(vhost, app);
		return ai == null ? new TreeMap<>() : Viewers.count(publishing, Viewers.of(ai));
	}

	/** Puts viewers on each stream row: the count for a publishing stream, 0 for one that is not. */
	@SuppressWarnings("unchecked")
	static void addViewers(Object rows, Map<String, Integer> viewers)
	{
		if(rows instanceof List<?> list)
		{
			for(Object r: list)
			{
				if(r instanceof Map<?, ?> row)
				{
					((Map<String, Object>)row).put("viewers", viewers.getOrDefault(String.valueOf(row.get("name")), 0));
				}
			}
		}
	}

	// which running streams show each overlay; changes when a transcoder starts or stops drawing one
	private static String showing(Path vhostDir, String vhost, String app) throws IOException
	{
		StringBuilder b = new StringBuilder();
		for(String name: OverlayStore.list(OverlayStore.dir(vhostDir, app)))
		{
			b.append(name).append('=').append(UrlOverlayModule.streamsShowing(vhost, app, name)).append(';');
		}
		return b.toString();
	}

	/** The revision: a digest over what the page shows, cheap enough to compute on every poll. */
	static String revision(Path vhostDir, String app, List<String> publishing, String showing, Map<String, Integer> viewers) throws IOException
	{
		MessageDigest d;
		try
		{
			d = MessageDigest.getInstance("SHA-256");
		}
		catch(java.security.NoSuchAlgorithmException e)
		{
			throw new IllegalStateException(e);
		}
		StringBuilder b = new StringBuilder();
		Path appDir = ModuleFiles.appDir(vhostDir, app);
		if(Files.isDirectory(appDir))
		{
			try(Stream<Path> s = Files.walk(appDir))
			{
				for(Path p: (Iterable<Path>)s.sorted()::iterator)
				{
					stamp(b, appDir.relativize(p).toString(), p);
				}
			}
		}
		File[] templates = vhostDir.resolve("transcoder").resolve("templates").toFile().listFiles((dir, n) -> n.endsWith(".xml"));
		if(templates != null)
		{
			java.util.Arrays.sort(templates);
			for(File t: templates)
			{
				stamp(b, "template:" + t.getName(), t.toPath());
			}
		}
		stamp(b, "application", vhostDir.resolve("conf").resolve(app).resolve("Application.xml"));
		b.append("publishing:").append(publishing).append('\n').append("showing:").append(showing);
		// the numbers only, sorted by stream, so the revision moves when a count changes and at no other time
		b.append('\n').append("viewers:").append(new TreeMap<>(viewers));
		return HexFormat.of().formatHex(d.digest(b.toString().getBytes(StandardCharsets.UTF_8))).substring(0, 16);
	}

	private static void stamp(StringBuilder b, String name, Path p) throws IOException
	{
		b.append(name).append('|');
		if(Files.isRegularFile(p))
		{
			b.append(Files.size(p)).append('|').append(Files.getLastModifiedTime(p).toMillis());
		}
		b.append('\n');
	}

	/**
	 * Adds to each template the applications whose Transcoder/Templates list names it, since a template is shared by
	 * every application of the virtual host.
	 */
	static void listedBy(List<Map<String, Object>> templates, List<Map<String, Object>> apps)
	{
		for(Map<String, Object> t: templates)
		{
			List<String> by = new ArrayList<>();
			for(Map<String, Object> a: apps)
			{
				if(a.get("templates") instanceof String list && TemplateLists.entries(list).contains(t.get("template")))
				{
					by.add(String.valueOf(a.get("name")));
				}
			}
			t.put("listedBy", by);
		}
	}

	/** Every template with what the validator reads from it, as GET .../templates/{name}/validate returns it. */
	static List<Map<String, Object>> templates(Path dir) throws IOException
	{
		List<Map<String, Object>> out = new ArrayList<>();
		File[] files = dir.toFile().listFiles((d, n) -> n.endsWith(".xml"));
		if(files == null)
		{
			return out;
		}
		java.util.Arrays.sort(files);
		for(File f: files)
		{
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", f.getName().substring(0, f.getName().length() - 4));
			m.put("template", f.getName());
			m.put("modified", f.lastModified());
			m.put("shipped", TemplateFiles.WSE_SHIPPED.contains(m.get("name")));
			try(InputStream in = Files.newInputStream(f.toPath()))
			{
				m.putAll(TemplateValidator.validate(in).toMap());
				// a stream's copy says whether it still matches its master, so the list can offer an update
				String base = TemplateFiles.copyBase(f.getName(), m.get("description") == null ? null : String.valueOf(m.get("description")));
				if(base != null)
				{
					m.put("copyCurrent", TemplateFiles.copyIsCurrent(dir, base, f.getName().substring(0, f.getName().length() - 4)));
				}
			}
			catch(Exception e)
			{
				m.put("error", e.getMessage());
			}
			out.add(m);
		}
		return out;
	}
}
