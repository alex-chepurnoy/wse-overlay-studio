/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.templates;

import java.nio.file.Path;

/**
 * Where the module keeps its files: under &lt;vhost dir&gt;/conf.modules/overlaystudio/, beside VIF's conf.modules/vif/, one
 * folder per application. WSE's update tool copies only the files a release ships and deletes a fixed list, none of
 * them under conf.modules (updatetool ManifestBuilderConfig and updatemanifest.umf).
 */
public final class ModuleFiles
{
	public static final String CONF_MODULES = "conf.modules";
	public static final String MODULE = "overlaystudio";
	public static final String STREAMS = "streams";

	private ModuleFiles()
	{
	}

	/** A name from a request that cannot be used as one folder or file name; REST answers it with 400. */
	public static class BadName extends IllegalArgumentException
	{
		private static final long serialVersionUID = 1L;

		public BadName(String message)
		{
			super(message);
		}
	}

	/**
	 * A virtual host, application, instance or stream name as one path segment: not empty, not . or .., and without a
	 * path separator or control character. WSE stream names allow far more than TemplateFiles.safeName does.
	 */
	public static String segment(String what, String name)
	{
		String n = name == null ? "" : name.trim();
		if(n.isEmpty() || n.equals(".") || n.equals("..") || n.length() > 200 || n.chars().anyMatch(c -> c == '/' || c == '\\' || c < 0x20 || c == 0x7f))
		{
			throw new BadName("Not a usable " + what + " name: '" + name + "'");
		}
		return n;
	}

	/** The path, refused unless it is inside root once normalized. */
	public static Path inside(Path root, Path p)
	{
		Path r = root.toAbsolutePath().normalize();
		Path n = p.toAbsolutePath().normalize();
		if(!n.startsWith(r) || n.equals(r))
		{
			throw new BadName("Path leaves " + r + ": " + p);
		}
		return n;
	}

	public static Path root(Path vhostDir)
	{
		return vhostDir.resolve(CONF_MODULES).resolve(MODULE);
	}

	/** An application's folder: its named overlays, and streams/ for per-stream overlays. */
	public static Path appDir(Path vhostDir, String app)
	{
		return inside(root(vhostDir), root(vhostDir).resolve(segment("application", app)));
	}

	/** A stream's own overlay file: streams/&lt;instance&gt;/&lt;stream&gt;.json. */
	public static Path streamFile(Path vhostDir, String app, String instance, String stream)
	{
		Path app0 = appDir(vhostDir, app);
		return inside(app0, app0.resolve(STREAMS).resolve(segment("instance", instance)).resolve(segment("stream", stream) + ".json"));
	}
}
