/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.restlet.Request;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.ext.jackson.JacksonRepresentation;
import org.restlet.representation.Representation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wowza.wms.plugin.overlaystudio.OverlayStudioLog;
import com.wowza.wms.plugin.overlaystudio.templates.ModuleFiles;
import com.wowza.wms.plugin.overlaystudio.templates.TemplateFiles;
import com.wowza.wms.rest.ConfigBase;
import com.wowza.wms.rest.WMSConfig;
import com.wowza.wms.rest.restserver.RestletApplication;
import com.wowza.wms.rest.vhosts.VHostsConfig;

/**
 * Base for the vhost-level template and application resources. These are actions on files rather than stored
 * objects, so GET and POST are handled here directly instead of through ConfigBase's load and save cycle: the
 * response is a JSON map and every refusal carries its status and message.
 */
public abstract class VHostResourceBase extends ConfigBase
{
	private static final ObjectMapper JSON = new ObjectMapper();

	/** GET; path holds the restLocation variables, query the query string. */
	protected Object doGet(Map<String, String> path, Map<String, String> query) throws TemplateFiles.Refused, Exception
	{
		throw new TemplateFiles.Refused(405, "GET is not supported here");
	}

	/** POST with a JSON object body. */
	protected Object doPost(Map<String, String> path, Map<String, Object> body) throws TemplateFiles.Refused, Exception
	{
		throw new TemplateFiles.Refused(405, "POST is not supported here");
	}

	/** DELETE. */
	protected Object doDelete(Map<String, String> path) throws TemplateFiles.Refused, Exception
	{
		throw new TemplateFiles.Refused(405, "DELETE is not supported here");
	}

	/**
	 * Every path variable is one path segment once WSE has URL-decoded it, so none can climb out of the folder it names;
	 * BadName otherwise. Resources check their own names further where they become file names.
	 */
	static void checkPath(Map<String, String> path)
	{
		for(Map.Entry<String, String> e: path.entrySet())
		{
			if(!e.getKey().equals("server"))
			{
				ModuleFiles.segment(e.getKey(), e.getValue());
			}
		}
	}

	/** The virtual host's folder; BadName when WSE has no virtual host by that name. */
	protected static Path vhostDir(String vhostName)
	{
		String dir = vhostName == null ? null : VHostsConfig.getVHostDir(vhostName);
		if(dir == null)
		{
			throw new ModuleFiles.BadName("No virtual host '" + vhostName + "'");
		}
		return Paths.get(dir);
	}

	@Override
	public Representation get(Request request)
	{
		return handle(request, null, false);
	}

	@Override
	public Representation post(Representation rep, Request request)
	{
		return handle(request, rep, "POST");
	}

	@Override
	public Representation delete(Request request)
	{
		return handle(request, null, "DELETE");
	}

	private Representation handle(Request request, Representation rep, boolean post)
	{
		return handle(request, rep, post ? "POST" : "GET");
	}

	private Representation handle(Request request, Representation rep, String verb)
	{
		boolean post = verb.equals("POST");
		// ConfigBase.get and post set this before doing anything; the change log reads it
		this.restletUser = request.getClientInfo().getUser();
		WMSConfig info = getClass().getAnnotation(WMSConfig.class);
		if(info == null || !info.restVerbs().contains(verb))
		{
			return reply(Status.CLIENT_ERROR_METHOD_NOT_ALLOWED, error(405, verb + " is not supported here"));
		}
		// every change needs a WSE administrator (WriteAccess)
		if(WriteAccess.isWrite(verb))
		{
			String denied = WriteAccess.refusal(getApplication(), restletUser);
			if(denied != null)
			{
				OverlayStudioLog.refused(this, verb + " " + request.getResourceRef().getPath(), 403, denied);
				return reply(Status.CLIENT_ERROR_FORBIDDEN, error(403, denied));
			}
		}
		// the same check ConfigBase.get makes before answering
		if(!((RestletApplication)getApplication()).getRESTConfig().isAuthenticationMethodEnabled())
		{
			return reply(Status.CLIENT_ERROR_UNAUTHORIZED, error(401, "The authentication method currently set is not supported."));
		}
		Map<String, String> path = fillStringKeyMapFromUri(info.restLocation(), request.getResourceRef().toString());
		try
		{
			checkPath(path);
			Object result;
			if(post)
			{
				String text = rep == null || rep.isEmpty() ? null : rep.getText();
				if(text == null || text.isBlank())
				{
					return reply(Status.CLIENT_ERROR_BAD_REQUEST, error(400, "Request body is empty; a JSON body is required for POST"));
				}
				@SuppressWarnings("unchecked")
				Map<String, Object> body = JSON.readValue(text, Map.class);
				result = doPost(path, body);
			}
			else if(verb.equals("DELETE"))
			{
				result = doDelete(path);
			}
			else
			{
				Map<String, String> query = new HashMap<>(request.getResourceRef().getQueryAsForm().getValuesMap());
				result = doGet(path, query);
			}
			return reply(post ? Status.SUCCESS_CREATED : Status.SUCCESS_OK, result);
		}
		catch(TemplateFiles.Refused e)
		{
			OverlayStudioLog.refused(this, verb + " " + request.getResourceRef().getPath(), e.status(), e.getMessage());
			Map<String, Object> err = error(e.status(), e.getMessage());
			if(e.id() != null)
			{
				err.put("id", e.id());
			}
			if(e.field() != null)
			{
				err.put("field", e.field());
			}
			return reply(Status.valueOf(e.status()), err);
		}
		catch(ModuleFiles.BadName e)
		{
			OverlayStudioLog.refused(this, verb + " " + request.getResourceRef().getPath(), 400, e.getMessage());
			return reply(Status.CLIENT_ERROR_BAD_REQUEST, error(400, e.getMessage()));
		}
		catch(com.fasterxml.jackson.core.JsonProcessingException e)
		{
			return reply(Status.CLIENT_ERROR_BAD_REQUEST, error(400, "Body is not a JSON object: " + e.getOriginalMessage()));
		}
		catch(Exception e)
		{
			logException(getClass().getSimpleName() + "." + verb.toLowerCase(), e);
			// the message only: class names and stack details stay in the WSE log
			String msg = e.getMessage() == null || e.getMessage().isBlank() ? "Internal error; see the WSE log." : e.getMessage();
			return reply(Status.SERVER_ERROR_INTERNAL, error(500, msg));
		}
	}

	private Representation reply(Status status, Object body)
	{
		setStatus(status);
		return new JacksonRepresentation<>(MediaType.APPLICATION_JSON, body);
	}

	private static Map<String, Object> error(int code, String message)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("success", false);
		m.put("code", String.valueOf(code));
		m.put("message", message);
		return m;
	}

	protected static String str(Map<String, ?> m, String key)
	{
		Object v = m.get(key);
		return v == null ? null : v.toString();
	}
}
