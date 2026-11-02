/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.templates;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Template files in a transcoder/templates directory: the starters bundled in the jar, copies for one stream, and
 * the name rules that keep every write inside that directory. A template the module did not write (no
 * "Managed by Overlay Studio" in its Description) is never overwritten.
 */
public final class TemplateFiles
{
	public static final List<String> STARTERS = List.of("overlaystudio-cpu", "overlaystudio-gpu-eva", "overlaystudio-passthrough");
	/** The templates WSE installs (extras/transcoder/templates in the WSE source); they are copied, never changed in place. */
	public static final List<String> WSE_SHIPPED = List.of("audioonly", "transcode", "transcode-h265", "transcode-h265HDRtoSDR", "transrate");
	private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
	private static final Pattern DESCRIPTION = Pattern.compile("(?s)(<Transcode>\\s*<Description>)(.*?)(</Description>)");

	private TemplateFiles()
	{
	}

	public static class Refused extends Exception
	{
		private final int status;
		// the layer and field refused, when it is one (plan 3.10: a 400 names the field)
		private final String id, field;

		public Refused(int status, String message)
		{
			this(status, message, null, null);
		}

		public Refused(int status, String message, String id, String field)
		{
			super(message);
			this.status = status;
			this.id = id;
			this.field = field;
		}

		public int status()
		{
			return status;
		}

		public String id()
		{
			return id;
		}

		public String field()
		{
			return field;
		}
	}

	/**
	 * Accepts a template or stream name that is safe as a file name in the templates directory: letters, digits,
	 * dot, dash and underscore, starting with a letter or digit. A ".xml" suffix is allowed and stripped.
	 */
	public static String safeName(String name) throws Refused
	{
		if(name == null)
		{
			throw new Refused(400, "Name is required");
		}
		String n = name.trim();
		if(n.toLowerCase(Locale.ROOT).endsWith(".xml"))
		{
			n = n.substring(0, n.length() - 4);
		}
		if(!SAFE_NAME.matcher(n).matches() || n.contains(".."))
		{
			throw new Refused(400, "Not a usable template name: '" + name + "'. Use letters, digits, dot, dash and underscore.");
		}
		return n;
	}

	public static byte[] starter(String starter) throws Refused, IOException
	{
		if(!STARTERS.contains(starter))
		{
			throw new Refused(404, "No starter template '" + starter + "'");
		}
		try(InputStream in = TemplateFiles.class.getResourceAsStream("/starters/" + starter + ".xml"))
		{
			if(in == null)
			{
				throw new IOException("starter resource missing: " + starter);
			}
			return in.readAllBytes();
		}
	}

	/** The starters with their validation, for listing. */
	public static List<Map<String, Object>> describeStarters() throws IOException
	{
		List<Map<String, Object>> out = new ArrayList<>();
		for(String s: STARTERS)
		{
			try
			{
				byte[] xml = starter(s);
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("name", s);
				m.putAll(TemplateValidator.validate(new ByteArrayInputStream(xml)).toMap());
				m.put("xml", new String(xml, StandardCharsets.UTF_8));
				out.add(m);
			}
			catch(Refused e)
			{
				// STARTERS lists only bundled names
			}
		}
		return out;
	}

	/** Writes a starter as name.xml. @return the file written */
	public static synchronized Path writeStarter(Path templatesDir, String starter, String name, boolean overwrite) throws Refused, IOException
	{
		byte[] xml = starter(starter);
		return write(templatesDir, safeName(name), xml, overwrite);
	}

	/**
	 * Writes a copy of an existing template as stream.xml for one stream, its Description marked as managed and
	 * naming its source.
	 */
	public static synchronized Path copyForStream(Path templatesDir, String sourceTemplate, String stream, boolean overwrite) throws Refused, IOException
	{
		String dst = safeName(stream);
		return write(templatesDir, dst, copyXml(templatesDir, sourceTemplate, dst).getBytes(StandardCharsets.UTF_8), overwrite);
	}

