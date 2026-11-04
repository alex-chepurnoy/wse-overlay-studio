/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.templates;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Checks whether a transcoder template can carry an overlay. Reads the XML itself (JDK DOM, no WSE classes).
 * Errors make a template unusable for overlays; warnings point at settings that limit it.
 */
public final class TemplateValidator
{
	public static final String MANAGED_MARKER = "Managed by Overlay Studio";
	/** The encode every VIF template uses to get frames (vif.xml, vif-cpu.xml, vif-gpu.xml, vif-gpu-eva.xml). */
	public static final String VIF_ENCODE = "videoIntelligence";
	/** Overlays a template carries, in properties overlayStudio1 to overlayStudio4 (plan 3.9). */
	public static final int SLOTS = 4;
	/** Template properties (Transcode/Properties) naming the overlay in slot 1 and the encodes that carry it. */
	// literal, as tests/ui/labels_test.py reads them; propOverlay(1) and propEncodes(1) give the same names
	public static final String PROP_OVERLAY = "overlayStudio1";
	public static final String PROP_ENCODES = "overlayStudioEncodes1";

	/** The property naming the overlay in this slot, 1 to SLOTS. */
	public static String propOverlay(int slot)
	{
		return "overlayStudio" + slot;
	}

	/** The property listing the encodes that carry this slot's overlay; none listed: drawn on the source, so every encode. */
	public static String propEncodes(int slot)
	{
		return "overlayStudioEncodes" + slot;
	}

	/**
	 * One overlay a template carries: its slot, 1 to SLOTS (property order, which is stacking order: a higher slot
	 * draws on top), its name and the encodes that carry it (none: drawn on the source, so every encode gets it).
	 */
	public record Slot(int slot, String name, List<String> encodes)
	{
		public Map<String, Object> toMap()
		{
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", name);
			m.put("encodes", encodes);
			m.put("slot", slot);
			return m;
		}
	}

	/** The overlays list for a response: {name, encodes, slot} in slot order. */
	public static List<Map<String, Object>> toMaps(List<Slot> slots)
	{
		List<Map<String, Object>> out = new ArrayList<>();
		for(Slot s: slots)
		{
			out.add(s.toMap());
		}
		return out;
	}

	// implementation names present in WSE's transcoder source; others may still work
	static final Set<String> DECODERS = Set.of("default", "nvcuvid", "nvcuvid_eva", "quicksync", "netint");
	static final Set<String> SCALERS = Set.of("default", "cuda", "cuda_eva");
	static final Set<String> ENCODERS = Set.of("default", "x264", "nvenc", "nvenc_eva", "quicksync", "netint");

	private TemplateValidator()
	{
	}

	public record Problem(String level, String key, String message)
	{
	}

	public record Result(boolean usable, boolean managed, boolean vif, String description, String decoder, String scaler, boolean dynamicOverlays,
			List<Map<String, Object>> encodes, List<Problem> problems, String overlay, List<String> overlayEncodes, boolean deinterlace, String gpuId,
			List<Map<String, Object>> groups, String hardware, List<Map<String, Object>> images, String minFrameRate, List<Slot> overlays)
	{
		/** The overlay in slot `slot`, or null. */
		public Slot slot(int slot)
		{
			return overlays.stream().filter(x -> x.slot() == slot).findFirst().orElse(null);
		}

		public Map<String, Object> toMap()
		{
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("usable", usable);
			m.put("managed", managed);
			m.put("vif", vif);
			m.put("overlays", toMaps(overlays));
			m.put("description", description);
			m.put("decoder", decoder);
			m.put("scaler", scaler);
			m.put("dynamicOverlays", dynamicOverlays);
			m.put("deinterlace", deinterlace);
			m.put("gpuId", gpuId);
			m.put("groups", groups);
			m.put("hardware", hardware);
			m.put("images", images);
			m.put("minFrameRate", minFrameRate);
			m.put("encodes", encodes);
			List<Map<String, Object>> ps = new ArrayList<>();
			for(Problem p: problems)
			{
				Map<String, Object> pm = new LinkedHashMap<>();
				pm.put("level", p.level());
				pm.put("key", p.key());
				pm.put("message", p.message());
				ps.add(pm);
			}
			m.put("problems", ps);
			return m;
		}
	}

