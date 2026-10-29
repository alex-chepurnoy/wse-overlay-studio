/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.rest;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import com.wowza.util.SystemUtils;
import com.wowza.wms.plugin.urloverlay.UrlOverlayLog;
import com.wowza.wms.plugin.urloverlay.UrlOverlayModule;
import com.wowza.wms.plugin.urloverlay.templates.TemplateOps;
import com.wowza.wms.rest.WMSConfig;
import com.wowza.wms.rest.vhosts.VHostsConfig;
import com.wowza.wms.rest.vhosts.applications.transcoder.TranscoderAppConfig;

/**
 * Assigns a template to an application or to one stream.
 * GET ?template=&amp;scope=&amp;stream= is a dry run; POST {"template", "scope", "stream"} applies it. Application.xml is
 * written through WSE's TranscoderAppConfig, the class behind the Manager's Transcoder page.
 */
@WMSConfig(restLocation = "/v1/{server}/plugin/urloverlay/vhosts/{vhostName}/applications/{appName}/templates/assign", restVerbs = "GET,POST", xmlFilename = "")
public class TemplateAssignConfig extends VHostResourceBase
{
	@Override
	protected Object doGet(Map<String, String> path, Map<String, String> query) throws Exception
	{
		TranscoderAppConfig app = load(path);
		Map<String, Object> m = plan(path, app, query.get("scope"), query.get("template"), query.get("stream")).toMap();
		m.put("dryRun", true);
		// who else is affected: the streams publishing in this application now
		m.put("publishing", AppStreamsConfig.publishing(path.get("vhostName"), path.get("appName")));
		return m;
	}

	@Override
	protected Object doPost(Map<String, String> path, Map<String, Object> body) throws Exception
	{
		TranscoderAppConfig app = load(path);
		TemplateOps.Plan plan = plan(path, app, str(body, "scope"), str(body, "template"), str(body, "stream"));
		TemplateOps.writeFiles(templatesDir(path, app), plan);
		if(plan.changesApp())
		{
			app.setTemplatesInUse(plan.list().after());
			app.setLiveStreamTranscoder(plan.liveStreamTranscoderAfter());
			app.addToSaveFieldList("templatesInUse");
			app.addToSaveFieldList("liveStreamTranscoder");
			// ConfigBase marks a field "missing" when its text() node is absent, which an empty element such as a new
			// application's <LiveStreamTranscoder></LiveStreamTranscoder> is, and never saves a missing field
			// (ConfigBase.saveObjectToFile).
			app.missingVariables.remove("templatesInUse");
			app.missingVariables.remove("liveStreamTranscoder");
			app.saveObject();
		}
		UrlOverlayLog.change(this, "assigned template " + plan.template() + " to " + path.get("vhostName") + "/" + path.get("appName")
				+ ("stream".equals(plan.scope()) ? " stream " + plan.stream() : "") + (plan.writeFile() == null ? "" : ", wrote " + plan.writeFile())
				+ ", Templates '" + plan.list().before() + "' -> '" + plan.list().after() + "'"
				+ (plan.changesApp() ? ", application restart required" : ", Application.xml unchanged"));
		Map<String, Object> m = plan.toMap();
		m.put("success", true);
		if(plan.writeFile() != null)
		{
			m.put("live", UrlOverlayModule.reloadTemplateFile(path.get("vhostName"), templatesDir(path, app).resolve(plan.writeFile())));
		}
		if("stream".equals(plan.scope()))
		{
			// a pushed stream builds its transcoder once; the page offers to restart it (StreamRestartConfig)
			m.put("restartNeeded", UrlOverlayModule.outputsChanged(path.get("vhostName"), path.get("appName"), plan.stream()));
		}
		return m;
	}

	static TranscoderAppConfig load(Map<String, String> path) throws Exception
	{
		TranscoderAppConfig app = new TranscoderAppConfig(path.get("vhostName"), path.get("appName"));
		if(!app.loadObject())
		{
			throw new com.wowza.wms.plugin.urloverlay.templates.TemplateFiles.Refused(404, "No application '" + path.get("appName") + "'");
		}
		return app;
	}

	private static TemplateOps.Plan plan(Map<String, String> path, TranscoderAppConfig app, String scope, String template, String stream) throws Exception
	{
		return TemplateOps.plan(templatesDir(path, app), app.getTemplatesInUse(), app.getLiveStreamTranscoder(), scope, template, stream);
	}

	/** The application's TemplateDir, expanded the way TranscoderAppConfig.saveObject expands it. */
	static Path templatesDir(Map<String, String> path, TranscoderAppConfig app)
	{
		Map<String, String> vars = new HashMap<>();
		vars.put("com.wowza.wms.context.VHost", path.get("vhostName"));
		vars.put("com.wowza.wms.context.VHostConfigHome", VHostsConfig.getVHostDir(path.get("vhostName")));
		vars.put("com.wowza.wms.context.Application", path.get("appName"));
		String dir = app.getTemplateDir();
		if(dir == null || dir.isBlank())
		{
			return Paths.get(VHostsConfig.getVHostDir(path.get("vhostName")), "transcoder", "templates");
		}
		return Paths.get(SystemUtils.expandEnvironmentVariables(dir, vars));
	}
}