	// what copyForStream writes for stream from sourceTemplate as it is now: the same file with its description marked
	private static String copyXml(Path templatesDir, String sourceTemplate, String stream) throws Refused, IOException
	{
		String src = safeName(sourceTemplate);
		String dst = safeName(stream);
		Path from = templatesDir.resolve(src + ".xml");
		if(!Files.isRegularFile(from))
		{
			throw new Refused(404, "No template '" + src + "' in " + templatesDir);
		}
		String xml = Files.readString(from, StandardCharsets.UTF_8);
		String marked = TemplateValidator.MANAGED_MARKER + ": copy of " + src + " for stream " + dst + ".";
		Matcher m = DESCRIPTION.matcher(xml);
		if(m.find())
		{
			xml = xml.substring(0, m.start(2)) + escape(marked) + xml.substring(m.end(2));
		}
		else
		{
			xml = xml.replaceFirst("<Transcode>", "<Transcode>\n\t\t<Description>" + Matcher.quoteReplacement(escape(marked)) + "</Description>");
		}
		return xml;
	}

	/**
	 * Whether a stream's copy is still what an update from its master would write, or null when the master is gone.
	 * Any difference counts: the master saved since, or the copy changed by hand.
	 */
	public static Boolean copyIsCurrent(Path templatesDir, String base, String stream) throws IOException
	{
		try
		{
			Path copy = templatesDir.resolve(safeName(stream) + ".xml");
			if(!Files.isRegularFile(templatesDir.resolve(safeName(base) + ".xml")) || !Files.isRegularFile(copy))
			{
				return null;
			}
			return copyXml(templatesDir, base, stream).equals(Files.readString(copy, StandardCharsets.UTF_8));
		}
		catch(Refused e)
		{
			return null;
		}
	}

	/** The master a copy names, when the file is the copy for the stream it names; null for any other template. */
	public static String copyBase(String fileName, String description)
	{
		Matcher m = description == null ? null : COPY_OF.matcher(description);
		return m != null && m.find() && fileName.equals(m.group(2) + ".xml") ? m.group(1) : null;
	}

	private static final Pattern COPY_OF = Pattern.compile("copy of ([A-Za-z0-9._-]+) for stream ([A-Za-z0-9._-]+)\\.");

	/**
	 * Sets, or with a null overlay removes, the overlay in slot 1 and the encodes that carry it (none listed: drawn on
	 * the source, so every encode gets it), keeping slots 2 to 4. Only on templates the module wrote.
	 */
	public static synchronized void setOverlay(Path templatesDir, String template, String overlay, List<String> encodes) throws Refused, IOException
	{
		List<TemplateValidator.Slot> slots = new ArrayList<>(slotsOf(templatesDir, template));
		slots.removeIf(x -> x.slot() == 1);
		if(overlay != null)
		{
			slots.add(new TemplateValidator.Slot(1, overlay, encodes == null ? List.of() : List.copyOf(encodes)));
		}
		setOverlays(templatesDir, template, slots);
	}

	/** The overlays a template carries now, in slot order. */
	public static List<TemplateValidator.Slot> slotsOf(Path templatesDir, String template) throws Refused, IOException
	{
		Path file = templatesDir.resolve(safeName(template) + ".xml");
		if(!Files.isRegularFile(file))
		{
			throw new Refused(404, "No template '" + safeName(template) + "'");
		}
		try(InputStream in = Files.newInputStream(file))
		{
			return TemplateValidator.validate(in).overlays();
		}
	}

	/**
	 * The slots with overlay on them: where it already is, with these encodes, else in the lowest free slot.
	 * Refused when all SLOTS are taken.
	 */
	public static List<TemplateValidator.Slot> withAdded(List<TemplateValidator.Slot> slots, String template, String overlay, List<String> encodes) throws Refused
	{
		safeName(overlay);
		List<String> on = encodes == null ? List.of() : List.copyOf(encodes);
		List<TemplateValidator.Slot> out = new ArrayList<>();
		boolean placed = false;
		for(TemplateValidator.Slot x: slots)
		{
			if(x.name().equals(overlay))
			{
				out.add(new TemplateValidator.Slot(x.slot(), overlay, on));
				placed = true;
			}
			else
			{
				out.add(x);
			}
		}
		for(int n = 1; !placed && n <= TemplateValidator.SLOTS; n++)
		{
			final int slot = n;
			if(slots.stream().noneMatch(x -> x.slot() == slot))
			{
				out.add(new TemplateValidator.Slot(n, overlay, on));
				placed = true;
			}
		}
		if(!placed)
		{
			throw new Refused(409, template + " already carries " + TemplateValidator.SLOTS + " overlays; take one off first.");
		}
		out.sort(java.util.Comparator.comparingInt(TemplateValidator.Slot::slot));
		return out;
	}