	public static Result validate(InputStream xml)
	{
		List<Problem> problems = new ArrayList<>();
		Document doc;
		try
		{
			doc = newBuilder().parse(xml);
		}
		catch(Exception e)
		{
			problems.add(new Problem("error", "xml", "Not a readable template: " + e.getMessage()));
			return new Result(false, false, false, null, null, null, false, List.of(), problems, null, List.of(), false, null, List.of(), null, List.of(), null, List.of());
		}
		Element transcode = child(doc.getDocumentElement(), "Transcode");
		if(transcode == null)
		{
			problems.add(new Problem("error", "Transcode", "No Root/Transcode element"));
			return new Result(false, false, false, null, null, null, false, List.of(), problems, null, List.of(), false, null, List.of(), null, List.of(), null, List.of());
		}
		String description = text(transcode, "Description");
		boolean managed = description != null && description.contains(MANAGED_MARKER);
		Element video = child(child(transcode, "Decode"), "Video");
		String decoder = text(video, "Implementation");
		boolean dynamic = "true".equalsIgnoreCase(trim(text(video, "EnableDynamicOverlays")));
		boolean deinterlace = "true".equalsIgnoreCase(trim(text(video, "Deinterlace")));
		String gpuId = trim(text(video, "GPUID"));
		String scaler = text(child(transcode, "Scale"), "Implementation");
		if(!dynamic)
		{
			problems.add(new Problem("error", "Decode/Video/EnableDynamicOverlays", "Must be true for an overlay."));
		}
		if(decoder != null && !DECODERS.contains(trim(decoder).toLowerCase(Locale.ROOT)))
		{
			problems.add(new Problem("warning", "Decode/Video/Implementation", "Unknown decoder '" + decoder + "'."));
		}
		if(scaler != null && !SCALERS.contains(trim(scaler).toLowerCase(Locale.ROOT)))
		{
			problems.add(new Problem("warning", "Scale/Implementation", "Unknown scaler '" + scaler + "'."));
		}

		List<Map<String, Object>> encodes = new ArrayList<>();
		int carriers = 0;
		boolean vif = false;
		Element encodesEl = child(transcode, "Encodes");
		if(encodesEl != null)
		{
			NodeList list = encodesEl.getChildNodes();
			for(int i = 0; i < list.getLength(); i++)
			{
				if(!(list.item(i) instanceof Element e) || !e.getTagName().equals("Encode"))
				{
					continue;
				}
				String name = trim(text(e, "Name"));
				vif |= VIF_ENCODE.equals(name);
				boolean enabled = "true".equalsIgnoreCase(trim(text(e, "Enable")));
				Element v = child(e, "Video");
				String codec = trim(text(v, "Codec"));
				String impl = trim(text(v, "Implementation"));
				boolean passThrough = codec != null && codec.equalsIgnoreCase("PassThru");
				boolean noVideo = codec == null || codec.equalsIgnoreCase("Disable");
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("name", name);
				m.put("enabled", enabled);
				m.put("streamName", trim(text(e, "StreamName")));
				m.put("codec", codec);
				m.put("implementation", impl);
				m.put("fitMode", trim(text(child(v, "FrameSize"), "FitMode")));
				m.put("width", trim(text(child(v, "FrameSize"), "Width")));
				m.put("height", trim(text(child(v, "FrameSize"), "Height")));
				// what the page's template builder shows and copies
				m.put("profile", trim(text(v, "Profile")));
				m.put("bitrate", trim(text(v, "Bitrate")));
				Element a = child(e, "Audio");
				m.put("audioCodec", trim(text(a, "Codec")));
				m.put("audioBitrate", trim(text(a, "Bitrate")));
				Element kf = child(v, "KeyFrameInterval");
				m.put("keyInterval", trim(text(kf, "Interval")));
				m.put("followSource", "true".equalsIgnoreCase(trim(text(kf, "FollowSource"))));
				// the Template step reads these back
				m.put("skipFrameCount", trim(text(v, "SkipFrameCount")));
				m.put("sourceRectangle", trim(text(child(v, "FrameSize"), "SourceRectangle")));
				m.put("convert8bit", "true".equalsIgnoreCase(trim(text(v, "Convert8bit"))));
				Map<String, String> params = parameters(child(v, "Parameters"));
				m.put("parameters", params);
				m.putAll(TemplateBuilder.describeRateControl(params));
				Element rs = child(a, "Resample");
				boolean resample = rs != null && "true".equalsIgnoreCase(trim(text(rs, "Enable")));
				m.put("audioChannels", resample ? trim(text(rs, "Channels")) : null);
				m.put("audioSampleRate", resample ? trim(text(rs, "SampleRate")) : null);
				m.put("captions", !"false".equalsIgnoreCase(properties(e).get("copyCEA608CaptionsFromSourceStream")));
				boolean carries = enabled && !passThrough && !noVideo;
				m.put("canCarryOverlay", carries);
				encodes.add(m);
				if(carries)
				{
					carriers++;
				}
				if(enabled && passThrough)
				{
					problems.add(new Problem("warning", "Encodes/Encode[" + name + "]/Video/Codec", "PassThru encodes cannot carry an overlay."));
				}
				if(carries && impl != null && !ENCODERS.contains(impl.toLowerCase(Locale.ROOT)))
				{
					problems.add(new Problem("warning", "Encodes/Encode[" + name + "]/Video/Implementation", "Unknown encoder '" + impl + "'."));
				}
			}
		}
		if(carriers == 0)
		{
			problems.add(new Problem("error", "Encodes", "No enabled encode that can carry an overlay (needs a video codec other than PassThru or Disable)."));
		}
		if(vif)
		{
			problems.add(new Problem("error", "Encodes/Encode[" + VIF_ENCODE + "]", "A Video Intelligence template; VIF and Overlay Studio are not mixed on one stream."));
		}
		boolean usable = problems.stream().noneMatch(p -> p.level().equals("error"));
		Map<String, String> props = properties(transcode);
		List<Slot> slots = new ArrayList<>();
		Map<String, Integer> seen = new LinkedHashMap<>();
		for(int n = 1; n <= SLOTS; n++)
		{
			String name = props.get(propOverlay(n));
			if(name == null || name.isBlank())
			{
				continue;
			}
			name = name.trim();
			List<String> on = splitList(props.get(propEncodes(n)));
			for(String e: on)
			{
				if(encodes.stream().noneMatch(m -> e.equals(m.get("name")) && Boolean.TRUE.equals(m.get("canCarryOverlay"))))
				{
					problems.add(new Problem("warning", "Transcode/Properties/" + propEncodes(n), "Encode '" + e + "' is not an enabled encode that can carry an overlay."));
				}
			}
			Integer before = seen.putIfAbsent(name, n);
			if(before != null)
			{
				problems.add(new Problem("error", "Transcode/Properties/" + propOverlay(n), "Overlay " + name + " is named in slot " + before + " and slot " + n
						+ "; a template carries each overlay once."));
				continue;
			}
			slots.add(new Slot(n, name, on));
		}
		usable = problems.stream().noneMatch(p -> p.level().equals("error"));
		Slot first = slots.stream().filter(x -> x.slot() == 1).findFirst().orElse(null);
		return new Result(usable, managed, vif, description, decoder, scaler, dynamic, encodes, problems,
				first == null ? null : first.name(), first == null ? List.of() : first.encodes(), deinterlace, gpuId,
				groups(transcode), TemplateBuilder.Hardware.of(decoder, encodes.stream().map(x -> (String)x.get("implementation")).filter(x -> x != null).findFirst().orElse(null)).name(),
				images(transcode), trim(text(video, "MinimumFrameRate")), List.copyOf(slots));
	}

