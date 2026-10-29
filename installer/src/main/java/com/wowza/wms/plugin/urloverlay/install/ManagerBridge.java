/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The REST bridge the installer places in WSE Manager (bridge/, docs/DESIGN.md, The Manager bridge): where its classes
 * go, whether this Manager is one it was built for, and how the Manager is restarted to load it.
 * <p>
 * The bridge is a Spring 6 controller compiled against Jakarta Servlet 6, and it loads only because the Manager mounts
 * manager/temp as its WEB-INF/classes. So it is installed only when all of these hold: WMSManager.war carries
 * spring-webmvc 6.x; the Manager's Tomcat is 10.1 (manager/lib/wms-tomcat-10.1.*.jar, or
 * lib-tomcat/tomcat-embed-core-10.1.*.jar; Tomcat is not inside the war); and the start script passes
 * --tempDirectory=&lt;manager&gt;/temp. Checked against a 4.12.0 install: spring-webmvc-6.2.19, wms-tomcat-10.1.59.jar and
 * startmgr.bat's --tempDirectory="%WMSMGR_HOME%\temp"; startmgr.sh carries --tempDirectory=$WMSMGR_HOME/temp.
 */
final class ManagerBridge
{
	/** The bridge's package, under manager/temp. */
	static final String PACKAGE = "com/wowza/wms/webapp/urloverlay";
	static final String PAYLOAD = "bridge/";
	static final String LINUX_SERVICE = "WowzaStreamingEngineManager";
	private static final Pattern WEBMVC = Pattern.compile("WEB-INF/lib/spring-webmvc-6\\.\\d+\\.\\d+(\\.\\w+)?\\.jar");
	private static final Pattern TOMCAT = Pattern.compile("(wms-tomcat|tomcat-embed-core)-10\\.1\\.\\d+\\.jar");
	private static final Pattern TEMP = Pattern.compile("--tempDirectory=\"?(\\$WMSMGR_HOME|\\$\\{WMSMGR_HOME}|%WMSMGR_HOME%)[/\\\\]temp\"?(\\s|$)");

	private ManagerBridge()
	{
	}

	/** Whether the bridge can go into this Manager, and if not, why. */
	record Gate(boolean ok, String reason)
	{
	}

	static Path temp(Path managerHome)
	{
		return managerHome.resolve("temp");
	}

	static Path classDir(Path managerHome)
	{
		return temp(managerHome).resolve(PACKAGE);
	}

	static Gate check(Path managerHome)
	{
		Path war = managerHome.resolve("lib").resolve("WMSManager.war");
		if(!Files.isRegularFile(war))
		{
			return new Gate(false, "no " + war);
		}
		boolean spring = false;
		try(ZipInputStream z = new ZipInputStream(Files.newInputStream(war)))
		{
			for(ZipEntry e; (e = z.getNextEntry()) != null && !spring;)
			{
				spring = WEBMVC.matcher(e.getName()).matches();
			}
		}
		catch(IOException e)
		{
			return new Gate(false, war + " could not be read (" + e.getMessage() + ")");
		}
		if(!spring)
		{
			return new Gate(false, "its WMSManager.war does not carry Spring 6 (WEB-INF/lib/spring-webmvc-6.*.jar)");
		}
		if(!hasTomcat101(managerHome.resolve("lib")) && !hasTomcat101(managerHome.resolve("lib").resolve("lib-tomcat")))
		{
			return new Gate(false, "its Tomcat is not 10.1 (no wms-tomcat-10.1.*.jar or lib-tomcat/tomcat-embed-core-10.1.*.jar in manager/lib)");
		}
		boolean temp = false;
		for(String script: List.of("startmgr.sh", "startmgr.bat"))
		{
			Path s = managerHome.resolve("bin").resolve(script);
			try
			{
				temp |= Files.isRegularFile(s) && TEMP.matcher(Files.readString(s, StandardCharsets.ISO_8859_1)).find();
			}
			catch(IOException e)
			{
				// read as not found
			}
		}
		if(!temp)
		{
			return new Gate(false, "its start script (manager/bin/startmgr.sh or startmgr.bat) does not pass --tempDirectory=<manager>/temp");
		}
		return new Gate(true, null);
	}

	private static boolean hasTomcat101(Path dir)
	{
		try(var s = Files.list(dir))
		{
			return s.anyMatch(p -> TOMCAT.matcher(p.getFileName().toString()).matches());
		}
		catch(IOException e)
		{
			return false;
		}
	}

	/**
	 * The Windows service name of this Manager: its version line with spaces, dots and dashes removed, as
	 * InstallWowzaStreamingEngineManager-Service.bat builds it from manager/bin/wowzamgrversion.bat (for example
	 * WowzaStreamingEngineManager4120ENG4658+7). Null when the file is not there.
	 */
	static String windowsService(Path managerHome)
	{
		Path v = managerHome.resolve("bin").resolve("wowzamgrversion.bat");
		try
		{
			Matcher m = Pattern.compile("(?im)^\\s*set\\s+WowzaMgrVersion=(.+?)\\s*$").matcher(Files.readString(v, StandardCharsets.ISO_8859_1));
			return m.find() ? m.group(1).replace(" ", "").replace(".", "").replace("-", "") : null;
		}
		catch(IOException e)
		{
			return null;
		}
	}

	/** What a person types to restart the Manager. */
	static String restartCommand(Path managerHome)
	{
		if(!Preflight.WINDOWS)
		{
			return "sudo systemctl restart " + LINUX_SERVICE;
		}
		String svc = windowsService(managerHome);
		return svc == null ? "restart the Wowza Streaming Engine Manager service in Windows Services"
				: "net stop \"" + svc + "\" && net start \"" + svc + "\"  (in a Command Prompt run as administrator)";
	}

	/** Tests only: stands in for running the restart. */
	static volatile java.util.function.Function<List<String>, Integer> testRunner;

	/** Restarts the Manager now; null when it worked, else what went wrong, in plain words. */
	static String restart(Path managerHome)
	{
		List<String> cmd = new ArrayList<>();
		if(Preflight.WINDOWS)
		{
			String svc = windowsService(managerHome);
			if(svc == null)
			{
				return "the Manager's service name could not be found";
			}
			cmd.addAll(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "Restart-Service -Name '" + svc.replace("'", "''") + "'"));
		}
		else
		{
			cmd.addAll(List.of("systemctl", "restart", LINUX_SERVICE));
		}
		try
		{
			var t = testRunner;
			int code = t != null ? t.apply(cmd) : new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor();
			return code == 0 ? null : "the restart command ended with code " + code;
		}
		catch(IOException e)
		{
			return e.getMessage();
		}
		catch(InterruptedException e)
		{
			Thread.currentThread().interrupt();
			return "interrupted";
		}
	}
}