	/**
	 * The overlays a request names, as {"overlays": [{name, encodes, slot}]}: slot defaults to the entry's position.
	 * Refused (400) for more than SLOTS, a slot outside 1 to SLOTS or used twice, or an overlay named twice.
	 */
	@SuppressWarnings("unchecked")
	public static List<TemplateValidator.Slot> slotsFrom(Object raw) throws Refused
	{
		if(!(raw instanceof List<?> list))
		{
			throw new Refused(400, "overlays must be a list of {\"name\", \"encodes\", \"slot\"}", null, "overlays");
		}
		if(list.size() > TemplateValidator.SLOTS)
		{
			throw new Refused(400, "A template carries at most " + TemplateValidator.SLOTS + " overlays", null, "overlays");
		}
		List<TemplateValidator.Slot> out = new ArrayList<>();
		for(int i = 0; i < list.size(); i++)
		{
			if(!(list.get(i) instanceof Map<?, ?> m) || !(m.get("name") instanceof String name) || name.isBlank())
			{
				throw new Refused(400, "overlays[" + i + "] needs a name", null, "overlays");
			}
			int slot = i + 1;
			if(m.get("slot") != null)
			{
				if(!(m.get("slot") instanceof Number n) || n.intValue() != n.doubleValue())
				{
					throw new Refused(400, "overlays[" + i + "]: slot must be a whole number", null, "overlays");
				}
				slot = n.intValue();
			}
			List<String> encodes = new ArrayList<>();
			if(m.get("encodes") instanceof List)
			{
				for(Object e: (List<Object>)m.get("encodes"))
				{
					encodes.add(String.valueOf(e));
				}
			}
			else if(m.get("encodes") != null)
			{
				throw new Refused(400, "overlays[" + i + "]: encodes must be a list of encode names", null, "overlays");
			}
			out.add(new TemplateValidator.Slot(slot, safeName(name.trim()), encodes));
		}
		checkSlots(out);
		return out;
	}

	private static void checkSlots(List<TemplateValidator.Slot> slots) throws Refused
	{
		java.util.Set<Integer> used = new java.util.HashSet<>();
		java.util.Set<String> names = new java.util.HashSet<>();
		for(TemplateValidator.Slot x: slots)
		{
			if(x.slot() < 1 || x.slot() > TemplateValidator.SLOTS)
			{
				throw new Refused(400, "Overlay " + x.name() + ": slot must be from 1 to " + TemplateValidator.SLOTS, null, "overlays");
			}
			if(!used.add(x.slot()))
			{
				throw new Refused(400, "Slot " + x.slot() + " is given two overlays", null, "overlays");
			}
			if(!names.add(x.name()))
			{
				throw new Refused(400, "Overlay " + x.name() + " is named twice; a template carries each overlay once", null, "overlays");
			}
		}
	}

	/**
	 * Sets the overlays a template carries, replacing every overlayStudio1 to 4 property (and its encodes) with these
	 * slots. Only on templates the module wrote. Other content is left as it is; new elements get the file's tab
	 * indentation.
	 */
	public static synchronized void setOverlays(Path templatesDir, String template, List<TemplateValidator.Slot> slots) throws Refused, IOException
	{
		String t = safeName(template);
		Path file = templatesDir.resolve(t + ".xml");
		if(!Files.isRegularFile(file))
		{
			throw new Refused(404, "No template '" + t + "'");
		}
		if(!isManaged(file))
		{
			throw new Refused(409, t + ".xml was not written by Overlay Studio; it is not changed.");
		}
		for(TemplateValidator.Slot x: slots)
		{
			safeName(x.name());
		}
		checkSlots(slots);
		List<TemplateValidator.Slot> sorted = new ArrayList<>(slots);
		sorted.sort(java.util.Comparator.comparingInt(TemplateValidator.Slot::slot));
		try
		{
			Document doc = parse(Files.readAllBytes(file), t);
			Element transcode = firstChild(doc.getDocumentElement(), "Transcode");
			Element props = firstChild(transcode, "Properties");
			if(props == null)
			{
				props = doc.createElement("Properties");
				// Transcode's last child is the indentation before </Transcode>
				Node closing = transcode.getLastChild();
				transcode.insertBefore(doc.createTextNode("\n\t\t"), closing);
				transcode.insertBefore(props, closing);
				props.appendChild(doc.createTextNode("\n\t\t"));
			}
			for(int n = 1; n <= TemplateValidator.SLOTS; n++)
			{
				removeProperty(props, TemplateValidator.propOverlay(n));
				removeProperty(props, TemplateValidator.propEncodes(n));
			}
			for(TemplateValidator.Slot x: sorted)
			{
				addProperty(doc, props, TemplateValidator.propOverlay(x.slot()), x.name());
				addProperty(doc, props, TemplateValidator.propEncodes(x.slot()), String.join(",", x.encodes()));
			}
			replace(file, serialize(doc, Files.readString(file, StandardCharsets.UTF_8).contains("\r\n")).getBytes(StandardCharsets.UTF_8));
		}
		catch(Refused e)
		{
			throw e;
		}
		catch(Exception e)
		{
			throw new IOException("Could not update " + t + ".xml: " + e.getMessage(), e);
		}
	}

