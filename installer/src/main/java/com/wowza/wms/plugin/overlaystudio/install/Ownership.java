/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;

/**
 * On Linux, gives every file and folder the installer creates the owner and group of conf/Server.xml, so a WSE that runs
 * as its own user can write where the module keeps its files. Files the installer rewrites in place keep their owner
 * anyway. On Windows, and when the installer already runs as that owner, it does nothing.
 */
class Ownership
{
	/** Sets owner and group on one path. */
	interface Setter
	{
		void set(Path p, UserPrincipal owner, GroupPrincipal group) throws IOException;
	}

	/** Tests only: replaces the one {@link #forHome} would build. */
	static volatile java.util.function.Function<Path, Ownership> testFactory;

	static final Ownership NONE = new Ownership(null, null, null);

	private final UserPrincipal owner;
	private final GroupPrincipal group;
	private final Setter setter;
	private final List<String> failures = new ArrayList<>();

	Ownership(UserPrincipal owner, GroupPrincipal group, Setter setter)
	{
		this.owner = owner;
		this.group = group;
		this.setter = setter;
	}

	static Ownership forHome(Path home)
	{
		if(testFactory != null)
		{
			return testFactory.apply(home);
		}
		if(Preflight.WINDOWS)
		{
			return NONE;
		}
		try
		{
			PosixFileAttributes a = Files.readAttributes(home.resolve("conf").resolve("Server.xml"), PosixFileAttributes.class);
			if(a.owner().getName().equals(System.getProperty("user.name")))
			{
				return NONE;
			}
			return new Ownership(a.owner(), a.group(), (p, o, g) -> {
				PosixFileAttributeView v = Files.getFileAttributeView(p, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
				v.setOwner(o);
				v.setGroup(g);
			});
		}
		catch(IOException | UnsupportedOperationException e)
		{
			return NONE;
		}
	}

	boolean active()
	{
		return setter != null;
	}

	/** Gives one new file or folder the install's owner. A failure is collected for the report, not thrown. */
	void fix(Path p)
	{
		if(setter == null || p == null)
		{
			return;
		}
		try
		{
			setter.set(p, owner, group);
		}
		catch(IOException | RuntimeException e)
		{
			failures.add(p + " (" + e.getMessage() + ")");
		}
	}

	/** Files.createDirectories, giving each folder it creates the install's owner. */
	void createDirectories(Path dir) throws IOException
	{
		List<Path> missing = new ArrayList<>();
		for(Path p = dir.toAbsolutePath(); p != null && !Files.exists(p); p = p.getParent())
		{
			missing.add(0, p);
		}
		Files.createDirectories(dir);
		missing.forEach(this::fix);
	}

	List<String> failures()
	{
		return failures;
	}
}
