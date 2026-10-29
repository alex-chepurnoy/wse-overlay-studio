/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Method;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.ServerResource;
import org.restlet.security.User;

import com.wowza.wms.rest.ConfigBase;
import com.wowza.wms.rest.WMSConfig;

/**
 * Changes need a WSE administrator. Every write verb of every module resource is refused when WSE REST does not
 * authenticate (no application here stands for that), and the rule itself is checked for each kind of user.
 */
class WriteAccessTest
{
	static final Set<String> ADMINS = Set.of("alice");

	@Test
	void onlyAnAuthenticatedAdministratorMayChangeThings()
	{
		assertNull(WriteAccess.refusal(true, "alice", ADMINS::contains));
		String notAdmin = WriteAccess.refusal(true, "bob", ADMINS::contains);
		assertNotNull(notAdmin);
		assertTrue(notAdmin.contains("bob is not in the admin group"), notAdmin);
		assertNotNull(WriteAccess.refusal(true, null, ADMINS::contains), "no user");
		assertNotNull(WriteAccess.refusal(true, "", ADMINS::contains), "no user");
		String off = WriteAccess.refusal(false, "alice", ADMINS::contains);
		assertNotNull(off, "without REST authentication nobody is known to be an administrator");
		assertTrue(off.contains("AuthenticationMethod"), off);
		assertTrue(off.startsWith(WriteAccess.READ_ONLY), off);
		// every verb but GET is a write
		{
			assertFalse(WriteAccess.isWrite("GET"));
			for(String v: new String[] { "POST", "PUT", "DELETE", "post" })
			{
				assertTrue(WriteAccess.isWrite(v), v);
			}
		}
	}

	static Request request(Method m, String path, String user)
	{
		Request req = new Request(m, "http://127.0.0.1:8087" + path);
		if(user != null)
		{
			req.getClientInfo().setUser(new User(user));
		}
		return req;
	}

	static Response call(ServerResource res, Method m, String path, String user)
	{
		Request req = request(m, path, user);
		Response resp = new Response(req);
		res.init(new Context(), req, resp);
		Representation body = new StringRepresentation("{\"name\":\"x\"}");
		Representation out;
		ConfigBase cb = (ConfigBase)res;
		if(m == Method.POST)
		{
			out = cb.post(body, req);
		}
		else if(m == Method.PUT)
		{
			out = cb.put(body, req);
		}
		else
		{
			out = cb.delete(req);
		}
		if(out != null)
		{
			resp.setEntity(out);
		}
		return resp;
	}

	@Test
	void everyWriteOfEveryResourceIsRefusedWithoutRestAuthentication() throws Exception
	{
		List<String> checked = new ArrayList<>();
		for(Class<?> c: PathNamesTest.vhostResources())
		{
			WMSConfig info = c.getAnnotation(WMSConfig.class);
			String path = info.restLocation().replaceAll("\\{[^}]+\\}", "live");
			for(String verb: info.restVerbs().split(","))
			{
				if(!WriteAccess.isWrite(verb))
				{
					continue;
				}
				Response r = call((ServerResource)c.getDeclaredConstructor().newInstance(), Method.valueOf(verb), path, "alice");
				assertEquals(403, r.getStatus().getCode(), c.getSimpleName() + " " + verb);
				assertTrue(r.getEntityAsText().contains("authentication is off"), c.getSimpleName() + " " + verb + ": " + r.getEntityAsText());
				checked.add(c.getSimpleName() + " " + verb);
			}
		}
		// the stream overlay resources sit on WSE's own REST base
		String stream = "/v1/server/plugin/urloverlay/vhosts/_defaultVHost_/applications/live/instances/_definst_/streams/cam1/overlay";
		for(Object[] t: new Object[][] { { OverlayConfig.class, "POST" }, { OverlayConfig.class, "PUT" }, { OverlayConfig.class, "DELETE" },
				{ ObjectConfig.class, "POST" }, { ObjectConfig.class, "PUT" }, { ObjectConfig.class, "DELETE" }, { ObjectsConfig.class, "POST" } })
		{
			Class<?> c = (Class<?>)t[0];
			Response r = call((ServerResource)c.getDeclaredConstructor().newInstance(), Method.valueOf((String)t[1]), stream + "/objects/a", "alice");
			assertEquals(403, r.getStatus().getCode(), c.getSimpleName() + " " + t[1]);
			checked.add(c.getSimpleName() + " " + t[1]);
		}
		assertTrue(checked.size() >= 20, checked.toString());
	}
}
