/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.rest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import com.wowza.wms.plugin.urloverlay.UrlOverlayModule;
import com.wowza.wms.plugin.urloverlay.config.TextFile;
import com.wowza.wms.plugin.urloverlay.templates.TemplateFiles;
import com.wowza.wms.rest.WMSConfig;

/**
 * POST {"how": "copy", "name": "transrate-overlay", "overlay": "...", "encodes": [...]} copies a template with
 * dynamic overlays on and the overlay set; {"how": "inplace", ...} changes a customer's own template itself, after a
 * backup, and records the edit in the install manifest so uninstall puts the original back.
 */
@WMSConfig(restLocation = "/v1/{server}/plugin/urloverlay/vhosts/{vhostName}/templates/{templateName}/use", restVerbs = "POST", xmlFilename = "")
public class TemplateUseConfig extends VHostResourceBase
{
	private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

	@Override
	@SuppressWarnings("unchecked")
	protected Object doPost(Map<String, String> path, Map<String, Object> body) throws Exception
	{
		Path dir = vhostDir(path.get("vhostName")).resolve("transcoder").resolve("templates");
		String t = TemplateFiles.safeName(path.get("templateName"));
		String overlay = str(body, "overlay");
		if(overlay == null || overlay.isBlank())
		{
			throw new TemplateFiles.Refused(400, "Pick the overlay the template carries.");
		}
		List<String> encodes = new ArrayList<>();
		if(body.get("encodes") instanceof List)
		{
			for(Object o: (List<Object>)body.get("encodes"))
			{
				encodes.add(String.valueOf(o));
			}
		}
		Map<String, Object> m;
		if("inplace".equals(str(body, "how")))
		{
			Path file = dir.resolve(t + ".xml");
			byte[] before = TemplateFiles.enableInPlace(dir, t, overlay, encodes);
			Path backup = AppModuleConfig.backupOf(file);
			Files.write(backup, before, StandardOpenOption.CREATE_NEW);
			boolean recorded = record(file, backup, before);
			UrlOverlayLog.change(this, "switched on overlays in template " + t + " in place, backup " + backup.getFileName());
			m = TemplatesBuildConfig.answer(file);
			m.put("backup", backup.toString());
			m.put("recorded", recorded);
			m.put("live", UrlOverlayModule.reloadTemplateFile(path.get("vhostName"), file));
		}
		else
		{
			Path written = TemplateFiles.copyWithOverlay(dir, t, str(body, "name"), overlay, encodes);
			UrlOverlayLog.change(this, "copied template " + t + " as " + written.getFileName() + " with overlay " + overlay);
			m = TemplatesBuildConfig.answer(written);
		}
		return m;
	}

	/**
	 * Adds an in-place template edit to the install manifest in the installer's shape, with no surgical changes:
	 * uninstall restores the backup while the file is as the module last wrote it, and otherwise keeps it.
	 */
	@SuppressWarnings("unchecked")
	static boolean record(Path file, Path backup, byte[] before) throws Exception
	{
		Path home = Paths.get(System.getProperty("com.wowza.wms.AppHome", "."));
		Path man = manifest(home);
		if(man == null)
		{
			return false;
		}
		Map<String, Object> m = JSON.readValue(man.toFile(), Map.class);
		List<Object> edited = (List<Object>)m.computeIfAbsent("edited", k -> new ArrayList<>());
		String rel = home.relativize(file).toString().replace('\\', '/');
		Map<String, Object> e = (Map<String, Object>)edited.stream().filter(o -> o instanceof Map && rel.equals(((Map<String, Object>)o).get("path")))
				.findFirst().orElse(null);
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
			// something else changed the file since URL Overlay last wrote it, so its backup is no longer the file
			// without URL Overlay: the installer's uninstall then keeps the file instead of restoring that backup
			e.put("editedByOthers", true);
		}
		e.put("installed", TextFile.sha256(Files.readAllBytes(file)));
		m.put("updated", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
		AppModuleConfig.writeManifest(man, m);
		return true;
	}

	/** After the module writes a file it recorded as edited, the manifest's hash follows, so uninstall can still restore it. */
	@SuppressWarnings("unchecked")
	static void touch(Path file)
	{
		try
		{
			Path home = Paths.get(System.getProperty("com.wowza.wms.AppHome", "."));
			Path man = manifest(home);
			if(man == null)
			{
				return;
			}
			Map<String, Object> m = JSON.readValue(man.toFile(), Map.class);
			String rel = home.relativize(file).toString().replace('\\', '/');
			for(Object o: (List<Object>)m.getOrDefault("edited", List.of()))
			{
				if(o instanceof Map && rel.equals(((Map<String, Object>)o).get("path")))
				{
					((Map<String, Object>)o).put("installed", TextFile.sha256(Files.readAllBytes(file)));
					AppModuleConfig.writeManifest(man, m);
					return;
				}
			}
		}
		catch(Exception e)
		{
			UrlOverlayLog.info("UrlOverlay: could not update the install manifest for " + file + ": " + e);
		}
	}

	private static Path manifest(Path home)
	{
		Path p = home.resolve(AppModuleConfig.MANIFEST);
		return Files.isRegularFile(p) ? p : null;
	}
}
