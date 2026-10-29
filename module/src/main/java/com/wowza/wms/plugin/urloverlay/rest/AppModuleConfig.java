/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.rest;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.wowza.wms.plugin.urloverlay.UrlOverlayLog;
import com.wowza.wms.plugin.urloverlay.config.ConfigEdits;
import com.wowza.wms.plugin.urloverlay.config.TextFile;
import com.wowza.wms.plugin.urloverlay.config.XmlScan;
import com.wowza.wms.plugin.urloverlay.templates.TemplateFiles;
import com.wowza.wms.rest.WMSConfig;
import com.wowza.wms.vhost.IVHost;
import com.wowza.wms.vhost.VHostSingleton;

/**
 * Whether an application loads this module, and adding it from the plugin page. Adding is
 * the installer's own edit: the module entry is inserted into Application.xml with every other byte kept, the file is
 * backed up first as &lt;file&gt;.urloverlay-&lt;time&gt;.bak, and the edit is recorded in the install manifest, so
 * uninstall takes it out again exactly as it takes out the installer's. WSE loads modules when an application starts,
 * so { "restart": true } restarts the application: it is shut down and started again once it has stopped, which drops
 * the streams publishing to it; they reconnect.
 */
@WMSConfig(restLocation = "/v1/{server}/plugin/urloverlay/vhosts/{vhostName}/applications/{appName}/module", restVerbs = "GET,POST", xmlFilename = "")
public class AppModuleConfig extends VHostResourceBase
{
	static final String MANIFEST = "conf.modules/urloverlay/install-manifest.json";
	private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

	@Override
	protected Object doGet(Map<String, String> path, Map<String, String> query) throws Exception
	{
		String vhost = path.get("vhostName"), app = path.get("appName");
		Path xml = applicationXml(vhost, app);
		String text = TextFile.read(xml).text();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("application", app);
		m.put("installed", ConfigEdits.hasModule(text));
		m.put("streamType", streamType(text));
		m.put("live", isLive(text));
		m.put("publishing", AppStreamsConfig.publishing(vhost, app));
		return m;
	}

