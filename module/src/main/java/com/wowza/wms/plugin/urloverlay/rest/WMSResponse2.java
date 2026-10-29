/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.rest;
import com.fasterxml.jackson.annotation.*;
import com.wowza.wms.rest.*;
import org.restlet.data.Status;

public class WMSResponse2 extends  WMSResponse
{
	//null out/remove these
	public boolean success;
	public String data;

	@JsonProperty("code")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public String code;

	@JsonProperty("message")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public String message;

	//all serialize of this object
	@JsonUnwrapped()
	public ConfigBase theObject;

	public void setSuccess()
	{
		super.success = true;
	}

	public void setFail()
	{
		super.success = false;
	}

	public void setCode(Status status)
	{
		this.code = String.valueOf(status.getCode());
	}

}