	/**
	 * A parser that refuses DOCTYPE declarations, so a template cannot pull in external entities. Only the Xerces
	 * feature is used: the XMLConstants access properties are not recognized by the older Xerces on WSE's classpath.
	 */
	static DocumentBuilder newBuilder() throws ParserConfigurationException
	{
		DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
		f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		f.setExpandEntityReferences(false);
		return f.newDocumentBuilder();
	}

	/** Transcode/Properties/Property as Name to Value, the layout WSE reads (XMLUtils.loadConfigProperty). */
	static Map<String, String> properties(Element transcode)
	{
		Map<String, String> out = new LinkedHashMap<>();
		Element props = child(transcode, "Properties");
		if(props == null)
		{
			return out;
		}
		for(Node n = props.getFirstChild(); n != null; n = n.getNextSibling())
		{
			if(n instanceof Element e && e.getTagName().equals("Property"))
			{
				String name = trim(text(e, "Name"));
				if(name != null)
				{
					out.put(name, trim(text(e, "Value")));
				}
			}
		}
		return out;
	}

	/** Parameters/Parameter as Name to Value, in order; WSE reads only Name and Value (TranscoderStreamUtils). */
	static Map<String, String> parameters(Element params)
	{
		Map<String, String> out = new LinkedHashMap<>();
		if(params == null)
		{
			return out;
		}
		for(Node n = params.getFirstChild(); n != null; n = n.getNextSibling())
		{
			if(n instanceof Element e && e.getTagName().equals("Parameter"))
			{
				String name = trim(text(e, "Name"));
				if(name != null)
				{
					out.put(name, trim(text(e, "Value")));
				}
			}
		}
		return out;
	}

