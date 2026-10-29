/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.rest;

import java.util.LinkedHashMap;
import java.util.Map;

import org.restlet.resource.ResourceException;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.wowza.wms.plugin.urloverlay.UrlOverlayModule;
import com.wowza.wms.rest.WMSResponse;

/**
 * Base for read-only stream resources under .../instances/{instance}/streams/{stream}/. The response body is the
 * map a subclass fills in load(), written at the top level of the JSON.
 */
// fields of the WSE REST base class that mean nothing in these responses
@JsonIgnoreProperties({ "version", "serverName", "saveFieldList" })
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.NONE, getterVisibility = JsonAutoDetect.Visibility.NONE, isGetterVisibility = JsonAutoDetect.Visibility.NONE, setterVisibility = JsonAutoDetect.Visibility.NONE, creatorVisibility = JsonAutoDetect.Visibility.NONE)
public abstract class StreamResourceBase extends JsonApplicationConfigBase
{
	protected String instanceName = null;
	protected String streamName = null;
	private final Map<String, Object> body = new LinkedHashMap<>();

	protected StreamResourceBase()
	{
		addFieldForKeyMap("instance", "instanceName");
		addFieldForKeyMap("stream", "streamName");
	}

	@Override
	protected void doInit() throws ResourceException
	{
		super.doInit();
		addToStringKeyMapFromRequest("instance");
		addToStringKeyMapFromRequest("stream");
		setPathMap();
	}

	@JsonAnyGetter
	public Map<String, Object> body()
	{
		return body;
	}

	/** GET: find the module, then let the subclass fill the body. A missing app or module is a 404. */
	@Override
	public boolean loadObject()
	{
		UrlOverlayModule module = (UrlOverlayModule)OverlayConfig.getModule(vhostName, appName, instanceName, streamName, new WMSResponse());
		if(module == null)
		{
			return false;
		}
		body.clear();
		return load(module, body);
	}

	/** @return false for a 404 */
	protected abstract boolean load(UrlOverlayModule module, Map<String, Object> body);
}
