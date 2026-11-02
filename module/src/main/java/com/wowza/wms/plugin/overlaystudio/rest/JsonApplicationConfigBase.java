/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import java.util.ArrayList;
import java.util.List;

import org.restlet.Request;
import org.restlet.data.MediaType;
import org.restlet.data.Preference;
import org.restlet.data.Status;
import org.restlet.ext.jackson.JacksonRepresentation;
import org.restlet.representation.Representation;

import com.wowza.wms.rest.vhosts.applications.ApplicationConfigBase;

/** WSE's application REST base, answering in JSON only and refusing a POST or PUT with no body as 400, not 500. */
public class JsonApplicationConfigBase extends ApplicationConfigBase
{
	public JsonApplicationConfigBase()
	{
	}

	public JsonApplicationConfigBase(String vhostName, String appName)
	{
		super(vhostName, appName);
	}

	private void fixClient(Request request)
	{
		request.getClientInfo().getAcceptedMediaTypes();
		List<Preference<MediaType>> mtypes = new ArrayList<>();
		mtypes.add(new Preference<>(MediaType.APPLICATION_JSON));
		request.getClientInfo().setAcceptedMediaTypes(mtypes);
	}

	// ConfigBase would hand an empty body to Jackson and answer 500; curl --digest sends one first, expecting a 401
	private Representation emptyBodyResponse(Request request)
	{
		String msg = "Request body is empty; a JSON body is required for " + request.getMethod();
		logWarn(getClass().getName() + "." + request.getMethod().getName().toLowerCase() + ": " + msg + " [" + request.getResourceRef() + "]");
		setStatus(Status.CLIENT_ERROR_BAD_REQUEST, msg);
		WMSResponse2 resp = new WMSResponse2();
		resp.setFail();
		resp.setCode(Status.CLIENT_ERROR_BAD_REQUEST);
		resp.message = msg;
		return new JacksonRepresentation<>(MediaType.APPLICATION_JSON, resp);
	}

	private static boolean isEmptyBody(Representation rep)
	{
		return rep == null || rep.isEmpty();
	}

	// every POST, PUT and DELETE needs an administrator (WriteAccess); null when this request may change things
	private Representation refuseWrite(Request request)
	{
		String denied = WriteAccess.refusal(getApplication(), request.getClientInfo().getUser());
		if(denied == null)
		{
			return null;
		}
		logWarn(getClass().getSimpleName() + "." + request.getMethod().getName().toLowerCase() + " refused: " + denied + " [" + request.getResourceRef() + "]");
		setStatus(Status.CLIENT_ERROR_FORBIDDEN, denied);
		WMSResponse2 resp = new WMSResponse2();
		resp.setFail();
		resp.setCode(Status.CLIENT_ERROR_FORBIDDEN);
		resp.message = denied;
		return new JacksonRepresentation<>(MediaType.APPLICATION_JSON, resp);
	}

	@Override
	public Representation post(Representation rep, Request request)
	{
		fixClient(request);
		Representation denied = refuseWrite(request);
		if(denied != null)
			return denied;
		if (isEmptyBody(rep))
			return emptyBodyResponse(request);
		rep.setMediaType(MediaType.APPLICATION_JSON);
		return super.post(rep, request);
	}

	@Override
	public Representation put(Representation rep, Request request)
	{
		fixClient(request);
		Representation denied = refuseWrite(request);
		if(denied != null)
			return denied;
		if (isEmptyBody(rep))
			return emptyBodyResponse(request);
		rep.setMediaType(MediaType.APPLICATION_JSON);
		return super.put(rep,request);
	}

	@Override
	public Representation get(Request request)
	{
		fixClient(request);
		return super.get(request);
	}


	@Override
	public Representation delete(Request request)
	{
		fixClient(request);
		Representation denied = refuseWrite(request);
		if(denied != null)
			return denied;
		return super.delete(request);
	}
}