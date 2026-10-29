/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** A config file's text with its byte order mark and line ending, so writing it back changes only what was edited. */
public record TextFile(String text, boolean bom, String eol)
{
	private static final byte[] BOM = { (byte)0xEF, (byte)0xBB, (byte)0xBF };

	public static TextFile read(Path p) throws IOException
	{
		return of(Files.readAllBytes(p));
	}

	public static TextFile of(byte[] bytes)
	{
		boolean bom = bytes.length >= 3 && bytes[0] == BOM[0] && bytes[1] == BOM[1] && bytes[2] == BOM[2];
		String text = new String(bytes, bom ? 3 : 0, bytes.length - (bom ? 3 : 0), StandardCharsets.UTF_8);
		return new TextFile(text, bom, text.contains("\r\n") ? "\r\n" : "\n");
	}

	public TextFile with(String newText)
	{
		return new TextFile(newText, bom, eol);
	}

	public byte[] bytes()
	{
		byte[] body = text.getBytes(StandardCharsets.UTF_8);
		if(!bom)
		{
			return body;
		}
		byte[] out = new byte[body.length + 3];
		System.arraycopy(BOM, 0, out, 0, 3);
		System.arraycopy(body, 0, out, 3, body.length);
		return out;
	}

	public static String sha256(byte[] b)
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
		}
		catch(Exception e)
		{
			throw new IllegalStateException(e);
		}
	}

	public static String sha256(Path p) throws IOException
	{
		return sha256(Files.readAllBytes(p));
	}
}
