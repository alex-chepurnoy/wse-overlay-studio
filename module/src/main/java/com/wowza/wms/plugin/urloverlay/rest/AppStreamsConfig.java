/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.rest;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.wowza.wms.application.IApplication;
import com.wowza.wms.application.IApplicationInstance;
import com.wowza.wms.plugin.urloverlay.templates.TemplateLists;
import com.wowza.wms.plugin.urloverlay.templates.TemplateValidator;
import com.wowza.wms.rest.WMSConfig;
import com.wowza.wms.rest.vhosts.applications.transcoder.TranscoderAppConfig;
import com.wowza.wms.vhost.IVHost;
import com.wowza.wms.vhost.VHostSingleton;

/**
 * The application's streams: those publishing now and those with their own template, each with the template WSE
 * would load for it (the same first-match rule as LiveStreamTranscoder.init) and the overlay that template names.
 */
@WMSConfig(restLocation = "/v1/{server}/plugin/urloverlay/vhosts/{vhostName}/applications/{appName}/streams", restVerbs = "GET", xmlFilename = "")
public class AppStreamsConfig extends VHostResourceBase
{
	/**
	 * Incoming streams publishing now in the application's default instance, without the transcoder's own outputs;
	 * empty when the application is not loaded.
	 */
	static List<String> publishing(String vhostName, String appName)
	{
		List<String> out = new ArrayList<>();
		IApplicationInstance ai = instance(vhostName, appName);
		if(ai != null && ai.getPublishStreamNames() != null)
		{
			for(String n: ai.getPublishStreamNames())
			{
				com.wowza.wms.stream.IMediaStream ms = ai.getStreams().getStream(n);
				if(ms == null || !ms.isTranscodeResult())
				{
					out.add(n);
				}
			}
		}
		return out;
	}

	/** Pulled streams (MediaCaster), the only ones WSE's resetStream action can restart. */
	static boolean pulled(String vhostName, String appName, String stream)
	{
		IApplicationInstance ai = instance(vhostName, appName);
		return ai != null && ai.getMediaCasterStreams() != null && ai.getMediaCasterStreams().getMediaCaster(stream) != null;
	}

	/**
	 * How a live stream reaches WSE, which decides how it can be restarted (UrlOverlayModule.restartStream): "pulled",
	 * "rtmp" or "rtp" restart on their own; "server" is published inside WSE with no client, such as by a server listener,
	 * and only an application restart starts it again.
	 */
	static String arrives(String vhostName, String appName, String stream)
	{
		IApplicationInstance ai = instance(vhostName, appName);
		if(ai == null)
		{
			return null;
		}
		if(ai.getMediaCasterStreams() != null && ai.getMediaCasterStreams().getMediaCaster(stream) != null)
		{
			return "pulled";
		}
		com.wowza.wms.stream.IMediaStream ms = ai.getStreams().getStream(stream);
		if(ms == null)
		{
			return null;
		}
		if(ms.getClient() != null)
		{
			return "rtmp";
		}
		return ms.getRTPStream() != null && ms.getRTPStream().getSession() != null ? "rtp" : "server";
	}

	static IApplicationInstance instance(String vhostName, String appName)
	{
		IVHost vhost = VHostSingleton.getInstance(vhostName);
		if(vhost == null || !vhost.isApplicationLoaded(appName))
		{
			return null;
		}
		IApplication app = vhost.getApplication(appName);
		if(app == null || !app.isAppInstanceLoaded("_definst_"))
		{
			return null;
		}
		return app.getAppInstance("_definst_");
	}

	@Override
	protected Object doGet(Map<String, String> path, Map<String, String> query) throws Exception
	{
		return streams(path);
	}

	/** The streams listing, also part of the application's state (AppStateConfig). */
	static Map<String, Object> streams(Map<String, String> path) throws Exception
	{
		String vhost = path.get("vhostName"), appName = path.get("appName");
		TranscoderAppConfig app = TemplateAssignConfig.load(path);
		Path dir = TemplateAssignConfig.templatesDir(path, app);
		List<String> live = publishing(vhost, appName);
		TreeSet<String> names = new TreeSet<>(live);
		// streams with their own template, made by assign
		File[] own = dir.toFile().listFiles((d, n) -> n.endsWith(".xml"));
		if(own != null)
		{
			for(File f: own)
			{
				String n = f.getName().substring(0, f.getName().length() - 4);
				try(InputStream in = Files.newInputStream(f.toPath()))
				{
					String desc = TemplateValidator.validate(in).description();
					if(desc != null && desc.contains("for stream " + n + "."))
					{
						names.add(n);
					}
				}
			}
		}
		String list = app.getTemplatesInUse();
		List<Map<String, Object>> rows = new ArrayList<>();
		for(String s: names)
		{
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("name", s);
			r.put("publishing", live.contains(s));
			r.put("pulled", live.contains(s) && pulled(vhost, appName, s));
			r.put("arrives", live.contains(s) ? arrives(vhost, appName, s) : null);
			// its template now builds other outputs, or another file is picked for it: shown after a restart
			r.put("restartNeeded", live.contains(s) && com.wowza.wms.plugin.urloverlay.UrlOverlayModule.outputsChanged(vhost, appName, s));
			String t = TemplateLists.resolve(list, s, n -> Files.isRegularFile(dir.resolve(n)));
			r.put("template", t);
			r.put("own", t != null && t.equalsIgnoreCase(s + ".xml"));
			if(t != null)
			{
				try(InputStream in = Files.newInputStream(dir.resolve(t)))
				{
					TemplateValidator.Result v = TemplateValidator.validate(in);
					r.put("vif", v.vif());
					r.put("overlay", v.overlay());
					r.put("usable", v.usable());
					r.put("description", v.description());
				}
			}
			rows.add(r);
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("application", appName);
		m.put("templates", list);
		m.put("liveStreamTranscoder", app.getLiveStreamTranscoder());
		m.put("streams", rows);
		return m;
	}
}
