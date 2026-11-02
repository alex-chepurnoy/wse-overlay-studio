/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.templates;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Plans a template assignment without touching WSE: which file to write, what the application's template list
 * becomes and what the template can do. The REST resource applies the plan through WSE's own Application.xml
 * writer, so a dry run and a real run come from the same code.
 */
public final class TemplateOps
{
	public static final String TRANSCODER = "transcoder";

	private TemplateOps()
	{
	}

	public record Plan(String scope, String template, String stream, String writeFile, String copyFrom, TemplateLists.Change list,
			String liveStreamTranscoderBefore, String liveStreamTranscoderAfter, TemplateValidator.Result validation, String currentTemplate,
			boolean currentIsVif)
	{
		public boolean changesApp()
		{
			return list.changed() || !liveStreamTranscoderBefore.equals(liveStreamTranscoderAfter);
		}

		public Map<String, Object> toMap()
		{
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("scope", scope);
			m.put("template", template);
			if(stream != null)
			{
				m.put("stream", stream);
			}
			m.put("writeFile", writeFile);
			m.put("copyFrom", copyFrom);
			m.put("templatesBefore", list.before());
			m.put("templatesAfter", list.after());
			m.put("replacedFallback", list.replacedFallback());
			m.put("liveStreamTranscoderBefore", liveStreamTranscoderBefore);
			m.put("liveStreamTranscoderAfter", liveStreamTranscoderAfter);
			// WSE reads an application's Transcoder settings when the application starts
			m.put("restartRequired", changesApp());
			m.put("validation", validation.toMap());
			// what the stream (stream scope) or the application's other streams (app scope) run now
			m.put("currentTemplate", currentTemplate);
			m.put("currentIsVif", currentIsVif);
			// switching the transcoder on for the application makes every stream in it start transcoding
			m.put("transcoderTurnsOn", liveStreamTranscoderBefore.isEmpty() && !liveStreamTranscoderAfter.isEmpty());
			m.put("overlay", validation.overlay());
			return m;
		}
	}

	/**
	 * @param templatesDir the application's transcoder template directory
	 * @param templatesInUse the application's current Transcoder/Templates value
	 * @param liveStreamTranscoder the application's current Transcoder/LiveStreamTranscoder value
	 * @param scope "app" (template becomes the fallback) or "stream" (stream gets its own copy, stream.xml)
	 */
	public static Plan plan(Path templatesDir, String templatesInUse, String liveStreamTranscoder, String scope, String template, String stream)
			throws TemplateFiles.Refused, IOException
	{
		String t = TemplateFiles.safeName(template);
		Path source = templatesDir.resolve(t + ".xml");
		if(!Files.isRegularFile(source))
		{
			throw new TemplateFiles.Refused(404, "No template '" + t + "' in the application's template directory");
		}
		TemplateValidator.Result v;
		try(InputStream in = Files.newInputStream(source))
		{
			v = TemplateValidator.validate(in);
		}
		if(!v.usable())
		{
			throw new TemplateFiles.Refused(422, "Template '" + t + "' cannot carry an overlay: " + v.problems().get(0).message());
		}
		String lst = liveStreamTranscoder == null ? "" : liveStreamTranscoder.trim();
		String lstAfter = lst;
		if(lst.isEmpty())
		{
			lstAfter = TRANSCODER;
		}
		else if(TemplateLists.entries(lst).stream().noneMatch(s -> s.equalsIgnoreCase(TRANSCODER)))
		{
			// other values (transrate, a custom class) are not changed behind the user's back
			throw new TemplateFiles.Refused(409, "LiveStreamTranscoder is '" + lst + "'; it must include 'transcoder' for an overlay.");
		}

		if("app".equals(scope))
		{
			// streams without their own template run the fallback; taking it over from VIF would put the overlay on VIF's streams
			String fallback = TemplateLists.entries(templatesInUse).stream().filter(e -> !TemplateLists.isMatch(e)).findFirst().orElse(null);
			if(fallback != null && !fallback.equalsIgnoreCase(TemplateLists.withXml(t)) && isVif(templatesDir.resolve(fallback)))
			{
				throw new TemplateFiles.Refused(409, "The application's fallback " + fallback
						+ " is a Video Intelligence template. VIF and Overlay Studio are not mixed: assign per stream instead.");
			}
			String cur = fallback == null || !Files.isRegularFile(templatesDir.resolve(fallback)) ? null : fallback;
			return new Plan(scope, t, null, null, null, TemplateLists.forApp(templatesInUse, t), lst, lstAfter, v, cur, cur != null && isVif(templatesDir.resolve(cur)));
		}
		if("stream".equals(scope))
		{
			String s = TemplateFiles.safeName(stream);
			String writeFile = null;
			String copyFrom = null;
			if(!s.equals(t))
			{
				Path target = templatesDir.resolve(s + ".xml");
				if(Files.exists(target) && !TemplateFiles.isManaged(target))
				{
					throw new TemplateFiles.Refused(409, s + ".xml exists and is not managed by Overlay Studio; assign it by name instead.");
				}
				writeFile = s + ".xml";
				copyFrom = t + ".xml";
			}
			String cur = TemplateLists.resolve(templatesInUse, s, n -> Files.isRegularFile(templatesDir.resolve(n)));
			return new Plan(scope, t, s, writeFile, copyFrom, TemplateLists.forStream(templatesInUse), lst, lstAfter, v, cur,
					cur != null && isVif(templatesDir.resolve(cur)));
		}
		throw new TemplateFiles.Refused(400, "scope must be 'app' or 'stream'");
	}

	static boolean isVif(Path file) throws IOException
	{
		if(!Files.isRegularFile(file))
		{
			return false;
		}
		try(InputStream in = Files.newInputStream(file))
		{
			return TemplateValidator.validate(in).vif();
		}
	}

	/** Writes the plan's template file, if it has one. The Application.xml change is the caller's. */
	public static void writeFiles(Path templatesDir, Plan plan) throws TemplateFiles.Refused, IOException
	{
		if(plan.writeFile() != null)
		{
			TemplateFiles.copyForStream(templatesDir, plan.template(), plan.stream(), true);
		}
	}
}
