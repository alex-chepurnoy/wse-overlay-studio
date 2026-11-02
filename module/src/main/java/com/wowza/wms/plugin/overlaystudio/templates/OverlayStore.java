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
	public static Map<String, Object> write(Path dir, String name, Map<String, Object> body, Function<String, String> urlProblem)
			throws TemplateFiles.Refused, IOException
	{
		return write(dir, name, body, urlProblem, f -> 0L);
	}

	/** Layers an overlay may hold (plan 3.10). */
	public static final int MAX_LAYERS = 64;
	/** The most an overlay file may be, in bytes. */
	public static final int MAX_FILE_BYTES = 1024 * 1024;
	/** Decoded picture pixels an overlay's image layers may add up to: about 128 MB. */
	public static final long MAX_IMAGE_PIXELS = 32_000_000L;
	/** The most an overlay-level w or h may be. */
	public static final int MAX_CANVAS = 8192;

	/**
	 * As above, with imagePixels giving the pixels of an imageFile (0 when it is not there yet), for the overlay's
	 * picture budget. Every refusal of a layer names its id and field.
	 */
	@SuppressWarnings("unchecked")
	public static Map<String, Object> write(Path dir, String name, Map<String, Object> body, Function<String, String> urlProblem, Function<String, Long> imagePixels)
			throws TemplateFiles.Refused, IOException
	{
		Path f = file(dir, name);
		String[] top = overlayProblem(body);
		if(top != null)
		{
			throw new TemplateFiles.Refused(400, top[1], null, top[0]);
		}
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
			throw new TemplateFiles.Refused(400, "An overlay needs an objects list", null, "objects");
		}
		if(((List<Object>)objs).size() > MAX_LAYERS)
		{
			throw new TemplateFiles.Refused(400, "An overlay holds at most " + MAX_LAYERS + " layers; this one has " + ((List<Object>)objs).size(), null, "objects");
		}
		Set<String> ids = new HashSet<>();
		Set<String> files = new HashSet<>();
		long pixels = 0;
		List<Map<String, Object>> clean = new ArrayList<>();
		for(Object o: (List<Object>)objs)
		{
			if(!(o instanceof Map))
			{
				throw new TemplateFiles.Refused(400, "Each object must be a JSON object", null, "objects");
			}
			Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>)o);
			String id = m.get("id") == null ? null : m.get("id").toString();
			String[] bad = com.wowza.wms.plugin.overlaystudio.rest.ObjectConfig.layerProblem(m);
			if(bad != null)
			{
				throw new TemplateFiles.Refused(400, "Object " + id + ": " + bad[1], id, bad[0]);
			}
			if(!ids.add(id))
			{
				throw new TemplateFiles.Refused(400, "Object " + id + ": two layers have this id", id, "id");
			}
			if("webPage".equals(m.get("type")))
			{
				String err = urlProblem.apply(m.get("url") == null ? null : m.get("url").toString());
				if(err != null)
				{
					throw new TemplateFiles.Refused(400, "Object " + id + ": " + err, id, "url");
				}
			}
			// each file is decoded once however many layers show it
			if("image".equals(m.get("type")) && m.get("imageFile") != null && files.add(m.get("imageFile").toString()))
			{
				pixels += imagePixels.apply(m.get("imageFile").toString());
				if(pixels > MAX_IMAGE_PIXELS)
				{
					throw new TemplateFiles.Refused(400, "Object " + id + ": the overlay's pictures add up to more than " + MAX_IMAGE_PIXELS / 1_000_000
							+ " million pixels", id, "imageFile");
				}
			}
			clean.add(m);
		}
		out.put("objects", clean);
		byte[] bytes = JSON.writeValueAsBytes(out);
		if(bytes.length > MAX_FILE_BYTES)
		{
			throw new TemplateFiles.Refused(400, "The overlay is " + bytes.length / 1024 + " KB; the most is " + MAX_FILE_BYTES / 1024 + " KB", null, "objects");
		}
		Files.createDirectories(dir);
		// written beside the file and moved into place, so a stream loading it never reads half of it
		Path tmp = Files.createTempFile(dir, "." + f.getFileName(), ".tmp");
		try
		{
			Files.write(tmp, bytes);
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

	/** Why the overlay's own fields are refused, as {field, message}, or null: its style defaults, timing knobs and canvas. */
	static String[] overlayProblem(Map<String, Object> body)
	{
		String[] p = com.wowza.wms.plugin.overlaystudio.rest.ObjectConfig.styleProblem(body);
		if(p != null)
		{
			return p;
		}
		Object rr = body.get("refreshRate");
		if(rr != null && !(rr instanceof Number n && n.doubleValue() >= 0 && n.doubleValue() <= 3600))
		{
			return new String[] { "refreshRate", "refreshRate must be seconds from 0 to 3600" };
		}
		Object md = body.get("maxDrawingTime");
		if(md != null && !(md instanceof Number n && n.doubleValue() >= 0 && n.doubleValue() <= 60_000))
		{
			return new String[] { "maxDrawingTime", "maxDrawingTime must be milliseconds from 0 to 60000" };
		}
		for(String b: new String[] { "threadDrawing", "even_coords" })
		{
			if(body.get(b) != null && !(body.get(b) instanceof Boolean))
			{
				return new String[] { b, b + " must be true or false" };
			}
		}
		for(String k: new String[] { "w", "h" })
		{
			Object v = body.get(k);
			if(v != null && !(v instanceof Number n && n.doubleValue() >= 0 && n.doubleValue() <= MAX_CANVAS))
			{
				return new String[] { k, k + " must be from 0 to " + MAX_CANVAS };
			}
		}
		return null;
	}

	/**
	 * What is saved but worth saying (plan 3.10): a font this server does not have, as {id, field, message}; id is
	 * null for the overlay's own default. The text then draws in a fallback.
	 */
	@SuppressWarnings("unchecked")
	public static List<Map<String, Object>> warnings(Map<String, Object> saved)
	{
		List<Map<String, Object>> out = new ArrayList<>();
		fontWarning(out, null, saved.get("font"));
		Object objs = saved.get("objects");
		if(objs instanceof List)
		{
			for(Object o: (List<Object>)objs)
			{
				Map<String, Object> m = (Map<String, Object>)o;
				if("string".equals(m.get("type")))
				{
					fontWarning(out, (String)m.get("id"), m.get("font"));
				}
			}
		}
		return out;
	}

	private static void fontWarning(List<Map<String, Object>> out, String id, Object font)
	{
		if(font instanceof String f && !com.wowza.wms.plugin.overlaystudio.Fonts.known(f))
		{
			Map<String, Object> w = new LinkedHashMap<>();
			w.put("id", id);
			w.put("field", "font");
			w.put("message", "Font '" + f + "' is not installed on this server; the text draws in " + com.wowza.wms.plugin.overlaystudio.Fonts.DEFAULT_FAMILY + " until it is");
			out.add(w);
		}
	}

	/** The layer ids an overlay body holds. */
	@SuppressWarnings("unchecked")
	public static Set<String> ids(Map<String, Object> overlay)
	{
		Set<String> out = new java.util.LinkedHashSet<>();
		if(overlay != null && overlay.get("objects") instanceof List)
		{
			for(Object o: (List<Object>)overlay.get("objects"))
			{
				if(o instanceof Map && ((Map<String, Object>)o).get("id") != null)
				{
					out.add(((Map<String, Object>)o).get("id").toString());
				}
			}
		}
		return out;
	}

	/**
	 * The renamed map a save may carry (plan 3.6), checked against the ids the overlay had and the ones it now has:
	 * every old id was a layer and is gone, every new one is a layer that was not there, no two old ids go to one new
	 * one, and none chains. Never stored.
	 */
	public static Map<String, String> renamed(Object raw, Set<String> before, Set<String> after) throws TemplateFiles.Refused
	{
		Map<String, String> out = new LinkedHashMap<>();
		if(raw == null)
		{
			return out;
		}
		if(!(raw instanceof Map<?, ?> m))
		{
			throw new TemplateFiles.Refused(400, "renamed must map old layer ids to new ones", null, "renamed");
		}
		Set<String> targets = new HashSet<>();
		for(Map.Entry<?, ?> e: m.entrySet())
		{
			String from = String.valueOf(e.getKey());
			if(!(e.getValue() instanceof String to))
			{
				throw new TemplateFiles.Refused(400, "renamed: " + from + " must map to a layer id", from, "renamed");
			}
			if(!before.contains(from))
			{
				throw new TemplateFiles.Refused(400, "renamed: the overlay had no layer '" + from + "'", from, "renamed");
			}
			if(after.contains(from))
			{
				throw new TemplateFiles.Refused(400, "renamed: layer '" + from + "' is still on the overlay", from, "renamed");
			}
			if(!after.contains(to))
			{
				throw new TemplateFiles.Refused(400, "renamed: the overlay has no layer '" + to + "'", from, "renamed");
			}
			if(before.contains(to) || m.containsKey(to))
			{
				throw new TemplateFiles.Refused(400, "renamed: '" + to + "' is an id the overlay already had; renames may not chain", from, "renamed");
			}
			if(!targets.add(to))
			{
				throw new TemplateFiles.Refused(400, "renamed: two layers are renamed to '" + to + "'", from, "renamed");
			}
			out.put(from, to);
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
