/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wowza.wms.plugin.overlaystudio.LiveValues;
import com.wowza.wms.plugin.overlaystudio.OverlayStudioLog;
import com.wowza.wms.plugin.overlaystudio.OverlayStudioModule;
import com.wowza.wms.plugin.overlaystudio.templates.OverlayStore;
import com.wowza.wms.plugin.overlaystudio.templates.TemplateFiles;
import com.wowza.wms.rest.WMSConfig;

/**
 * A named overlay's live values (plan 3.6). GET returns them, {id: {text, color, background, opacity, hidden}}, to
 * read-only users too; POST {"clear": "&lt;id&gt;"} clears one; DELETE clears them all. Setting one is
 * NamedOverlayLiveLayerConfig.
 */
@WMSConfig(restLocation = "/v1/{server}/plugin/overlaystudio/vhosts/{vhostName}/applications/{appName}/overlays/{overlayName}/live", restVerbs = "GET,POST,DELETE", xmlFilename = "")
public class NamedOverlayLiveConfig extends VHostResourceBase
{
	/** The overlay's name, refused with 404 when it does not exist. */
	static String existing(Map<String, String> path) throws Exception
	{
		String name = TemplateFiles.safeName(path.get("overlayName"));
		OverlayStore.read(NamedOverlayConfig.dir(path), name);
		return name;
	}

	@Override
	protected Object doGet(Map<String, String> path, Map<String, String> query) throws Exception
	{
		String name = existing(path);
		return LiveValues.get(NamedOverlayConfig.liveKey(path, name), NamedOverlayConfig.dir(path));
	}

	@Override
	protected Object doPost(Map<String, String> path, Map<String, Object> body) throws Exception
	{
		String name = existing(path);
		if(!(body.get("clear") instanceof String id) || body.size() != 1)
		{
			throw new TemplateFiles.Refused(400, "POST here takes {\"clear\": \"<layer id>\"}; set a value with POST .../live/<layer id>", null, "clear");
		}
		Path dir = NamedOverlayConfig.dir(path);
		LiveValues.Key key = NamedOverlayConfig.liveKey(path, name);
		boolean had = LiveValues.clear(key, dir, id);
		List<String> updated = had ? OverlayStudioModule.applyLive(key) : List.of();
		if(had)
		{
			OverlayStudioLog.change(this, "cleared the live value of " + id + " on overlay " + name + " in " + path.get("vhostName") + "/" + path.get("appName"));
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("success", true);
		m.put("cleared", had ? id : null);
		m.put("updatedStreams", updated);
		return m;
	}

	@Override
	protected Object doDelete(Map<String, String> path) throws Exception
	{
		String name = existing(path);
		LiveValues.Key key = NamedOverlayConfig.liveKey(path, name);
		LiveValues.clearAll(key, NamedOverlayConfig.dir(path));
		List<String> updated = OverlayStudioModule.applyLive(key);
		OverlayStudioLog.change(this, "cleared every live value on overlay " + name + " in " + path.get("vhostName") + "/" + path.get("appName"));
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("success", true);
		m.put("updatedStreams", updated);
		return m;
	}
}
