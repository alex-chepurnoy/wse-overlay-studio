/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.install;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** The files the installer jar carries under payload/, listed in payload/index.txt at build time. */
final class Payload
{
	private final List<String> files = new ArrayList<>();
	private final String version;

	Payload() throws IOException
	{
		String index = read("index.txt");
		if(index == null)
		{
			throw new IOException("This installer jar has no payload (payload/index.txt missing)");
		}
		for(String l: index.split("\n"))
		{
			if(!l.isBlank())
			{
				files.add(l.trim());
			}
		}
		String v = read("version.txt");
		version = v == null ? "unknown" : v.trim();
	}

	String version()
	{
		return version;
	}

	/** Payload paths under a folder, for example "lib/" or "ui/". */
	List<String> under(String folder)
	{
		List<String> out = new ArrayList<>();
		for(String f: files)
		{
			if(f.startsWith(folder))
			{
				out.add(f);
			}
		}
		return out;
	}

	byte[] bytes(String path) throws IOException
	{
		try(InputStream in = Payload.class.getResourceAsStream("/payload/" + path))
		{
			if(in == null)
			{
				throw new IOException("Payload file missing: " + path);
			}
			return in.readAllBytes();
		}
	}

	private static String read(String path) throws IOException
	{
		try(InputStream in = Payload.class.getResourceAsStream("/payload/" + path))
		{
			return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
