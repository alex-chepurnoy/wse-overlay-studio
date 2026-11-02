/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wowza.wms.plugin.overlaystudio.LiveValues;
import com.wowza.wms.plugin.overlaystudio.OverlayStudioModule;
import com.wowza.wms.plugin.overlaystudio.templates.OverlayStore;
import com.wowza.wms.plugin.overlaystudio.templates.TemplateFiles;
import com.wowza.wms.rest.WMSConfig;

/**
 * Sets a live value on one layer of a named overlay (plan 3.6), for score bugs and tickers fed by an outside system:
 * POST {"text": "HOME 2 - 1 AWAY"}, also color, background, opacity and hidden, merged into the layer's value. Every
 * running stream showing the overlay redraws with it; the overlay file is not touched. 404 for a layer the saved
 * overlay does not have, 429 above LiveValues.RATE writes a second.
 */
@WMSConfig(restLocation = "/v1/{server}/plugin/overlaystudio/vhosts/{vhostName}/applications/{appName}/overlays/{overlayName}/live/{layerId}", restVerbs = "POST", xmlFilename = "")
public class NamedOverlayLiveLayerConfig extends VHostResourceBase
{
	@Override
	@SuppressWarnings("unchecked")
	protected Object doPost(Map<String, String> path, Map<String, Object> body) throws Exception
	{
		String name = TemplateFiles.safeName(path.get("overlayName"));
		String id = path.get("layerId");
		Map<String, Object> layer = null;
		for(Object o: (List<Object>)OverlayStore.read(NamedOverlayConfig.dir(path), name).getOrDefault("objects", List.of()))
		{
			if(o instanceof Map && id.equals(((Map<String, Object>)o).get("id")))
			{
				layer = (Map<String, Object>)o;
			}
		}
		if(layer == null)
		{
			throw new TemplateFiles.Refused(404, "Overlay " + name + " has no layer '" + id + "'", id, null);
		}
		String[] bad = ObjectConfig.liveProblem(layer, body);
		if(bad != null)
		{
			throw new TemplateFiles.Refused(400, bad[1], id, bad[0]);
		}
		LiveValues.Key key = NamedOverlayConfig.liveKey(path, name);
		Map<String, Object> value = LiveValues.set(key, NamedOverlayConfig.dir(path), id, body);
		List<String> updated = OverlayStudioModule.applyLive(key);
		// not in the change log: a feed writes many times a second
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("success", true);
		m.put("id", id);
		m.put("value", value);
		m.put("updatedStreams", updated);
		return m;
	}
}
