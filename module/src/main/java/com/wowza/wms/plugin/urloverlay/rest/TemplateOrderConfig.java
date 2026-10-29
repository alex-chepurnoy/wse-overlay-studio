/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.rest;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wowza.wms.plugin.urloverlay.UrlOverlayLog;
import com.wowza.wms.plugin.urloverlay.templates.TemplateFiles;
import com.wowza.wms.plugin.urloverlay.templates.TemplateLists;
import com.wowza.wms.rest.WMSConfig;
import com.wowza.wms.rest.vhosts.applications.transcoder.TranscoderAppConfig;

/**
 * The application's template order: Application.xml Transcoder/Templates, the
 * comma-separated list WSE tries in order for each stream, taking the first file that exists
 * (LiveStreamTranscoder.init). WSEM offers "match the stream name" plus one fallback; this edits the whole list.
 * GET reads it, with whether each entry exists; POST {"templates": [...]} saves it the way the assign step does
 * (TranscoderAppConfig). WSE reads the list when the application starts, so a change asks for an application restart.
 */
@WMSConfig(restLocation = "/v1/{server}/plugin/urloverlay/vhosts/{vhostName}/applications/{appName}/templates/order", restVerbs = "GET,POST", xmlFilename = "")
public class TemplateOrderConfig extends VHostResourceBase
{
	public static final int MAX_ENTRIES = 12;

	@Override
	protected Object doGet(Map<String, String> path, Map<String, String> query) throws Exception
	{
		TranscoderAppConfig app = TemplateAssignConfig.load(path);
		return describe(TemplateAssignConfig.templatesDir(path, app), app.getTemplatesInUse());
	}

	@Override
	@SuppressWarnings("unchecked")
	protected Object doPost(Map<String, String> path, Map<String, Object> body) throws Exception
	{
		if(!(body.get("templates") instanceof List<?> raw))
		{
			throw new TemplateFiles.Refused(400, "templates must be a list");
		}
		List<String> list = check((List<Object>)raw);
		TranscoderAppConfig app = TemplateAssignConfig.load(path);
		String before = app.getTemplatesInUse();
		String after = String.join(",", list);
		Map<String, Object> m = describe(TemplateAssignConfig.templatesDir(path, app), after);
		if(!after.equals(before))
		{
			// a copy of Application.xml as it was, the way adding the module keeps one
			Path xml = AppModuleConfig.applicationXml(path.get("vhostName"), path.get("appName"));
			Path backup = AppModuleConfig.backupOf(xml);
			Files.write(backup, Files.readAllBytes(xml), StandardOpenOption.CREATE_NEW);
			m.put("backup", backup.toString());
			app.setTemplatesInUse(after);
			app.addToSaveFieldList("templatesInUse");
			// an empty element reads as missing and is never saved (see TemplateAssignConfig)
			app.missingVariables.remove("templatesInUse");
			app.saveObject();
			UrlOverlayLog.change(this, "template order of " + path.get("vhostName") + "/" + path.get("appName") + ": '" + before + "' -> '" + after + "', backup " + backup.getFileName());
		}
		m.put("success", true);
		m.put("before", before);
		m.put("restartRequired", !after.equals(before));
		return m;
	}

	/** The entries as given, each a template file name (".xml" added) or ${SourceStreamName}.xml; refused otherwise. */
	public static List<String> check(List<Object> raw) throws TemplateFiles.Refused
	{
		if(raw.isEmpty())
		{
			throw new TemplateFiles.Refused(400, "The list needs at least one template; without one WSE transcodes nothing.");
		}
		if(raw.size() > MAX_ENTRIES)
		{
			throw new TemplateFiles.Refused(400, "At most " + MAX_ENTRIES + " entries.");
		}
		List<String> out = new ArrayList<>();
		for(Object o: raw)
		{
			String e = String.valueOf(o).trim();
			String entry = e.equalsIgnoreCase(TemplateLists.MATCH_STREAM) ? TemplateLists.MATCH_STREAM : TemplateFiles.safeName(e) + ".xml";
			if(out.stream().anyMatch(x -> x.equalsIgnoreCase(entry)))
			{
				throw new TemplateFiles.Refused(400, entry + " is in the list twice.");
			}
			out.add(entry);
		}
		return out;
	}

	static Map<String, Object> describe(Path dir, String list)
	{
		List<Map<String, Object>> entries = new ArrayList<>();
		for(String e: TemplateLists.entries(list))
		{
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("entry", e);
			boolean match = e.equalsIgnoreCase(TemplateLists.MATCH_STREAM);
			m.put("matchStream", match);
			m.put("exists", match || new File(dir.toFile(), e).isFile());
			entries.add(m);
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("templates", list);
		m.put("entries", entries);
		return m;
	}
}
