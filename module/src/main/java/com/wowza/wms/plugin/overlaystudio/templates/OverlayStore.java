/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.templates;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Named overlays of an application: the pages, their placement and timing, saved once and used
 * by every stream whose transcoder template names the overlay. One file per overlay in the application's folder under
 * conf.modules/overlaystudio/ (ModuleFiles).
 * Only the overlay's own fields are kept; which encodes carry it belongs to the template.
 */
public final class OverlayStore
{
	/**
	 * Overlay-level fields a named overlay keeps, its style defaults included (objects fall back on them).
	 * overlaySource and overlayEncodes come from the template.
	 */
	static final List<String> OVERLAY_KEYS = List.of("refreshRate", "threadDrawing", "maxDrawingTime", "even_coords", "opacity", "overlayIndex",
			"stroke", "color", "fillColor", "shadowColor", "shadowOffset", "font", "fontStyle", "fontSize");
	private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

	private OverlayStore()
	{
	}

	public static Path dir(Path vhostDir, String app)
	{
		return ModuleFiles.appDir(vhostDir, app);
	}

	public static List<String> list(Path dir) throws IOException
	{
		List<String> out = new ArrayList<>();
		if(!Files.isDirectory(dir))
		{
			return out;
		}
		try(Stream<Path> s = Files.list(dir))
		{
			s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).map(n -> n.substring(0, n.length() - 5)).sorted().forEach(out::add);
		}
		return out;
	}

	public static Path file(Path dir, String name) throws TemplateFiles.Refused
	{
		return dir.resolve(TemplateFiles.safeName(name) + ".json");
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> read(Path dir, String name) throws TemplateFiles.Refused, IOException
	{
		Path f = file(dir, name);
		if(!Files.isRegularFile(f))
		{
			throw new TemplateFiles.Refused(404, "No overlay '" + name + "'");
		}
		return JSON.readValue(f.toFile(), Map.class);
	}

	/**
	 * Checks and writes an overlay. Keeps the overlay-level fields in OVERLAY_KEYS and the objects; each object needs a
	 * unique id and a type, and a webPage object a url the urlProblem check accepts.
	 *
	 * @param urlProblem returns why a url may not be captured, or null
	 * @return what was written
	 */
	@SuppressWarnings("unchecked")
	public static Map<String, Object> write(Path dir, String name, Map<String, Object> body, Function<String, String> urlProblem)
			throws TemplateFiles.Refused, IOException
	{
		Path f = file(dir, name);
		Map<String, Object> out = new LinkedHashMap<>();
		for(String k: OVERLAY_KEYS)
		{
			if(body.containsKey(k))
			{
				out.put(k, body.get(k));
			}
		}
		Object objs = body.get("objects");
		if(!(objs instanceof List))
		{
			throw new TemplateFiles.Refused(400, "An overlay needs an objects list");
		}
		Set<String> ids = new HashSet<>();
		List<Map<String, Object>> clean = new ArrayList<>();
		for(Object o: (List<Object>)objs)
		{
			if(!(o instanceof Map))
			{
				throw new TemplateFiles.Refused(400, "Each object must be a JSON object");
			}
			Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>)o);
			Object id = m.get("id");
			if(id == null || id.toString().isBlank() || !ids.add(id.toString()))
			{
				throw new TemplateFiles.Refused(400, "Each object needs a unique id");
			}
			if(m.get("type") == null)
			{
				throw new TemplateFiles.Refused(400, "Object " + id + " has no type");
			}
			for(Map.Entry<String, String> r: com.wowza.wms.plugin.overlaystudio.rest.ObjectConfig.REMOVED.entrySet())
			{
				if(m.containsKey(r.getKey()))
				{
					throw new TemplateFiles.Refused(400, "Object " + id + ": " + r.getKey() + " was removed in 2.0; use " + r.getValue());
				}
			}
			if("string".equals(m.get("type")))
			{
				String[] text = com.wowza.wms.plugin.overlaystudio.rest.ObjectConfig.textProblem(m);
				if(text != null)
				{
					throw new TemplateFiles.Refused(400, "Object " + id + ": " + text[1]);
				}
			}
			String[] timing = com.wowza.wms.plugin.overlaystudio.rest.ObjectConfig.timingProblem(m.get("showFrom"), m.get("hideAt"), m.get("blinkSeconds"), m.get("fadeIn"), m.get("fadeOut"));
			if(timing != null)
			{
				throw new TemplateFiles.Refused(400, "Object " + id + ": " + timing[1]);
			}
			if("webPage".equals(m.get("type")))
			{
				String err = urlProblem.apply(m.get("url") == null ? null : m.get("url").toString());
				if(err != null)
				{
					throw new TemplateFiles.Refused(400, "Object " + id + ": " + err);
				}
			}
			clean.add(m);
		}
		out.put("objects", clean);
		Files.createDirectories(dir);
		// written beside the file and moved into place, so a stream loading it never reads half of it
		Path tmp = Files.createTempFile(dir, "." + f.getFileName(), ".tmp");
		try
		{
			JSON.writeValue(tmp.toFile(), out);
			try
			{
				Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			}
			catch(java.nio.file.AtomicMoveNotSupportedException e)
			{
				Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		}
		finally
		{
			Files.deleteIfExists(tmp);
		}
		return out;
	}

	public static void delete(Path dir, String name) throws TemplateFiles.Refused, IOException
	{
		Path f = file(dir, name);
		if(!Files.deleteIfExists(f))
		{
			throw new TemplateFiles.Refused(404, "No overlay '" + name + "'");
		}
	}
}
