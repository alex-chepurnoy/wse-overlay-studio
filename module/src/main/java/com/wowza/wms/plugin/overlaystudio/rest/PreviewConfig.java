/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wowza.wms.application.IApplicationInstance;
import com.wowza.wms.plugin.overlaystudio.OverlayStudioModule;
import com.wowza.wms.rest.WMSConfig;
import com.wowza.wms.vhost.HostPort;
import com.wowza.wms.vhost.HostPortList;

/**
 * HLS URLs for the source and each running encode, built from the VHost's streaming ports. {host} is returned
 * literally; the UI substitutes the host it used to reach WSE REST.
 */
@WMSConfig(restLocation = "/v1/{server}/plugin/overlaystudio/vhosts/{vhostName}/applications/{appName}/instances/{instance}/streams/{stream}/preview", restVerbs = "GET", xmlFilename = "")
public class PreviewConfig extends StreamResourceBase
{
	@Override
	protected boolean load(OverlayStudioModule module, Map<String, Object> body)
	{
		IApplicationInstance appInstance = module.getAppInstance();
		if(appInstance == null)
		{
			return false;
		}
		Integer http = null, https = null;
		HostPortList ports = appInstance.getVHost().getHostPortsList();
		for(int i = 0; i < ports.size(); i++)
		{
			HostPort hp = ports.get(i);
			if(!HostPort.TYPE_STREAMING.equals(hp.getTypeStr()))
			{
				continue;
			}
			if(hp.getSSLConfig() != null)
			{
				if(https == null)
				{
					https = hp.getPort();
				}
			}
			else if(http == null)
			{
				http = hp.getPort();
			}
		}
		String app = appInstance.getApplication().getName();
		String scheme = http != null ? "http" : "https";
		Integer port = http != null ? http : https;
		body.put("source", entry(null, streamName, scheme, port, app));
		List<Map<String, Object>> outputs = new ArrayList<>();
		for(String[] e: module.encodeOutputs(streamName))
		{
			outputs.add(entry(e[0], e[1], scheme, port, app));
		}
		body.put("outputs", outputs);
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("http", http);
		p.put("https", https);
		body.put("ports", p);
		// the application's HTTP streamers (Application.xml HTTPStreamers), for the playback addresses the page offers
		List<String> streamers = new ArrayList<>();
		for(String s: String.valueOf(appInstance.getHTTPStreamerList()).split(","))
		{
			if(!s.isBlank() && !s.trim().equals("null"))
			{
				streamers.add(s.trim());
			}
		}
		body.put("streamers", streamers);
		body.put("application", app);
		return true;
	}

	static Map<String, Object> entry(String encode, String stream, String scheme, Integer port, String app)
	{
		String name = stream != null && stream.startsWith("mp4:") ? stream.substring(4) : stream;
		Map<String, Object> m = new LinkedHashMap<>();
		if(encode != null)
		{
			m.put("encode", encode);
		}
		m.put("stream", name);
		m.put("hls", port == null ? null : scheme + "://{host}:" + port + "/" + app + "/" + name + "/playlist.m3u8");
		return m;
	}
}
