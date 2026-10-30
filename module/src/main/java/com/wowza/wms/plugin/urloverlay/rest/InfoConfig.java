/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.rest;

import java.util.LinkedHashMap;
import java.util.Map;

import org.restlet.Request;
import org.restlet.data.MediaType;
import org.restlet.data.Preference;
import org.restlet.representation.Representation;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.wowza.wms.plugin.urloverlay.CaptureClient;
import com.wowza.wms.plugin.urloverlay.egress.AddressRules;
import com.wowza.wms.plugin.urloverlay.ReleaseInfo;
import com.wowza.wms.plugin.urloverlay.UrlOverlayServer;
import com.wowza.wms.plugin.urloverlay.WebPageCapture;
import com.wowza.wms.rest.ConfigBase;
import com.wowza.wms.rest.WMSConfig;

/** Server-wide capture engine state: versions, Chrome and chromedriver, session use, network rules, recent blocks. */
// fields of the WSE REST base class that mean nothing in these responses
@JsonIgnoreProperties({ "version", "serverName", "saveFieldList" })
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.NONE, getterVisibility = JsonAutoDetect.Visibility.NONE, isGetterVisibility = JsonAutoDetect.Visibility.NONE, setterVisibility = JsonAutoDetect.Visibility.NONE, creatorVisibility = JsonAutoDetect.Visibility.NONE)
@WMSConfig(restLocation = "/v1/{server}/plugin/urloverlay/info", restVerbs = "GET", xmlFilename = "")
public class InfoConfig extends ConfigBase
{
	private final Map<String, Object> body = new LinkedHashMap<>();

	@JsonAnyGetter
	public Map<String, Object> body()
	{
		return body;
	}

	@Override
	public Representation get(Request request)
	{
		// answer JSON only, like the other resources of this plugin
		request.getClientInfo().getAcceptedMediaTypes();
		java.util.List<Preference<MediaType>> types = new java.util.ArrayList<>();
		types.add(new Preference<>(MediaType.APPLICATION_JSON));
		request.getClientInfo().setAcceptedMediaTypes(types);
		return super.get(request);
	}

	/** The virtual host's HostPorts of Type Streaming that serve HLS: port and whether it is SSL. */
	static java.util.List<Map<String, Object>> streamingPorts(com.wowza.wms.vhost.IVHost vhost)
	{
		java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
		com.wowza.wms.vhost.HostPortList list = vhost == null ? null : vhost.getHostPortsList();
		for(int i = 0; list != null && i < list.size(); i++)
		{
			com.wowza.wms.vhost.HostPort hp = list.get(i);
			java.util.List<String> adapters = hp.getHTTPStreamerAdapterIDs();
			if(hp.isStreaming() && (adapters == null || adapters.isEmpty() || adapters.contains("cupertinostreaming")))
			{
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("port", hp.getPort());
				m.put("ssl", hp.isSecure());
				out.add(m);
			}
		}
		return out;
	}

	/**
	 * The daily Chrome update's last run, from chrome-update.json, which the installer's UpdateChrome writes next to
	 * capture.properties: beside the socket on Windows (C:\ProgramData\WowzaURLOverlay), /etc/wse-url-overlay on Linux.
	 * Null when there is none.
	 */
	static Map<String, Object> chromeUpdate(Object socket)
	{
		java.util.List<java.nio.file.Path> places = new java.util.ArrayList<>();
		if(socket != null)
		{
			java.nio.file.Path dir = java.nio.file.Paths.get(String.valueOf(socket)).getParent();
			if(dir != null)
			{
				places.add(dir.resolve("chrome-update.json"));
			}
		}
		places.add(java.nio.file.Paths.get("/etc/wse-url-overlay/chrome-update.json"));
		for(java.nio.file.Path f: places)
		{
			try
			{
				if(java.nio.file.Files.isRegularFile(f))
				{
					return com.wowza.wms.plugin.urloverlay.capture.Json.object(java.nio.file.Files.readString(f, java.nio.charset.StandardCharsets.UTF_8));
				}
			}
			catch(java.io.IOException | RuntimeException e)
			{
				// unreadable: shown as no update information
			}
		}
		return null;
	}

	@Override
	public boolean loadObject()
	{
		body.clear();
		body.put("version", ReleaseInfo.getVersion());
		body.put("wseVersion", com.wowza.wms.server.ReleaseInfo.getVersion());
		body.put("os", System.getProperty("os.name"));
		// for the Template step's load estimate
		body.put("cpus", Runtime.getRuntime().availableProcessors());
		body.put("accessMode", UrlOverlayServer.accessMode());
		// the page goes read only when this user may not change anything
		String denied = WriteAccess.refusal(getApplication(), restletUser);
		Map<String, Object> write = new LinkedHashMap<>();
		write.put("allowed", denied == null);
		write.put("reason", denied);
		write.put("user", restletUser == null ? null : restletUser.getName());
		body.put("write", write);
		// where the page plays HLS from: the same host name, one of these ports
		body.put("streamingPorts", streamingPorts(com.wowza.wms.vhost.VHostSingleton.getInstance("_defaultVHost_")));
		// Chrome runs in the capture service; what it reports, or why it cannot be reached
		Map<String, Object> svc = CaptureClient.get().serviceStatus();
		boolean running = Boolean.TRUE.equals(svc.get("running"));
		Map<String, Object> service = new LinkedHashMap<>();
		service.put("running", running);
		service.put("message", svc.get("message"));
		service.put("socket", svc.get("socket"));
		service.put("version", svc.get("version"));
		service.put("user", svc.get("user"));
		body.put("service", service);
		body.put("chrome", running ? svc.get("chrome") : Map.of("found", false));
		body.put("chromedriver", running ? svc.get("chromedriver") : Map.of("found", false));
		body.put("chromeUpdate", chromeUpdate(svc.get("socket")));
		Map<String, Object> captures = new LinkedHashMap<>();
		captures.put("active", running ? svc.get("active") : 0);
		captures.put("limit", running ? svc.get("limit") : 0);
		captures.put("maxBrowserMemoryMB", svc.get("maxBrowserMemoryMB"));
		// pages in use by a stream, for the header: how many have a picture, how many cannot get one
		int capturing = 0, failing = 0;
		for(WebPageCapture c: WebPageCapture.POOL.all())
		{
			if (WebPageCapture.POOL.users(c) == 0)
				continue;
			Object state = c.status().get("state");
			if ("running".equals(state) && c.lastCaptureAt() != null)
				capturing++;
			else if ("blocked".equals(state) || "error".equals(state) || WebPageCapture.SERVICE_NOT_RUNNING.equals(state))
				failing++;
		}
		captures.put("pagesCapturing", capturing);
		captures.put("pagesFailing", failing);
		body.put("captures", captures);
		AddressRules rules = CaptureClient.get().rules();
		Map<String, Object> egress = new LinkedHashMap<>();
		egress.put("allowPrivate", rules.allowPrivate());
		egress.put("allowHosts", rules.allowEntries());
		egress.put("blockHosts", rules.blockEntries());
		egress.put("blockChromeServices", rules.blockChromeServices());
		egress.put("ports", rules.allowedPorts());
		egress.put("chromeServiceBlocks", running ? svc.get("chromeServiceBlocks") : 0);
		egress.put("recentBlocks", running ? svc.get("recentBlocks") : java.util.List.of());
		body.put("egress", egress);
		// the service never runs Chrome without its sandbox; sandboxProblem says why one did not start
		body.put("sandbox", "on");
		body.put("sandboxProblem", running ? svc.get("sandboxProblem") : null);
		return true;
	}
}