	/** A template's DOM; Refused (422) when it does not parse or has no Root/Transcode. */
	private static Document parse(byte[] xml, String name) throws Refused
	{
		Document doc;
		try
		{
			doc = TemplateValidator.newBuilder().parse(new ByteArrayInputStream(xml));
		}
		catch(Exception e)
		{
			throw new Refused(422, name + ".xml is not a readable template: " + e.getMessage());
		}
		if(firstChild(doc.getDocumentElement(), "Transcode") == null)
		{
			throw new Refused(422, name + ".xml has no Root/Transcode");
		}
		return doc;
	}

	private static String serialize(Document doc, boolean crlf) throws Exception
	{
		Transformer tf = TransformerFactory.newInstance().newTransformer();
		tf.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
		StringWriter w = new StringWriter();
		tf.transform(new DOMSource(doc), new StreamResult(w));
		// the transformer writes the platform's line separator; keep the file's own
		String out = w.toString().replace("\r\n", "\n");
		return crlf ? out.replace("\n", "\r\n") : out;
	}

	/** Sets Decode/Video/EnableDynamicOverlays to true, adding the elements that are missing. */
	static void enableDynamicOverlays(Document doc)
	{
		Element transcode = firstChild(doc.getDocumentElement(), "Transcode");
		Element decode = child(doc, transcode, "Decode", "\n\t\t");
		Element video = child(doc, decode, "Video", "\n\t\t\t");
		Element dyn = child(doc, video, "EnableDynamicOverlays", "\n\t\t\t\t");
		dyn.setTextContent("true");
	}

	// the child, added before the parent's closing indentation when missing
	private static Element child(Document doc, Element parent, String tag, String indent)
	{
		Element c = firstChild(parent, tag);
		if(c != null)
		{
			return c;
		}
		c = doc.createElement(tag);
		Node last = parent.getLastChild();
		if(last != null && last.getNodeType() == Node.TEXT_NODE && last.getTextContent().isBlank())
		{
			parent.insertBefore(doc.createTextNode(indent), last);
			parent.insertBefore(c, last);
		}
		else
		{
			parent.appendChild(doc.createTextNode(indent));
			parent.appendChild(c);
			parent.appendChild(doc.createTextNode(indent.substring(0, indent.length() - 1)));
		}
		return c;
	}

	// Description starts with the managed marker and the note; the template's own description is kept after it
	private static void markManaged(Document doc, String note)
	{
		Element transcode = firstChild(doc.getDocumentElement(), "Transcode");
		Element d = firstChild(transcode, "Description");
		String old = d == null ? "" : d.getTextContent().trim();
		if(d == null)
		{
			d = doc.createElement("Description");
			transcode.insertBefore(d, transcode.getFirstChild());
			transcode.insertBefore(doc.createTextNode("\n\t\t"), d);
		}
		d.setTextContent(TemplateValidator.MANAGED_MARKER + ": " + note + (old.isEmpty() ? "" : " " + old));
	}

