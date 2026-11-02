/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.geom.AffineTransform;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The fonts text layers draw with (plan 3.7): four SIL OFL families bundled in this jar, registered once, plus Java's
 * logical fonts and the server's own. A family is only a family; the style comes from fontStyle (D3), and every font
 * is built from its family, style and size (D2).
 *
 * Spike S3 found: a Bold file is picked by new Font(family, BOLD, size) once registered, but an Italic file is not, so
 * styles are built from the registered faces here; a style with no file (Fira Mono and PT Serif italic, and bold
 * italic in any family) is the nearest face slanted by a shear. On Linux with no fontconfig or no system font, every
 * font call fails, bundled fonts included: registration then records the reason, text layers draw nothing, and the
 * rest of an overlay still draws.
 */
public final class Fonts
{
	public static final String PLAIN = "plain", BOLD = "bold", ITALIC = "italic", BOLD_ITALIC = "bolditalic";
	public static final List<String> STYLES = List.of(PLAIN, BOLD, ITALIC, BOLD_ITALIC);
	public static final List<String> LOGICAL = List.of("Serif", "SansSerif", "Monospaced", "Dialog", "DialogInput");
	/** The family text uses when it names none. */
	public static final String DEFAULT_FAMILY = "Fira Sans";
	// a slant like a typical italic's, about 11 degrees
	static final double SHEAR = -0.2;

	/** A bundled face: its file in the jar, its family and the style the file holds. */
	record Face(String file, String family, String style)
	{
	}

	static final List<Face> BUNDLED = List.of(
			new Face("FiraSans-Regular.ttf", "Fira Sans", PLAIN), new Face("FiraSans-Bold.ttf", "Fira Sans", BOLD),
			new Face("FiraSans-Italic.ttf", "Fira Sans", ITALIC),
			new Face("FiraSansCondensed-Regular.ttf", "Fira Sans Condensed", PLAIN), new Face("FiraSansCondensed-Bold.ttf", "Fira Sans Condensed", BOLD),
			new Face("FiraSansCondensed-Italic.ttf", "Fira Sans Condensed", ITALIC),
			new Face("FiraMono-Regular.ttf", "Fira Mono", PLAIN), new Face("FiraMono-Bold.ttf", "Fira Mono", BOLD),
			new Face("PTSerif-Regular.ttf", "PT Serif", PLAIN), new Face("PTSerif-Bold.ttf", "PT Serif", BOLD));

	/** What registration found: the faces by family and style, families a system font of the same name shadows, why text cannot draw. */
	record State(Map<String, Map<String, Font>> faces, Map<String, String> shadowed, String unavailable)
	{
		/** Each bundled face's full name and its family's other spellings, to its family. */
		Map<String, String> faceFamilies()
		{
			Map<String, String> m = new LinkedHashMap<>();
			faces.forEach((family, byStyle) -> byStyle.values().forEach(f -> {
				m.put(f.getFontName(), family);
				m.put(f.getFontName().replace(" ", ""), family);
			}));
			faces.keySet().forEach(family -> m.remove(family));
			return m;
		}
	}

	private static volatile State state;

	private Fonts()
	{
	}

	/** Registers the bundled faces on first use. */
	static State state()
	{
		State s = state;
		if(s == null)
		{
			synchronized(Fonts.class)
			{
				s = state;
				if(s == null)
				{
					s = register(f -> Fonts.class.getResourceAsStream("/fonts/" + f), GraphicsEnvironment.getLocalGraphicsEnvironment()::registerFont);
					state = s;
				}
			}
		}
		return s;
	}

	/** Opens a bundled file by name. */
	interface Files
	{
		InputStream open(String file) throws Exception;
	}

	/** Registers a font with Java, as GraphicsEnvironment.registerFont does. */
	interface Registrar
	{
		boolean register(Font f);
	}

	/** Loads and registers the bundled faces; tests pass their own files and registrar. Never throws. */
	static State register(Files files, Registrar registrar)
	{
		Map<String, Map<String, Font>> faces = new LinkedHashMap<>();
		Map<String, String> shadowed = new LinkedHashMap<>();
		try
		{
			for(Face face: BUNDLED)
			{
				Font f;
				try(InputStream in = files.open(face.file()))
				{
					if(in == null)
					{
						throw new IllegalStateException("bundled font " + face.file() + " is missing from the module jar");
					}
					f = Font.createFont(Font.TRUETYPE_FONT, in);
				}
				registrar.register(f);
				faces.computeIfAbsent(face.family(), k -> new LinkedHashMap<>()).put(face.style(), f);
			}
			// registerFont answers true even when a system font of the same name wins; what new Font resolves to tells
			for(String family: faces.keySet())
			{
				String resolved = new Font(family, Font.PLAIN, 12).getFontName();
				String ours = faces.get(family).get(PLAIN).getFontName();
				if(!resolved.equals(ours))
				{
					shadowed.put(family, resolved);
				}
			}
			return new State(faces, shadowed, null);
		}
		catch(Throwable t)
		{
			// "Fontconfig head is null" and its kin on a Linux server without fontconfig or any font
			return new State(Map.of(), Map.of(), "Text is unavailable on this server: install fontconfig and one font package"
					+ " (Debian and Ubuntu: apt install fontconfig fonts-dejavu-core). " + t);
		}
	}

