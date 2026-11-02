/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wowza.wms.plugin.overlaystudio.templates.ModuleFiles;
import com.wowza.wms.rest.vhosts.VHostsConfig;

/**
 * The number of pages the capture service captures at once, as set from the page. The service starts with
 * capture.properties maxCaptures; this value replaces it at run time, and goes back to the service whenever the
 * module connects to it again, for example after the service restarted. Kept in
 * _defaultVHost_/conf.modules/overlaystudio/capture-limit.json, because the service is one per server.
 */
public final class CaptureLimit
{
	static final String FILE = "capture-limit.json";
	private static final String VHOST = "_defaultVHost_";
	private static final ObjectMapper JSON = new ObjectMapper();
	/** Held while the limit is changed or sent to the service, so the two never cross. */
	public static final Object LOCK = new Object();
	private static final Object lock = LOCK;
	private static boolean loaded = false;
	private static Integer wanted = null;
	// the service start the limit was last sent to, and why that service refused it, if it did
	private static volatile String sentTo = null;
	private static volatile String refused = null;
	// replaced by tests
	static volatile Path dir = null;

	private CaptureLimit()
	{
	}

	private static Path file()
	{
		Path d = dir;
		if(d == null)
		{
			String v = VHostsConfig.getVHostDir(VHOST);
			if(v == null)
			{
				return null;
			}
			d = ModuleFiles.root(Paths.get(v));
		}
		return d.resolve(FILE);
	}

	/** The limit set from the page, or null when it was never set and capture.properties decides. */
	public static Integer wanted()
	{
		synchronized(lock)
		{
			if(!loaded)
			{
				loaded = true;
				try
				{
					Path f = file();
					if(f != null && Files.isRegularFile(f))
					{
						Object n = JSON.readValue(Files.readString(f, StandardCharsets.UTF_8), Map.class).get("maxCaptures");
						wanted = n instanceof Number num ? Integer.valueOf(num.intValue()) : null;
					}
				}
				catch(IOException | RuntimeException e)
				{
					OverlayStudioLog.info(FILE + " could not be read, capture.properties decides: " + e.getMessage());
				}
			}
			return wanted;
		}
	}

	/** The service start (its serverTag) the limit was last given to. */
	static String sentTo()
	{
		return sentTo;
	}

	/** Why the service refused the limit sent to it, or null when it took it. */
	public static String refused()
	{
		return refused;
	}

	/** Records a send to the service started as tag, and its refusal; a new refusal is logged once. */
	static void sent(String tag, String refusal)
	{
		if(refusal != null && !refusal.equals(refused))
		{
			OverlayStudioLog.info("the capture service refused the page limit " + wanted + " set from the page, so it runs at its capture.properties value: " + refusal);
		}
		sentTo = tag;
		refused = refusal;
	}

	/** Puts back what was kept before a change the service refused: a limit, or none (capture.properties decides). */
	public static void restore(Integer previous) throws IOException
	{
		synchronized(lock)
		{
			if(previous != null)
			{
				save(previous);
				return;
			}
			Path f = file();
			if(f != null)
			{
				Files.deleteIfExists(f);
			}
			wanted = null;
			loaded = true;
		}
	}

	/** The service took this limit just now. */
	public static void applied()
	{
		refused = null;
	}

	/** Keeps the limit for the next connection to the service. */
	public static void save(int maxCaptures) throws IOException
	{
		synchronized(lock)
		{
			Path f = file();
			if(f == null)
			{
				throw new IOException("No " + VHOST + " folder to keep the setting in");
			}
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling(FILE + ".tmp");
			Files.writeString(tmp, JSON.writeValueAsString(Map.of("maxCaptures", maxCaptures)), StandardCharsets.UTF_8);
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			wanted = maxCaptures;
			loaded = true;
		}
	}
}
