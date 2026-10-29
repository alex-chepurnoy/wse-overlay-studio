/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.rest;

import org.restlet.data.MediaType;
import org.restlet.representation.Representation;
import org.restlet.resource.ResourceException;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import com.wowza.wms.rest.WMSConfig;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonSerialize
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.NONE, getterVisibility = JsonAutoDetect.Visibility.NONE, isGetterVisibility = JsonAutoDetect.Visibility.NONE, setterVisibility = JsonAutoDetect.Visibility.NONE, creatorVisibility = JsonAutoDetect.Visibility.NONE)
@JacksonXmlRootElement(localName = "Objects")
//  /v1/server/plugin/urloverlay/vhosts/_defaultVHost_/applications/live/instances/_definst_/streams/myStream/object", restVerbs = "PUT", xmlFilename = "")

@WMSConfig(restLocation = "/v1/{server}/plugin/urloverlay/vhosts/{vhostName}/applications/{appName}/instances/{instance}/streams/{stream}/overlay/objects", restVerbs = "GET,POST", xmlFilename = "")
public class ObjectsConfig extends JsonApplicationConfigBase
{

	@JsonProperty("applicationInstance")
	protected String instanceName = null;

	@JsonProperty("streamName")
	protected String streamName = null;

	public ObjectsConfig()
	{
		addFieldForKeyMap("instance", "instanceName");
		addFieldForKeyMap("stream", "streamName");
	}

	public ObjectsConfig(String vhost, String appName, String instanceName, String streamName)
	{
		super(vhost, appName);
		this.instanceName = instanceName;
		this.streamName = streamName;
		addFieldForKeyMap("instance", "instanceName");
		addToStringKeyMap("instance", instanceName);
		addFieldForKeyMap("stream", "streamName");
		addToStringKeyMap("stream", streamName);
	}

	@Override
	protected void doInit() throws ResourceException
	{
		super.doInit();
		addToStringKeyMapFromRequest("instance");
		addToStringKeyMapFromRequest("stream");
		setPathMap();
	}

	@Override
	public Representation post(Representation entity)
	{
		ObjectConfig obj = new ObjectConfig();
		Representation retVal = obj.post(entity, getRequest());
		this.setStatus(obj.getStatus());
		if (obj.currentException != null)
		{
			this.getResponse().setEntity(obj.currentException.getXML(), MediaType.APPLICATION_XML);
		}
		return retVal;
	}
}