	/** Writes a template built in the page's builder; replacing one is allowed only for a template the module wrote. */
	public static synchronized Path writeBuilt(Path templatesDir, TemplateBuilder.Spec spec, boolean replace) throws Refused, IOException
	{
		return write(templatesDir, spec.name(), TemplateBuilder.xml(spec).getBytes(StandardCharsets.UTF_8), replace);
	}

	/**
	 * Copies a template as newName with dynamic overlays on, keeping the overlays it carries and adding overlay in
	 * the next free slot (or giving it these encodes where it already is), leaving the original as it is.
	 */
	public static synchronized Path copyWithOverlay(Path templatesDir, String source, String newName, String overlay, List<String> encodes) throws Refused, IOException
	{
		String src = safeName(source);
		String dst = safeName(newName);
		if(overlay != null)
		{
			safeName(overlay);
		}
		Path from = templatesDir.resolve(src + ".xml");
		if(!Files.isRegularFile(from))
		{
			throw new Refused(404, "No template '" + src + "'");
		}
		byte[] bytes = Files.readAllBytes(from);
		Document doc = parse(bytes, src);
		enableDynamicOverlays(doc);
		markManaged(doc, "copy of " + src + " with overlays.");
		String text;
		try
		{
			text = serialize(doc, new String(bytes, StandardCharsets.UTF_8).contains("\r\n"));
		}
		catch(Exception e)
		{
			throw new IOException("Could not copy " + src + ".xml: " + e.getMessage(), e);
		}
		List<TemplateValidator.Slot> slots = TemplateValidator.validate(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))).overlays();
		if(overlay != null)
		{
			// checked before the copy is written, so a full template leaves no copy behind
			slots = withAdded(slots, src, overlay, encodes);
		}
		Path written = write(templatesDir, dst, text.getBytes(StandardCharsets.UTF_8), false);
		setOverlays(templatesDir, dst, slots);
		return written;
	}

	/**
	 * Switches dynamic overlays on in a customer's own template and adds the overlay in the next free slot (or gives
	 * it these encodes where it already is), in place. Not for the templates WSE installs, nor VIF's. @return the file's bytes before the change, for the caller's backup
	 */
	public static synchronized byte[] enableInPlace(Path templatesDir, String template, String overlay, List<String> encodes) throws Refused, IOException
	{
		String t = safeName(template);
		if(overlay != null)
		{
			safeName(overlay);
		}
		Path file = templatesDir.resolve(t + ".xml");
		if(!Files.isRegularFile(file))
		{
			throw new Refused(404, "No template '" + t + "'");
		}
		if(WSE_SHIPPED.contains(t))
		{
			throw new Refused(409, t + ".xml ships with WSE; make a copy instead, so WSE's own stays as it is.");
		}
		byte[] before = Files.readAllBytes(file);
		TemplateValidator.Result was = TemplateValidator.validate(new ByteArrayInputStream(before));
		if(was.vif())
		{
			throw new Refused(409, t + ".xml is a Video Intelligence template; a stream runs one or the other.");
		}
		List<TemplateValidator.Slot> slots = overlay == null ? was.overlays() : withAdded(was.overlays(), t, overlay, encodes);
		if(!isManaged(file))
		{
			Document doc = parse(before, t);
			enableDynamicOverlays(doc);
			markManaged(doc, "overlays switched on in place.");
			try
			{
				replace(file, serialize(doc, new String(before, StandardCharsets.UTF_8).contains("\r\n")).getBytes(StandardCharsets.UTF_8));
			}
			catch(Exception e)
			{
				throw new IOException("Could not update " + t + ".xml: " + e.getMessage(), e);
			}
		}
		setOverlays(templatesDir, t, slots);
		return before;
	}

	private static Element firstChild(Element parent, String tag)
	{
		for(Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
		{
			if(n instanceof Element e && e.getTagName().equals(tag))
			{
				return e;
			}
		}
		return null;
	}

	private static void removeProperty(Element props, String name)
	{
		for(Node n = props.getFirstChild(); n != null;)
		{
			Node next = n.getNextSibling();
			if(n instanceof Element e && e.getTagName().equals("Property"))
			{
				Element nm = firstChild(e, "Name");
				if(nm != null && name.equals(nm.getTextContent().trim()))
				{
					// take the indentation before it too
					Node prev = e.getPreviousSibling();
					if(prev != null && prev.getNodeType() == Node.TEXT_NODE && prev.getTextContent().isBlank())
					{
						props.removeChild(prev);
					}
					props.removeChild(e);
				}
			}
			n = next;
		}
	}

	// Adds a Property in WSE's layout (Name, Value, Type), before the indentation that closes Properties.
	private static void addProperty(Document doc, Element props, String name, String value)
	{
		Element p = doc.createElement("Property");
		for(String[] kv: new String[][] { { "Name", name }, { "Value", value }, { "Type", "String" } })
		{
			p.appendChild(doc.createTextNode("\n\t\t\t\t"));
			Element c = doc.createElement(kv[0]);
			c.setTextContent(kv[1]);
			p.appendChild(c);
		}
		p.appendChild(doc.createTextNode("\n\t\t\t"));
		Node last = props.getLastChild();
		if(last == null || last.getNodeType() != Node.TEXT_NODE || !last.getTextContent().isBlank())
		{
			last = doc.createTextNode("\n\t\t");
			props.appendChild(last);
		}
		props.insertBefore(doc.createTextNode("\n\t\t\t"), last);
		props.insertBefore(p, last);
	}

	/** The per-stream copies made from base by copyForStream, as template and stream names. */
	public static List<Map<String, String>> copiesOf(Path templatesDir, String base) throws Refused, IOException
	{
		String b = safeName(base);
		List<Map<String, String>> out = new ArrayList<>();
		for(Path f: xmlFiles(templatesDir))
		{
			String n = f.getFileName().toString();
			Matcher m = COPY_OF.matcher(Files.readString(f, StandardCharsets.UTF_8));
			if(m.find() && m.group(1).equals(b) && n.equals(m.group(2) + ".xml"))
			{
				Map<String, String> c = new LinkedHashMap<>();
				c.put("template", m.group(2));
				c.put("stream", m.group(2));
				out.add(c);
			}
		}
		return out;
	}

	/** The templates whose properties name this overlay in any slot, stream copies included. */
	public static List<String> templatesUsing(Path templatesDir, String overlay) throws IOException
	{
		List<String> out = new ArrayList<>();
		for(Path f: xmlFiles(templatesDir))
		{
			try(InputStream in = Files.newInputStream(f))
			{
				if(TemplateValidator.validate(in).overlays().stream().anyMatch(x -> overlay.equals(x.name())))
				{
					String n = f.getFileName().toString();
					out.add(n.substring(0, n.length() - 4));
				}
			}
		}
		return out;
	}

	private static List<Path> xmlFiles(Path dir) throws IOException
	{
		List<Path> out = new ArrayList<>();
		if(Files.isDirectory(dir))
		{
			try(Stream<Path> files = Files.list(dir))
			{
				files.filter(f -> f.getFileName().toString().endsWith(".xml")).sorted().forEach(out::add);
			}
		}
		return out;
	}

	public static boolean isManaged(Path file) throws IOException
	{
		return Files.isRegularFile(file) && Files.readString(file, StandardCharsets.UTF_8).contains(TemplateValidator.MANAGED_MARKER);
	}

	private static Path write(Path templatesDir, String name, byte[] xml, boolean overwrite) throws Refused, IOException
	{
		Path target = templatesDir.resolve(name + ".xml").normalize();
		if(!target.getParent().equals(templatesDir.normalize()))
		{
			throw new Refused(400, "Template name leaves the templates directory: " + name);
		}
		if(Files.exists(target))
		{
			if(!isManaged(target))
			{
				throw new Refused(409, name + ".xml exists and is not managed by Overlay Studio; it is not overwritten.");
			}
			if(!overwrite)
			{
				throw new Refused(409, name + ".xml exists; set overwrite to replace it.");
			}
		}
		Files.createDirectories(templatesDir);
		replace(target, xml);
		return target;
	}

	// Writes beside the file and moves it into place, so WSE starting a transcoder never reads half a template.
	private static void replace(Path file, byte[] bytes) throws IOException
	{
		Path tmp = Files.createTempFile(file.getParent(), "." + file.getFileName(), ".tmp");
		try
		{
			Files.write(tmp, bytes);
			try
			{
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch(java.nio.file.AtomicMoveNotSupportedException e)
			{
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		finally
		{
			Files.deleteIfExists(tmp);
		}
	}

	private static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
