/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The installer's edits to Server.xml and Application.xml, as text insertions placed
 * with XmlScan. Each edit leaves every other byte alone, matches the file's indentation and line ending, and reports
 * exactly what it inserted so uninstall can take out just that when the file was changed by something else since.
 */
public final class ConfigEdits
{
	public static final String SERVER_CLASS = "com.wowza.wms.plugin.overlaystudio.OverlayStudioServer";
	public static final String MODULE_CLASS = "com.wowza.wms.plugin.overlaystudio.OverlayStudioModule";

	private ConfigEdits()
	{
	}

	/** One change to a file's text: either an inserted block, or an element's content replaced. */
	public record Change(String kind, String path, String inserted, String before, String after, String note)
	{
		public Map<String, Object> toMap()
		{
			Map<String, Object> m = new java.util.LinkedHashMap<>();
			m.put("kind", kind);
			m.put("path", path);
			if(inserted != null)
			{
				m.put("inserted", inserted);
			}
			if(before != null)
			{
				m.put("before", before);
				m.put("after", after);
			}
			m.put("note", note);
			return m;
		}
	}

	/** A file's new text and what changed in it; no changes means the text is unchanged. */
	public record Result(TextFile file, List<Change> changes)
	{
	}

	/**
	 * Server.xml: the listener and the properties not set yet (existing values are kept). RESTInterface is never touched:
	 * the page reaches REST through the Manager bridge, server-side, so REST needs no CORS headers and stays on its own
	 * IPWhiteList.
	 */
	public static Result server(TextFile f, Map<String, String> properties)
	{
		List<Change> changes = new ArrayList<>();
		TextFile out = f;
		XmlScan.Span sl = XmlScan.first(out.text(), "/Root/Server/ServerListeners");
		if(sl == null)
		{
			throw new IllegalArgumentException("Server.xml has no Root/Server/ServerListeners");
		}
		if(!out.text().substring(sl.contentStart(), sl.close()).contains(SERVER_CLASS))
		{
			out = insert(out, "/Root/Server/ServerListeners", changes, "server listener",
					List.of(new Line(0, "<ServerListener>"), new Line(1, "<BaseClass>" + SERVER_CLASS + "</BaseClass>"), new Line(0, "</ServerListener>")));
		}
		for(Map.Entry<String, String> p: properties.entrySet())
		{
			if(!hasProperty(out.text(), "/Root/Server/Properties", p.getKey()))
			{
				out = insert(out, "/Root/Server/Properties", changes, "property " + p.getKey(), property(p.getKey(), p.getValue(), type(p.getValue())));
			}
		}
		return new Result(out, changes);
	}

	/** Application.xml: the module entry, if absent. Transcoder settings are never touched here. */
	public static Result application(TextFile f)
	{
		List<Change> changes = new ArrayList<>();
		TextFile out = f;
		XmlScan.Span mods = XmlScan.first(out.text(), "/Root/Application/Modules");
		if(mods == null)
		{
			throw new IllegalArgumentException("Application.xml has no Root/Application/Modules");
		}
		if(!out.text().substring(mods.contentStart(), mods.close()).contains(MODULE_CLASS))
		{
			out = insert(out, "/Root/Application/Modules", changes, "module", List.of(new Line(0, "<Module>"), new Line(1, "<Name>OverlayStudioModule</Name>"),
					new Line(1, "<Description>Overlay Studio</Description>"), new Line(1, "<Class>" + MODULE_CLASS + "</Class>"), new Line(0, "</Module>")));
		}
		return new Result(out, changes);
	}

	public static boolean hasModule(String applicationXml)
	{
		XmlScan.Span mods = XmlScan.first(applicationXml, "/Root/Application/Modules");
		return mods != null && applicationXml.substring(mods.contentStart(), mods.close()).contains(MODULE_CLASS);
	}

	/**
	 * Takes the recorded changes out of a file something else has edited since: an inserted block is removed where it
	 * still appears exactly, a replaced value is put back where it still holds the installed value.
	 * @return the new file and the changes that could not be undone
	 */
	public static Map.Entry<TextFile, List<String>> undo(TextFile f, List<Map<String, Object>> changes)
	{
		String t = f.text();
		List<String> left = new ArrayList<>();
		for(int k = changes.size() - 1; k >= 0; k--)
		{
			Map<String, Object> c = changes.get(k);
			if("insert".equals(c.get("kind")))
			{
				String ins = (String)c.get("inserted");
				int at = t.indexOf(ins);
				if(at < 0)
				{
					left.add("not found any more: " + c.get("note"));
					continue;
				}
				t = t.substring(0, at) + t.substring(at + ins.length());
			}
			else if("replace".equals(c.get("kind")))
			{
				XmlScan.Span s = XmlScan.first(t, (String)c.get("path"));
				if(s == null || !t.substring(s.contentStart(), s.close()).equals(c.get("after")))
				{
					left.add("changed since install, left as it is: " + c.get("note"));
					continue;
				}
				t = t.substring(0, s.contentStart()) + c.get("before") + t.substring(s.close());
			}
		}
		return Map.entry(f.with(t), left);
	}

