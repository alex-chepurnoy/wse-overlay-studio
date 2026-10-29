/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.capture;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The capture protocol between the module and the capture service, over a Unix domain socket. Each message is one
 * frame: a 4-byte magic, the header's length and the body's length as big-endian ints, the header as UTF-8 JSON, then
 * the body's bytes (a PNG for a frame, empty otherwise). Requests and answers alternate on a connection; the module
 * keeps a few connections open so a slow request does not hold up the others.
 * <p>
 * Every connection starts with hello: the client sends {"op":"hello","protocol":1,"version":...} and the service answers
 * with its own protocol and version, or an error, and closes, when the protocols differ. Every answer carries "ok"; a
 * failed one carries "error", a message meant for an operator.
 */
public final class Wire
{
	/** Raised with any change to the frame layout or to what an operation expects or answers. */
	public static final int PROTOCOL = 2;
	static final int MAGIC = 0x554f4331; // "UOC1"
	public static final int MAX_HEADER = 1 << 20;
	public static final int MAX_BODY = 64 << 20;

	public static final String OP = "op";
	public static final String HELLO = "hello";
	public static final String OPEN = "open";
	public static final String UPDATE = "update";
	public static final String FRAME = "frame";
	public static final String STATUS = "status";
	public static final String CLOSE = "close";

	private Wire()
	{
	}

	/** One frame: its header and its body, never null (empty when there is none). */
	public record Message(Map<String, Object> header, byte[] body)
	{
		public Message(Map<String, Object> header)
		{
			this(header, new byte[0]);
		}

		public String str(String key)
		{
			Object v = header.get(key);
			return v == null ? null : v.toString();
		}

		public long num(String key, long dflt)
		{
			return header.get(key) instanceof Number n ? n.longValue() : dflt;
		}

		public boolean ok()
		{
			return Boolean.TRUE.equals(header.get("ok"));
		}
	}

	public static void write(OutputStream out, Map<String, Object> header, byte[] body) throws IOException
	{
		byte[] h = Json.write(header).getBytes(StandardCharsets.UTF_8);
		byte[] b = body == null ? new byte[0] : body;
		if(h.length > MAX_HEADER || b.length > MAX_BODY)
		{
			throw new IOException("Capture message too large: header " + h.length + ", body " + b.length + " bytes");
		}
		DataOutputStream d = new DataOutputStream(out);
		d.writeInt(MAGIC);
		d.writeInt(h.length);
		d.writeInt(b.length);
		d.write(h);
		d.write(b);
		d.flush();
	}

	/** The next frame, or null when the other side closed the connection cleanly before one began. */
	public static Message read(InputStream in) throws IOException
	{
		DataInputStream d = new DataInputStream(in);
		int magic;
		try
		{
			magic = d.readInt();
		}
		catch(EOFException e)
		{
			return null;
		}
		if(magic != MAGIC)
		{
			throw new IOException("Not a capture protocol frame");
		}
		int hl = d.readInt(), bl = d.readInt();
		if(hl < 0 || hl > MAX_HEADER || bl < 0 || bl > MAX_BODY)
		{
			throw new IOException("Capture frame size out of range: header " + hl + ", body " + bl);
		}
		byte[] h = new byte[hl];
		d.readFully(h);
		byte[] b = new byte[bl];
		d.readFully(b);
		try
		{
			return new Message(Json.object(new String(h, StandardCharsets.UTF_8)), b);
		}
		catch(IllegalArgumentException e)
		{
			throw new IOException("Unreadable capture frame header: " + e.getMessage());
		}
	}

	public static Map<String, Object> header(String op)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put(OP, op);
		return m;
	}

	public static Map<String, Object> ok()
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("ok", true);
		return m;
	}

	public static Map<String, Object> error(String message)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("ok", false);
		m.put("error", message);
		return m;
	}

	/** The answer to a hello, or null when the protocols match. */
	public static String protocolMismatch(Object theirs, String side)
	{
		long t = theirs instanceof Number n ? n.longValue() : -1;
		return t == PROTOCOL ? null
				: "The URL Overlay " + side + " speaks capture protocol " + (t < 0 ? "unknown" : String.valueOf(t)) + " and this side speaks "
						+ PROTOCOL + ". Install the same URL Overlay version for the module and the capture service.";
	}
}