	@Override
	protected Object doPost(Map<String, String> path, Map<String, Object> body) throws Exception
	{
		String vhost = path.get("vhostName"), app = path.get("appName");
		boolean restart = Boolean.TRUE.equals(body.get("restart"));
		Path xml = applicationXml(vhost, app);
		TextFile f = TextFile.read(xml);
		if(!isLive(f.text()))
		{
			throw new TemplateFiles.Refused(409, "Application " + app + " is not a live application (StreamType " + streamType(f.text()) + "); the overlay needs a live transcoder");
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("application", app);
		boolean added = false;
		if(!ConfigEdits.hasModule(f.text()))
		{
			ConfigEdits.Result r = ConfigEdits.application(f);
			byte[] before = Files.readAllBytes(xml);
			Path backup = backupOf(xml);
			Files.write(backup, before, StandardOpenOption.CREATE_NEW);
			Files.write(xml, r.file().bytes());
			m.put("backup", backup.toString());
			m.put("recorded", record(vhost, app, xml, backup, before, r));
			added = true;
			UrlOverlayLog.change(this, "added the module to application " + vhost + "/" + app + ", backup " + backup.getFileName());
		}
		m.put("added", added);
		m.put("installed", true);
		List<String> publishing = AppStreamsConfig.publishing(vhost, app);
		m.put("publishing", publishing);
		m.put("restarted", restart && restart(vhost, app));
		if(restart)
		{
			UrlOverlayLog.change(this, "restarting application " + vhost + "/" + app + " so it loads the module; dropped " + publishing);
		}
		return m;
	}

	static Path applicationXml(String vhost, String app) throws TemplateFiles.Refused
	{
		Path xml = vhostDir(vhost).resolve("conf").resolve(TemplateFiles.safeName(app)).resolve("Application.xml");
		if(!Files.isRegularFile(xml))
		{
			throw new TemplateFiles.Refused(404, "No application '" + app + "'");
		}
		return xml;
	}

	static String streamType(String applicationXml)
	{
		XmlScan.Span s = XmlScan.first(applicationXml, "/Root/Application/Streams/StreamType");
		return s == null ? "" : applicationXml.substring(s.contentStart(), s.close()).trim();
	}

	/** The installer's rule: live, live-lowlatency, live-record and the other live stream types; VOD ("default") is not. */
	static boolean isLive(String applicationXml)
	{
		return streamType(applicationXml).startsWith("live");
	}

	static Path backupOf(Path p)
	{
		String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
		Path b = p.resolveSibling(p.getFileName() + ".urloverlay-" + stamp + ".bak");
		int n = 2;
		while(Files.exists(b))
		{
			b = p.resolveSibling(p.getFileName() + ".urloverlay-" + stamp + "-" + n++ + ".bak");
		}
		return b;
	}

	/**
	 * Adds the edit to the install manifest in the shape the installer writes, so its uninstall restores the original
	 * bytes. False when no manifest exists (a hand install): the edit is then not undone by an uninstall.
	 */
	@SuppressWarnings("unchecked")
	static boolean record(String vhost, String app, Path xml, Path backup, byte[] before, ConfigEdits.Result r) throws Exception
	{
		Path home = Paths.get(System.getProperty("com.wowza.wms.AppHome", "."));
		Path man = home.resolve(MANIFEST);
		if(!Files.isRegularFile(man))
		{
			return false;
		}
		Map<String, Object> m = JSON.readValue(man.toFile(), Map.class);
		List<Object> edited = (List<Object>)m.computeIfAbsent("edited", k -> new ArrayList<>());
		String rel = home.relativize(xml).toString().replace('\\', '/');
		Map<String, Object> e = null;
		for(Object o: edited)
		{
			if(o instanceof Map && rel.equals(((Map<String, Object>)o).get("path")))
			{
				e = (Map<String, Object>)o;
			}
		}
		if(e == null)
		{
			e = new LinkedHashMap<>();
			e.put("path", rel);
			e.put("original", TextFile.sha256(before));
			e.put("backup", home.relativize(backup).toString().replace('\\', '/'));
			e.put("changes", new ArrayList<>());
			edited.add(e);
		}
		else if(e.get("installed") != null && !TextFile.sha256(before).equals(e.get("installed")))
		{
			// something else changed the file since URL Overlay last wrote it, so its backup no longer stands for
			// "everything but URL Overlay": the installer's uninstall then takes out only the recorded lines
			e.put("editedByOthers", true);
		}
		List<Object> changes = (List<Object>)e.computeIfAbsent("changes", k -> new ArrayList<>());
		for(ConfigEdits.Change c: r.changes())
		{
			if(!changes.contains(c.toMap()))
			{
				changes.add(c.toMap());
			}
		}
		e.put("installed", TextFile.sha256(r.file().bytes()));
		List<Object> apps = (List<Object>)m.computeIfAbsent("apps", k -> new ArrayList<>());
		if(!apps.contains(vhost + "/" + app))
		{
			apps.add(vhost + "/" + app);
		}
		m.put("updated", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
		writeManifest(man, m);
		return true;
	}

	interface Step
	{
		void run(Path tmp) throws IOException;
	}

	/** Runs between writing the new manifest and moving it into place; a test makes it fail there. */
	static volatile Step beforeMove = tmp -> {
	};

	/** Writes the install manifest through a temporary file moved over it, as the installer does, so it is never half written. */
	static void writeManifest(Path man, Map<String, Object> m) throws IOException
	{
		Path tmp = man.resolveSibling(man.getFileName() + ".tmp");
		Files.write(tmp, JSON.writeValueAsBytes(m));
		beforeMove.run(tmp);
		try
		{
			Files.move(tmp, man, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		catch(AtomicMoveNotSupportedException e)
		{
			Files.move(tmp, man, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Shuts the application down and starts it again once it has stopped, in the background; false when it cannot. */
	private static boolean restart(String vhostName, String app)
	{
		IVHost vhost = VHostSingleton.getInstance(vhostName);
		if(vhost == null)
		{
			return false;
		}
		Thread t = new Thread(() -> {
			try
			{
				if(vhost.isApplicationLoaded(app))
				{
					vhost.shutdownApplication(app);
				}
				for(int k = 0; k < 100 && vhost.isApplicationLoaded(app); k++)
				{
					Thread.sleep(100);
				}
				vhost.startApplicationInstance(app);
			}
			catch(Exception e)
			{
				UrlOverlayLog.info("UrlOverlay: restarting application " + app + " failed: " + e);
			}
		}, "UrlOverlay-restart-" + app);
		t.setDaemon(true);
		t.start();
		return true;
	}
}