	// ---- helpers ----------------------------------------------------------------------------------------------------

	public record Line(int level, String text)
	{
	}

	public static List<Line> property(String name, String value, String type)
	{
		return List.of(new Line(0, "<Property>"), new Line(1, "<Name>" + esc(name) + "</Name>"), new Line(1, "<Value>" + esc(value) + "</Value>"),
				new Line(1, "<Type>" + type + "</Type>"), new Line(0, "</Property>"));
	}

	public static String type(String value)
	{
		if(value.equals("true") || value.equals("false"))
		{
			return "Boolean";
		}
		return value.matches("-?\\d+") ? "Integer" : "String";
	}

	/** The value of a property in the element at parent, or null. */
	public static String propertyValue(String text, String parent, String name)
	{
		XmlScan.Span p = XmlScan.first(text, parent);
		if(p == null)
		{
			return null;
		}
		for(XmlScan.Span prop: XmlScan.find(text, parent + "/Property"))
		{
			String inner = text.substring(prop.contentStart(), prop.close());
			if(prop.open() > p.contentStart() && prop.close() < p.close() && inner.contains("<Name>" + name + "</Name>"))
			{
				int a = inner.indexOf("<Value>"), b = inner.indexOf("</Value>");
				return a < 0 || b < a ? "" : inner.substring(a + 7, b).trim().replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
			}
		}
		return null;
	}

	public static boolean hasProperty(String text, String parent, String name)
	{
		XmlScan.Span p = XmlScan.first(text, parent);
		if(p == null)
		{
			return false;
		}
		for(XmlScan.Span n: XmlScan.find(text, parent + "/Property/Name"))
		{
			if(n.open() > p.contentStart() && n.close() < p.close() && text.substring(n.contentStart(), n.close()).trim().equals(name))
			{
				return true;
			}
		}
		return false;
	}

	/** Inserts lines as the last children of the element at parent, indented one step inside it. */
	public static TextFile insert(TextFile f, String parent, List<Change> changes, String note, List<Line> lines)
	{
		XmlScan.Span p = XmlScan.first(f.text(), parent);
		if(p == null)
		{
			throw new IllegalArgumentException("No " + parent + " to add the " + note + " to");
		}
		return insertInto(f, p, parent, changes, note, lines);
	}

	/** As insert, into the given element (parent is its path, for the record). */
	public static TextFile insertInto(TextFile f, XmlScan.Span p, String parent, List<Change> changes, String note, List<Line> lines)
	{
		String t = f.text();
		String parentIndent = XmlScan.indentAt(t, p.open());
		String unit = unit(t, p, parentIndent);
		StringBuilder b = new StringBuilder();
		int lineStart = XmlScan.lineStart(t, p.close());
		boolean ownLine = t.substring(lineStart, p.close()).isBlank();
		if(!ownLine)
		{
			b.append(f.eol());
		}
		for(Line l: lines)
		{
			b.append(parentIndent).append(unit.repeat(l.level() + 1)).append(l.text()).append(f.eol());
		}
		if(!ownLine)
		{
			b.append(parentIndent);
		}
		int at = ownLine ? lineStart : p.close();
		String ins = b.toString();
		changes.add(new Change("insert", parent, ins, null, null, note));
		return f.with(t.substring(0, at) + ins + t.substring(at));
	}

	// one indentation step, taken from the parent's existing children; a tab when it has none
	private static String unit(String t, XmlScan.Span p, String parentIndent)
	{
		String inner = t.substring(p.contentStart(), p.close());
		for(String line: inner.split("\r?\n"))
		{
			if(line.isBlank())
			{
				continue;
			}
			String ws = line.substring(0, line.length() - line.stripLeading().length());
			if(ws.startsWith(parentIndent) && ws.length() > parentIndent.length())
			{
				return ws.substring(parentIndent.length());
			}
		}
		return "\t";
	}

	private static String esc(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