	/** Why text cannot be drawn on this server, or null when it can. */
	public static String unavailable()
	{
		return state().unavailable();
	}

	/** The fontStyle values, with anything else as plain. */
	public static String style(String fontStyle)
	{
		return fontStyle != null && STYLES.contains(fontStyle.toLowerCase()) ? fontStyle.toLowerCase() : PLAIN;
	}

	/**
	 * The font for this family, style and size in pixels: a bundled face (slanted when no file holds the style), else a
	 * logical or installed font by name with Java's style bits. Null when text cannot be drawn on this server.
	 */
	public static Font font(String family, String fontStyle, float size)
	{
		State s = state();
		if(s.unavailable() != null)
		{
			return null;
		}
		String style = style(fontStyle);
		String fam = family == null || family.isBlank() ? DEFAULT_FAMILY : family;
		// Java resolves a face's full name ("Fira Sans Bold") to that face; a family is only a family (D3)
		fam = s.faceFamilies().getOrDefault(fam, fam);
		Map<String, Font> faces = s.faces().get(fam);
		if(faces != null && !s.shadowed().containsKey(fam))
		{
			Font exact = faces.get(style);
			if(exact != null)
			{
				return exact.deriveFont(size);
			}
			boolean bold = style.equals(BOLD) || style.equals(BOLD_ITALIC);
			Font base = faces.getOrDefault(bold ? BOLD : PLAIN, faces.get(PLAIN));
			return base.deriveFont(size).deriveFont(AffineTransform.getShearInstance(SHEAR, 0));
		}
		int bits = switch(style)
		{
			case BOLD -> Font.BOLD;
			case ITALIC -> Font.ITALIC;
			case BOLD_ITALIC -> Font.BOLD | Font.ITALIC;
			default -> Font.PLAIN;
		};
		return new Font(fam, bits, 1).deriveFont(size);
	}

	/** For GET /fonts: the bundled families with their styles and any shadowing, the logical fonts, the server's own. */
	public static Map<String, Object> list()
	{
		State s = state();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("available", s.unavailable() == null);
		m.put("problem", s.unavailable());
		m.put("default", DEFAULT_FAMILY);
		List<Map<String, Object>> bundled = new ArrayList<>();
		for(Map.Entry<String, Map<String, Font>> e: s.faces().entrySet())
		{
			Map<String, Object> f = new LinkedHashMap<>();
			f.put("family", e.getKey());
			f.put("styles", STYLES);
			// the styles with their own file; the rest are drawn slanted
			f.put("files", new ArrayList<>(e.getValue().keySet()));
			f.put("shadowedBy", s.shadowed().get(e.getKey()));
			bundled.add(f);
		}
		m.put("bundled", bundled);
		m.put("logical", LOGICAL);
		TreeSet<String> system = new TreeSet<>();
		if(s.unavailable() == null)
		{
			try
			{
				for(String f: GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames())
				{
					if(!s.faces().containsKey(f) && !LOGICAL.contains(f))
					{
						system.add(f);
					}
				}
			}
			catch(Throwable t)
			{
				// listed as none
			}
		}
		m.put("system", new ArrayList<>(system));
		return m;
	}

	/**
	 * For GET /fonts/check: which of these characters the font cannot draw, and the family Java actually uses for it,
	 * for the warnings beside a text layer. At most 512 distinct characters are checked.
	 */
	public static Map<String, Object> check(String family, String fontStyle, String chars)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("font", family);
		Font f = font(family, fontStyle, 12);
		if(f == null)
		{
			m.put("available", false);
			m.put("problem", unavailable());
			return m;
		}
		m.put("available", true);
		m.put("uses", f.getFamily());
		TreeSet<String> missing = new TreeSet<>();
		String text = chars == null ? "" : chars;
		int seen = 0;
		java.util.Set<Integer> distinct = new java.util.HashSet<>();
		for(int i = 0; i < text.length() && seen < 512; )
		{
			int cp = text.codePointAt(i);
			i += Character.charCount(cp);
			if(Character.isWhitespace(cp) || !distinct.add(cp))
			{
				continue;
			}
			seen++;
			if(!f.canDisplay(cp))
			{
				missing.add(new String(Character.toChars(cp)));
			}
		}
		m.put("missing", new ArrayList<>(missing));
		return m;
	}
}
