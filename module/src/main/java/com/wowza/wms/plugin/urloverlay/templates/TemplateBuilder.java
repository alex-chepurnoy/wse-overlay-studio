/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.templates;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Writes a transcoder template from the page's Template step: outputs with picture, frame rate, video, audio and
 * captions settings, the hardware for the whole template, the overlay, and any number of adaptive groups. Element
 * names and values follow WSE's own parser (TranscoderStreamUtils.loadTemplate) and the stock templates; the URL
 * Overlay parts follow the starters: dynamic overlays on, the managed marker, and the urlOverlay properties.
 */
public final class TemplateBuilder
{
	/** Output kinds: an encode, the source passed through, or audio only (video Disable, TranscoderStreamUtils). */
	public static final Set<String> KINDS = Set.of("video", "passthrough", "audio");
	public static final String ABR_GROUP = "abr";
	private static final Pattern OUTPUT_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,39}");
	private static final Pattern PARAM_NAME = Pattern.compile("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)+");
	// a parameter value is written into the XML as it is, so it may hold no markup and no control character
	private static final Pattern UNWRITABLE = Pattern.compile("[<>&\"\\p{Cntrl}]");
	private static final Set<String> PROFILES_264 = Set.of("baseline", "main", "high");
	private static final Set<String> FITS = Set.of("fit-height", "fit-width", "letterbox", "crop", "stretch");

	/**
	 * The whole template's hardware: decoder, scaler and encoder implementation names, as TranscoderStreamUtils reads
	 * them (videoEncoderImplementationStringToId and the decoder and scaler mappings). Intel keeps the default scaler.
	 */
	public enum Hardware
	{
		cpu("default", "default", "default"), nvidia("NVCUVID_EVA", "CUDA_EVA", "NVENC_EVA"), intel("IQSV", "default", "IQSV"), netint("NETINT", "NETINT", "NETINT");

		public final String decoder, scaler, encoder;

		Hardware(String decoder, String scaler, String encoder)
		{
			this.decoder = decoder;
			this.scaler = scaler;
			this.encoder = encoder;
		}

		/** The hardware a template's decoder and first encoder name; cpu when they match none. */
		public static Hardware of(String decoder, String encoder)
		{
			for(Hardware h: values())
			{
				if(h != cpu && (h.decoder.equalsIgnoreCase(decoder == null ? "" : decoder.trim()) || h.encoder.equalsIgnoreCase(encoder == null ? "" : encoder.trim())))
				{
					return h;
				}
			}
			return cpu;
		}
	}

	/** A part of the source to encode, in source pixels (FrameSize/SourceRectangle). */
	public record Region(int x, int y, int w, int h)
	{
	}

	/** An expert Parameter written as given: prefix.name and a value. */
	public record Param(String name, String value)
	{
	}

	/**
	 * One output. height 0 means the source's size (FitMode match-source). width is used by the fits that need it.
	 * rateControl and speed write H.264's mainconcept Parameters (bit_rate_mode, max_bit_rate, performance_level,
	 * values from TranscoderNativeParameterInfo); "default" writes nothing. keyInterval is in frames.
	 */
	public record Output(String name, String kind, boolean enabled, boolean overlay, int height, int width, String fit, Region region, int skip,
			String codec, String profile, long videoBitrate, String rateControl, long peakBitrate, String speed, boolean keyFollow, int keyInterval,
			boolean hdrToSdr, String audio, long audioBitrate, int channels, int sampleRate, boolean captions, List<Param> params)
	{
		boolean encodes()
		{
			return kind.equals("video");
		}
	}

	/** An adaptive group member: an output, and optionally only its audio or only its video (WowzaAudioOnly/VideoOnly). */
	public record Member(String output, String only)
	{
	}

	public record Group(String name, List<Member> members)
	{
	}

	/** minFrameRate: Decode/Video/MinimumFrameRate, 0 writes nothing (output fps = max(it, source) / (SkipFrameCount+1)). */
	public record Advanced(boolean deinterlace, int gpuId, double minFrameRate)
	{
	}

	/**
	 * One of the transcoder's own image overlays (TranscoderStreamUtils.loadConfigOverlay): a file in the content folder,
	 * drawn before scaling on every output (Decode/Video/Overlays) or on chosen outputs (Encode/Video/Overlays). width and
	 * height 0 use the image's size (${ImageWidth}); align is horizontal,vertical. Indexes are given in list order from 1,
	 * below the URL overlay's (UrlOverlayIndex, 16 by default); which index WSE draws on top is not in the Java source.
	 */
	public record ImageLayer(String file, boolean everyOutput, List<String> outputs, int x, int y, int width, int height, String align, int opacity,
			boolean refresh)
	{
	}

	public static final int MAX_IMAGES = 8;
	private static final Set<String> H_ALIGN = Set.of("left", "right", "hcenter");
	private static final Set<String> V_ALIGN = Set.of("top", "bottom", "vcenter");

	public record Spec(String name, Hardware hardware, String overlay, List<Output> outputs, List<Group> groups, Advanced advanced, List<ImageLayer> images)
	{
		/** The outputs that show the overlay; empty when every encoding output does (drawn once, before scaling). */
		public List<String> overlayEncodes()
		{
			List<String> on = new ArrayList<>();
			boolean all = true;
			for(Output o: outputs)
			{
				if(o.encodes() && o.enabled())
				{
					if(o.overlay())
					{
						on.add(o.name());
					}
					else
					{
						all = false;
					}
				}
			}
			return all ? List.of() : on;
		}
	}

	private TemplateBuilder()
	{
	}

	/** The spec in a request body; Refused (400) with a message the page shows when something is not usable. */
	@SuppressWarnings("unchecked")
	public static Spec parse(String name, Map<String, Object> body) throws TemplateFiles.Refused
	{
		String n = TemplateFiles.safeName(name);
		Hardware hw;
		try
		{
			hw = Hardware.valueOf(str(body.get("hardware"), "cpu").toLowerCase(Locale.ROOT));
		}
		catch(IllegalArgumentException e)
		{
			throw bad("Hardware must be cpu, nvidia, intel or netint.");
		}
		String overlay = str(body.get("overlay"), null);
		if(overlay != null && overlay.isBlank())
		{
			overlay = null;
		}
		if(overlay != null)
		{
			TemplateFiles.safeName(overlay);
		}
		if(!(body.get("outputs") instanceof List<?> raw) || raw.isEmpty())
		{
			throw bad("A template needs at least one output.");
		}
		if(raw.size() > 12)
		{
			throw bad("At most 12 outputs.");
		}
		// before 1.5.0 keyframes were set once for the whole template
		int keyDefault = (int)num(body.get("keyInterval"), 60, 1, 600, "template", "keyframe interval");
		boolean followDefault = Boolean.TRUE.equals(body.get("followSource"));
		List<Output> outs = new ArrayList<>();
		Set<String> names = new HashSet<>();
		for(Object o: raw)
		{
			if(!(o instanceof Map))
			{
				throw bad("Each output must be an object.");
			}
			Output out = output((Map<String, Object>)o, hw, keyDefault, followDefault);
			if(!names.add(out.name().toLowerCase(Locale.ROOT)))
			{
				throw bad("Two outputs are named " + out.name() + ".");
			}
			outs.add(out);
		}
		if(outs.stream().noneMatch(Output::enabled))
		{
			throw bad("Every output is switched off.");
		}
		if(overlay != null && outs.stream().noneMatch(x -> x.encodes() && x.enabled() && x.overlay()))
		{
			throw bad("No output shows the overlay. Turn it on for at least one output that is not passed through.");
		}
		List<Group> groups = groups(body, outs);
		double minFps = body.get("minFrameRate") == null ? 0 : num(body.get("minFrameRate"), 0, 0, 120, "template", "minimum frame rate");
		Advanced adv = new Advanced(Boolean.TRUE.equals(body.get("deinterlace")), (int)num(body.get("gpuId"), -1, -1, 15, "template", "GPU"), minFps);
		return new Spec(n, hw, overlay, outs, groups, adv, images(body, outs));
	}

	private static List<ImageLayer> images(Map<String, Object> body, List<Output> outs) throws TemplateFiles.Refused
	{
		List<ImageLayer> images = new ArrayList<>();
		if(!(body.get("images") instanceof List<?> raw))
		{
			return images;
		}
		if(raw.size() > MAX_IMAGES)
		{
			throw bad("At most " + MAX_IMAGES + " image layers.");
		}
		for(Object o: raw)
		{
			if(!(o instanceof Map<?, ?> m))
			{
				throw bad("Each image layer must be an object.");
			}
			String file = ImageLibrary.safeFile(str(m.get("file"), ""));
			String label = "Image " + file;
			boolean every = !"outputs".equals(str(m.get("where"), "all"));
			List<String> on = new ArrayList<>();
			if(!every && m.get("outputs") instanceof List<?> ol)
			{
				for(Object x: ol)
				{
					String name = String.valueOf(x);
					Output out = outs.stream().filter(y -> y.name().equals(name)).findFirst()
							.orElseThrow(() -> bad(label + " names output " + name + ", which is not in the template."));
					if(!out.encodes())
					{
						throw bad(label + ": output " + name + " does not encode video, so nothing can be drawn on it.");
					}
					on.add(name);
				}
			}
			if(!every && on.isEmpty())
			{
				throw bad(label + " is on chosen outputs, and none is chosen.");
			}
			String align = str(m.get("align"), "left,top").toLowerCase(Locale.ROOT).replace(" ", "");
			String[] a = align.split(",");
			if(a.length != 2 || !H_ALIGN.contains(a[0]) || !V_ALIGN.contains(a[1]))
			{
				throw bad(label + ": align is left, right or hcenter, then top, bottom or vcenter.");
			}
			images.add(new ImageLayer(file, every, on, (int)num(m.get("x"), 0, 0, 8192, label, "x"), (int)num(m.get("y"), 0, 0, 8192, label, "y"),
					(int)num(m.get("width"), 0, 0, 8192, label, "width"), (int)num(m.get("height"), 0, 0, 8192, label, "height"), align,
					(int)num(m.get("opacity"), 100, 0, 100, label, "opacity"), Boolean.TRUE.equals(m.get("refresh"))));
		}
		return images;
	}

	@SuppressWarnings("unchecked")
	private static List<Group> groups(Map<String, Object> body, List<Output> outs) throws TemplateFiles.Refused
	{
		List<Group> groups = new ArrayList<>();
		if(!(body.get("groups") instanceof List<?> raw))
		{
			// before 1.5.0: one group of every output carrying video, unless switched off
			if(!Boolean.FALSE.equals(body.get("abr")))
			{
				List<Member> m = new ArrayList<>();
				for(Output o: outs)
				{
					if(o.enabled() && !o.kind().equals("audio"))
					{
						m.add(new Member(o.name(), ""));
					}
				}
				if(m.size() > 1)
				{
					groups.add(new Group(ABR_GROUP, m));
				}
			}
			return groups;
		}
		if(raw.size() > 8)
		{
			throw bad("At most 8 adaptive groups.");
		}
		Set<String> names = new HashSet<>();
		for(Object g: raw)
		{
			if(!(g instanceof Map<?, ?> gm))
			{
				throw bad("Each group must be an object.");
			}
			String gname = str(gm.get("name"), "");
			if(!OUTPUT_NAME.matcher(gname).matches())
			{
				throw bad("Group name '" + gname + "': letters, digits, dash and underscore, at most 40.");
			}
			if(!names.add(gname.toLowerCase(Locale.ROOT)))
			{
				throw bad("Two groups are named " + gname + ".");
			}
			if(outs.stream().anyMatch(o -> o.name().equalsIgnoreCase(gname)))
			{
				throw bad("Group " + gname + " has an output's name; players could not tell them apart.");
			}
			List<Member> members = new ArrayList<>();
			if(gm.get("members") instanceof List<?> ml)
			{
				for(Object mo: ml)
				{
					String out = mo instanceof Map<?, ?> mm ? str(mm.get("output"), "") : String.valueOf(mo);
					String only = mo instanceof Map<?, ?> mm ? str(mm.get("only"), "") : "";
					Output o = outs.stream().filter(x -> x.name().equals(out)).findFirst()
							.orElseThrow(() -> bad("Group " + gname + " names output " + out + ", which is not in the template."));
					if(!only.isEmpty() && !only.equals("audio") && !only.equals("video"))
					{
						throw bad("Group " + gname + ": a member is all of an output, its audio only, or its video only.");
					}
					if(o.enabled())
					{
						members.add(new Member(o.name(), only));
					}
				}
			}
			if(!members.isEmpty())
			{
				groups.add(new Group(gname, members));
			}
		}
		return groups;
	}

	@SuppressWarnings("unchecked")
	private static Output output(Map<String, Object> m, Hardware hw, int keyDefault, boolean followDefault) throws TemplateFiles.Refused
	{
		String name = str(m.get("name"), "");
		if(!OUTPUT_NAME.matcher(name).matches())
		{
			throw bad("Output name '" + name + "': letters, digits, dash and underscore, at most 40.");
		}
		String kind = str(m.get("kind"), "video").toLowerCase(Locale.ROOT);
		if(!KINDS.contains(kind))
		{
			throw bad("Output " + name + ": kind must be video, passthrough or audio.");
		}
		boolean enabled = !Boolean.FALSE.equals(m.get("enabled"));
		boolean captions = !Boolean.FALSE.equals(m.get("captions"));
		// audio for every kind: an audio-only output always encodes it
		String audio = str(m.get("audio"), kind.equals("passthrough") ? "passthru" : "aac").toLowerCase(Locale.ROOT);
		if(!Set.of("aac", "opus", "passthru", "disable").contains(audio))
		{
			throw bad("Output " + name + ": audio is aac, opus, passthru or disable.");
		}
		if(kind.equals("audio") && !(audio.equals("aac") || audio.equals("opus")))
		{
			throw bad("Output " + name + ": an audio-only output encodes its audio, AAC or Opus.");
		}
		long ab = audio.equals("aac") || audio.equals("opus") ? num(m.get("audioBitrate"), 96000, 16000, 512000, name, "audio bitrate") : 0;
		int channels = (int)num(m.get("channels"), 0, 0, 2, name, "audio channels");
		int rate = (int)num(m.get("sampleRate"), 0, 0, 48000, name, "audio sample rate");
		if(rate != 0 && rate != 44100 && rate != 48000)
		{
			throw bad("Output " + name + ": sample rate 44100 or 48000, or 0 to keep the source's.");
		}
		if(!kind.equals("video"))
		{
			return new Output(name, kind, enabled, false, 0, 0, null, null, 0, null, null, 0, "default", 0, "default", true, 0, false, audio, ab,
					channels, rate, captions, List.of());
		}
		int height = (int)num(m.get("height"), 0, 0, 4320, name, "height");
		if(height != 0 && height < 120)
		{
			throw bad("Output " + name + ": height 0 (the source's size) or 120 to 4320.");
		}
		String fit = str(m.get("fit"), "fit-height").toLowerCase(Locale.ROOT);
		if(height != 0 && !FITS.contains(fit))
		{
			throw bad("Output " + name + ": fit is fit-height, fit-width, letterbox, crop or stretch.");
		}
		int width = (int)num(m.get("width"), 0, 0, 7680, name, "width");
		if(height != 0 && !fit.equals("fit-height") && width < 120)
		{
			throw bad("Output " + name + ": " + fit + " needs a width of at least 120.");
		}
		Region region = null;
		if(m.get("region") instanceof Map<?, ?> r)
		{
			region = new Region((int)num(r.get("x"), 0, 0, 8192, name, "region x"), (int)num(r.get("y"), 0, 0, 8192, name, "region y"),
					(int)num(r.get("w"), 0, 16, 8192, name, "region width"), (int)num(r.get("h"), 0, 16, 8192, name, "region height"));
		}
		int skip = (int)num(m.get("skip"), 0, 0, 5, name, "frame rate divisor");
		long vb = num(m.get("videoBitrate"), 0, 50000, 100000000, name, "video bitrate");
		String codec = str(m.get("codec"), "H.264");
		String profile = null;
		switch(codec.toUpperCase(Locale.ROOT))
		{
			case "H.264" -> {
				codec = "H.264";
				profile = str(m.get("profile"), "main").toLowerCase(Locale.ROOT);
				if(!PROFILES_264.contains(profile))
				{
					throw bad("Output " + name + ": H.264 profile is baseline, main or high.");
				}
			}
			case "H.265" -> {
				codec = "H.265";
				profile = "main";
			}
			case "VP8", "VP9" -> {
				codec = codec.toUpperCase(Locale.ROOT);
				if(hw != Hardware.cpu)
				{
					throw bad("Output " + name + ": " + codec + " is encoded on the CPU only.");
				}
			}
			default -> throw bad("Output " + name + ": codec is H.264, H.265, VP8 or VP9.");
		}
		String rc = str(m.get("rateControl"), "default").toLowerCase(Locale.ROOT);
		String speed = str(m.get("speed"), "default").toLowerCase(Locale.ROOT);
		if(!Set.of("default", "cbr", "vbr").contains(rc) || !Set.of("default", "fastest", "balanced", "quality").contains(speed))
		{
			throw bad("Output " + name + ": rate control is default, cbr or vbr; speed is default, fastest, balanced or quality.");
		}
		if(!codec.equals("H.264") && (!rc.equals("default") || !speed.equals("default")))
		{
			// the H.265 and VP names exist (TranscoderNativeParameterInfo) but their values are not in the Java source
			throw bad("Output " + name + ": rate control and speed are set for H.264; for " + codec + " add the encoder's parameters by name.");
		}
		long peak = rc.equals("vbr") ? num(m.get("peakBitrate"), vb * 3 / 2, vb, 200000000, name, "peak bitrate") : 0;
		boolean keyFollow = m.containsKey("keyFollow") ? Boolean.TRUE.equals(m.get("keyFollow")) : followDefault;
		int keyInterval = (int)num(m.get("keyInterval"), keyDefault, 1, 600, name, "keyframe interval");
		boolean hdr = Boolean.TRUE.equals(m.get("hdrToSdr"));
		if(hdr && !codec.equals("H.265") && !codec.equals("H.264"))
		{
			throw bad("Output " + name + ": HDR to SDR applies to H.264 and H.265.");
		}
		List<Param> params = new ArrayList<>();
		if(m.get("params") instanceof List<?> pl)
		{
			if(pl.size() > 30)
			{
				throw bad("Output " + name + ": at most 30 parameters.");
			}
			for(Object po: pl)
			{
				if(!(po instanceof Map<?, ?> pm))
				{
					continue;
				}
				String pn = str(pm.get("name"), "");
				String pv = str(pm.get("value"), "");
				if(!PARAM_NAME.matcher(pn).matches())
				{
					throw bad("Output " + name + ": parameter name '" + pn + "' is prefix.name, for example mainconcept.adaptive_b_frames.");
				}
				if(pv.length() > 200 || UNWRITABLE.matcher(pv).find())
				{
					throw bad("Output " + name + ": parameter " + pn + " has a value that cannot be written.");
				}
				params.add(new Param(pn, pv));
			}
		}
		return new Output(name, kind, enabled, Boolean.TRUE.equals(m.get("overlay")), height, width, height == 0 ? "match-source" : fit, region, skip,
				codec, profile, vb, rc, peak, speed, keyFollow, keyInterval, hdr, audio, ab, channels, rate, captions, params);
	}

	/** The template file's text. */
	public static String xml(Spec s)
	{
		Hardware hw = s.hardware();
		StringBuilder b = new StringBuilder();
		b.append("<Root version=\"1\">\n\t<Transcode>\n");
		b.append("\t\t<Description>").append(TemplateValidator.MANAGED_MARKER).append(": built on the Template step. ")
				.append(hw.name()).append(", ").append(s.outputs().size()).append(s.outputs().size() == 1 ? " output" : " outputs").append(".</Description>\n");
		b.append("\t\t<Decode>\n\t\t\t<Video>\n\t\t\t\t<Implementation>").append(hw.decoder).append("</Implementation>\n")
				.append("\t\t\t\t<GPUID>").append(s.advanced().gpuId()).append("</GPUID>\n")
				.append("\t\t\t\t<Deinterlace>").append(s.advanced().deinterlace()).append("</Deinterlace>\n")
				.append("\t\t\t\t<!-- Required for URL overlays. Turns off zero-copy GPU paths in this transcoder. -->\n")
				.append("\t\t\t\t<EnableDynamicOverlays>true</EnableDynamicOverlays>\n");
		if(s.advanced().minFrameRate() > 0)
		{
			b.append("\t\t\t\t<MinimumFrameRate>").append(s.advanced().minFrameRate()).append("</MinimumFrameRate>\n");
		}
		overlays(b, "\t\t\t\t", s, null);
		b.append("\t\t\t</Video>\n\t\t</Decode>\n");
		b.append("\t\t<Scale>\n\t\t\t<Implementation>").append(hw.scaler).append("</Implementation>\n\t\t</Scale>\n");
		b.append("\t\t<Encodes>\n");
		for(Output o: s.outputs())
		{
			encode(b, o, s);
		}
		b.append("\t\t</Encodes>\n");
		if(!s.groups().isEmpty())
		{
			b.append("\t\t<StreamNameGroups>\n\t\t\t<!-- Players open a group as ngrp:<stream>_<group> -->\n");
			for(Group g: s.groups())
			{
				b.append("\t\t\t<StreamNameGroup>\n\t\t\t\t<Name>").append(g.name()).append("</Name>\n")
						.append("\t\t\t\t<StreamName>${SourceStreamName}_").append(g.name()).append("</StreamName>\n\t\t\t\t<Members>\n");
				for(Member mb: g.members())
				{
					b.append("\t\t\t\t\t<Member>\n\t\t\t\t\t\t<MemberName>").append(mb.output()).append(mb.only().isEmpty() ? "" : "_" + mb.only())
							.append("</MemberName>\n\t\t\t\t\t\t<EncodeName>").append(mb.output()).append("</EncodeName>\n");
					if(!mb.only().isEmpty())
					{
						b.append("\t\t\t\t\t\t<MediaListRendition>\n\t\t\t\t\t\t\t<").append(mb.only().equals("audio") ? "WowzaAudioOnly" : "WowzaVideoOnly")
								.append(">true</").append(mb.only().equals("audio") ? "WowzaAudioOnly" : "WowzaVideoOnly").append(">\n\t\t\t\t\t\t</MediaListRendition>\n");
					}
					b.append("\t\t\t\t\t</Member>\n");
				}
				b.append("\t\t\t\t</Members>\n\t\t\t</StreamNameGroup>\n");
			}
			b.append("\t\t</StreamNameGroups>\n");
		}
		b.append("\t\t<Properties>\n");
		if(s.overlay() != null)
		{
			property(b, "\t\t\t", TemplateValidator.PROP_OVERLAY, s.overlay(), "String");
			property(b, "\t\t\t", TemplateValidator.PROP_ENCODES, String.join(",", s.overlayEncodes()), "String");
		}
		b.append("\t\t</Properties>\n\t</Transcode>\n</Root>\n");
		return b.toString();
	}

	private static void encode(StringBuilder b, Output o, Spec s)
	{
		String t5 = "\t\t\t\t\t", t6 = t5 + "\t";
		b.append("\t\t\t<Encode>\n\t\t\t\t<Name>").append(o.name()).append("</Name>\n\t\t\t\t<Enable>").append(o.enabled()).append("</Enable>\n")
				.append("\t\t\t\t<Description>").append(describe(o, s.overlay() != null)).append("</Description>\n")
				.append("\t\t\t\t<StreamName>${SourceStreamName}_").append(o.name()).append("</StreamName>\n\t\t\t\t<Video>\n");
		switch(o.kind())
		{
			case "passthrough" -> b.append(t5).append("<Codec>PassThru</Codec>\n").append(t5).append("<Bitrate>${SourceVideoBitrate}</Bitrate>\n");
			case "audio" -> b.append(t5).append("<Codec>Disable</Codec>\n");
			default -> {
				boolean vp = o.codec().startsWith("VP");
				b.append(t5).append("<Codec>").append(o.codec()).append("</Codec>\n")
						.append(t5).append("<Implementation>").append(vp ? "default" : s.hardware().encoder).append("</Implementation>\n")
						.append(t5).append("<GPUID>").append(s.advanced().gpuId()).append("</GPUID>\n");
				if(o.profile() != null)
				{
					b.append(t5).append("<Profile>").append(o.profile()).append("</Profile>\n");
				}
				b.append(t5).append("<Bitrate>").append(o.videoBitrate()).append("</Bitrate>\n")
						.append(t5).append("<KeyFrameInterval>\n").append(t6).append("<FollowSource>").append(o.keyFollow()).append("</FollowSource>\n")
						.append(t6).append("<Interval>").append(o.keyInterval()).append("</Interval>\n").append(t5).append("</KeyFrameInterval>\n");
				if(o.skip() > 0)
				{
					b.append(t5).append("<SkipFrameCount>").append(o.skip()).append("</SkipFrameCount>\n");
				}
				if(o.hdrToSdr())
				{
					b.append(t5).append("<Convert8bit>true</Convert8bit>\n");
				}
				b.append(t5).append("<FrameSize>\n").append(t6).append("<FitMode>").append(o.fit()).append("</FitMode>\n")
						.append(t6).append("<Width>").append(o.fit().equals("fit-height") || o.fit().equals("match-source") ? 0 : o.width()).append("</Width>\n")
						.append(t6).append("<Height>").append(o.fit().equals("fit-width") ? 0 : o.height()).append("</Height>\n")
						.append(t6).append("<Crop>0,0,0,0</Crop>\n")
						.append(t6).append("<SourceRectangle>").append(o.region() == null ? "" : o.region().x() + "," + o.region().y() + "," + o.region().w() + "," + o.region().h())
						.append("</SourceRectangle>\n").append(t5).append("</FrameSize>\n");
				overlays(b, t5, s, o.name());
				List<Param> params = new ArrayList<>();
				// H.264's MainConcept names, which every hardware here uses for H.264 (TranscoderNativeParameterInfo)
				if(o.rateControl().equals("cbr") || o.rateControl().equals("vbr"))
				{
					params.add(new Param("mainconcept.bit_rate_mode", o.rateControl().equals("cbr") ? "0" : "2"));
				}
				if(o.rateControl().equals("vbr"))
				{
					params.add(new Param("mainconcept.max_bit_rate", String.valueOf(o.peakBitrate())));
				}
				Map<String, String> levels = Map.of("fastest", "0", "balanced", "9", "quality", "15");
				if(levels.containsKey(o.speed()))
				{
					params.add(new Param("mainconcept.performance_level", levels.get(o.speed())));
				}
				params.addAll(o.params());
				if(!params.isEmpty())
				{
					b.append(t5).append("<Parameters>\n");
					for(Param p: params)
					{
						b.append(t6).append("<Parameter>\n").append(t6).append("\t<Name>").append(p.name()).append("</Name>\n")
								.append(t6).append("\t<Value>").append(p.value()).append("</Value>\n").append(t6).append("\t<Type>Long</Type>\n")
								.append(t6).append("</Parameter>\n");
					}
					b.append(t5).append("</Parameters>\n");
				}
			}
		}
		b.append("\t\t\t\t</Video>\n\t\t\t\t<Audio>\n");
		switch(o.audio())
		{
			case "passthru" -> b.append(t5).append("<Codec>PassThru</Codec>\n").append(t5).append("<Bitrate>${SourceAudioBitrate}</Bitrate>\n");
			case "disable" -> b.append(t5).append("<Codec>Disable</Codec>\n");
			default -> {
				b.append(t5).append("<Codec>").append(o.audio().equals("opus") ? "Opus" : "AAC").append("</Codec>\n")
						.append(t5).append("<Bitrate>").append(o.audioBitrate()).append("</Bitrate>\n");
				if(o.channels() != 0 || o.sampleRate() != 0)
				{
					b.append(t5).append("<Resample>\n").append(t6).append("<Enable>true</Enable>\n")
							.append(t6).append("<SampleRate>").append(o.sampleRate() == 0 ? 48000 : o.sampleRate()).append("</SampleRate>\n")
							.append(t6).append("<Channels>").append(o.channels() == 0 ? 2 : o.channels()).append("</Channels>\n").append(t5).append("</Resample>\n");
				}
			}
		}
		b.append("\t\t\t\t</Audio>\n");
		if(!o.captions())
		{
			b.append("\t\t\t\t<Properties>\n");
			property(b, t5, "copyCEA608CaptionsFromSourceStream", "false", "Boolean");
			b.append("\t\t\t\t</Properties>\n");
		}
		b.append("\t\t\t</Encode>\n");
	}

	// image layers drawn here: every-output ones on the decoder (output null), chosen-output ones on that output's encode
	private static void overlays(StringBuilder b, String indent, Spec s, String output)
	{
		List<String> lines = new ArrayList<>();
		for(int i = 0; i < s.images().size(); i++)
		{
			ImageLayer l = s.images().get(i);
			if(output == null ? !l.everyOutput() : l.everyOutput() || !l.outputs().contains(output))
			{
				continue;
			}
			String t = indent + "\t\t";
			lines.add(indent + "\t<Overlay>\n" + t + "<Enable>true</Enable>\n" + t + "<Index>" + (i + 1) + "</Index>\n"
					+ t + "<ImagePath>${com.wowza.wms.context.VHostConfigHome}/content/" + l.file() + "</ImagePath>\n"
					+ t + "<CheckForUpdates>" + l.refresh() + "</CheckForUpdates>\n" + t + "<Opacity>" + l.opacity() + "</Opacity>\n"
					+ t + "<Location>\n" + t + "\t<X>" + l.x() + "</X>\n" + t + "\t<Y>" + l.y() + "</Y>\n"
					+ t + "\t<Width>" + (l.width() == 0 ? "${ImageWidth}" : l.width()) + "</Width>\n"
					+ t + "\t<Height>" + (l.height() == 0 ? "${ImageHeight}" : l.height()) + "</Height>\n"
					+ t + "\t<Align>" + l.align() + "</Align>\n" + t + "</Location>\n" + indent + "\t</Overlay>\n");
		}
		if(!lines.isEmpty())
		{
			b.append(indent).append("<Overlays>\n");
			lines.forEach(b::append);
			b.append(indent).append("</Overlays>\n");
		}
	}

	private static String describe(Output o, boolean hasOverlay)
	{
		return switch(o.kind())
		{
			case "passthrough" -> "The original, unchanged";
			case "audio" -> "Audio only";
			default -> (o.height() == 0 ? "Source size" : o.height() + " high") + (hasOverlay && o.overlay() ? " with the overlay" : "");
		};
	}

	private static void property(StringBuilder b, String indent, String name, String value, String type)
	{
		b.append(indent).append("<Property>\n").append(indent).append("\t<Name>").append(name).append("</Name>\n")
				.append(indent).append("\t<Value>").append(value).append("</Value>\n").append(indent).append("\t<Type>").append(type).append("</Type>\n")
				.append(indent).append("</Property>\n");
	}

	/** What the page's Template step reads back from an existing template's validation, as a map per encode. */
	public static Map<String, Object> describeRateControl(Map<String, String> params)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		String mode = params.get("mainconcept.bit_rate_mode");
		m.put("rateControl", "0".equals(mode) ? "cbr" : "2".equals(mode) ? "vbr" : "default");
		m.put("peakBitrate", params.get("mainconcept.max_bit_rate"));
		String level = params.get("mainconcept.performance_level");
		m.put("speed", "0".equals(level) ? "fastest" : "9".equals(level) ? "balanced" : "15".equals(level) ? "quality" : "default");
		return m;
	}

	private static String str(Object o, String dflt)
	{
		return o == null ? dflt : String.valueOf(o).trim();
	}

	private static long num(Object o, long dflt, long min, long max, String output, String what) throws TemplateFiles.Refused
	{
		long v;
		if(o == null)
		{
			v = dflt;
		}
		else
		{
			try
			{
				v = o instanceof Number x ? x.longValue() : Long.parseLong(String.valueOf(o).trim());
			}
			catch(NumberFormatException e)
			{
				throw bad("Output " + output + ": " + what + " is not a number.");
			}
		}
		if(v < min || v > max)
		{
			throw bad("Output " + output + ": " + what + " must be " + min + " to " + max + ".");
		}
		return v;
	}

	private static TemplateFiles.Refused bad(String message)
	{
		return new TemplateFiles.Refused(400, message);
	}
}