	/** StreamNameGroups as name, stream name and members (encode, and audio or video only from MediaListRendition). */
	static List<Map<String, Object>> groups(Element transcode)
	{
		List<Map<String, Object>> out = new ArrayList<>();
		Element groups = child(transcode, "StreamNameGroups");
		if(groups == null)
		{
			return out;
		}
		for(Node n = groups.getFirstChild(); n != null; n = n.getNextSibling())
		{
			if(!(n instanceof Element g) || !g.getTagName().equals("StreamNameGroup"))
			{
				continue;
			}
			Map<String, Object> gm = new LinkedHashMap<>();
			gm.put("name", trim(text(g, "Name")));
			gm.put("streamName", trim(text(g, "StreamName")));
			List<Map<String, String>> members = new ArrayList<>();
			Element ms = child(g, "Members");
			for(Node mn = ms == null ? null : ms.getFirstChild(); mn != null; mn = mn.getNextSibling())
			{
				if(mn instanceof Element me && me.getTagName().equals("Member"))
				{
					Element mlr = child(me, "MediaListRendition");
					String only = mlr == null ? "" : "true".equalsIgnoreCase(trim(text(mlr, "WowzaAudioOnly"))) ? "audio"
							: "true".equalsIgnoreCase(trim(text(mlr, "WowzaVideoOnly"))) ? "video" : "";
					Map<String, String> mm = new LinkedHashMap<>();
					mm.put("output", trim(text(me, "EncodeName")));
					mm.put("only", only);
					members.add(mm);
				}
			}
			gm.put("members", members);
			out.add(gm);
		}
		return out;
	}

	/** The image overlays, on the decoder (every output) and per encode, in the Template step's shape. */
	static List<Map<String, Object>> images(Element transcode)
	{
		Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
		collectImages(child(child(child(transcode, "Decode"), "Video"), "Overlays"), null, byKey);
		Element encodes = child(transcode, "Encodes");
		for(Node n = encodes == null ? null : encodes.getFirstChild(); n != null; n = n.getNextSibling())
		{
			if(n instanceof Element e && e.getTagName().equals("Encode"))
			{
				collectImages(child(child(e, "Video"), "Overlays"), trim(text(e, "Name")), byKey);
			}
		}
		return new ArrayList<>(byKey.values());
	}

	// one layer per image file and placement; the same layer on several encodes is one layer with those outputs
	@SuppressWarnings("unchecked")
	private static void collectImages(Element overlays, String encode, Map<String, Map<String, Object>> byKey)
	{
		for(Node n = overlays == null ? null : overlays.getFirstChild(); n != null; n = n.getNextSibling())
		{
			if(!(n instanceof Element o) || !o.getTagName().equals("Overlay") || "false".equalsIgnoreCase(trim(text(o, "Enable"))))
			{
				continue;
			}
			String path = trim(text(o, "ImagePath"));
			String file = path == null ? "" : path.replace("${com.wowza.wms.context.VHostConfigHome}/content/", "");
			Element loc = child(o, "Location");
			String w = trim(text(loc, "Width")), h = trim(text(loc, "Height"));
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("file", file);
			m.put("where", encode == null ? "all" : "outputs");
			m.put("x", trim(text(loc, "X")));
			m.put("y", trim(text(loc, "Y")));
			m.put("width", w == null || w.startsWith("$") ? "0" : w);
			m.put("height", h == null || h.startsWith("$") ? "0" : h);
			m.put("align", trim(text(loc, "Align")));
			m.put("opacity", trim(text(o, "Opacity")));
			m.put("refresh", "true".equalsIgnoreCase(trim(text(o, "CheckForUpdates"))));
			String key = encode == null ? "all|" + m : "out|" + m;
			Map<String, Object> have = byKey.computeIfAbsent(key, k -> { m.put("outputs", new ArrayList<String>()); return m; });
			if(encode != null)
			{
				((List<String>)have.get("outputs")).add(encode);
			}
		}
	}

	static List<String> splitList(String csv)
	{
		List<String> out = new ArrayList<>();
		if(csv != null)
		{
			for(String x: csv.split(","))
			{
				if(!x.trim().isEmpty())
				{
					out.add(x.trim());
				}
			}
		}
		return out;
	}

	private static Element child(Element parent, String tag)
	{
		if(parent == null)
		{
			return null;
		}
		for(Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
		{
			if(n instanceof Element e && e.getTagName().equals(tag))
			{
				return e;
			}
		}
		return null;
	}

	private static String text(Element parent, String tag)
	{
		Element e = child(parent, tag);
		return e == null ? null : e.getTextContent();
	}

	private static String trim(String s)
	{
		return s == null ? null : s.trim();
	}
}
