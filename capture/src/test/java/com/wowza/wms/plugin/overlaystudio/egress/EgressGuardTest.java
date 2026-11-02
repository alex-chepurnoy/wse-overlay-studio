/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.egress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * Real requests through the proxy to a local HTTP server. Loopback is a blocked range by default, which makes a
 * local server a convenient stand-in for an internal service.
 */
class EgressGuardTest
{
	private HttpServer origin;
	private int originPort;
	private EgressGuard guard;
	private final List<String> blocks = new ArrayList<>();

	@BeforeEach
	void startOrigin() throws IOException
	{
		origin = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		origin.createContext("/hello", ex -> {
			byte[] body = "hello from origin".getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(200, body.length);
			try(OutputStream os = ex.getResponseBody())
			{
				os.write(body);
			}
		});
		origin.start();
		originPort = origin.getAddress().getPort();
	}

	@AfterEach
	void stop()
	{
		if(guard != null)
		{
			guard.close();
		}
		origin.stop(0);
	}

	private int startGuard(AddressRules rules) throws IOException
	{
		guard = new EgressGuard(rules, "test", (host, d) -> blocks.add(host + " " + d.rule()));
		return guard.start();
	}

	private static String exchange(int proxyPort, String request) throws IOException
	{
		try(Socket s = new Socket(InetAddress.getLoopbackAddress(), proxyPort))
		{
			s.setSoTimeout(10000);
			s.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
			s.getOutputStream().flush();
			return new String(s.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
		}
	}

	@Test
	void plainHttpToLoopbackIsBlockedByDefault() throws IOException
	{
		int p = startGuard(new AddressRules(false, null, null, true, String.valueOf(originPort)));
		String resp = exchange(p, "GET http://127.0.0.1:" + originPort + "/hello HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
		assertTrue(resp.startsWith("HTTP/1.1 403"), resp);
		assertTrue(resp.contains("loopback"), resp);
		assertEquals(List.of("127.0.0.1 loopback"), blocks);
	}

	@Test
	void plainHttpIsForwardedWhenAllowed() throws IOException
	{
		int p = startGuard(new AddressRules(false, "127.0.0.1", null, true, String.valueOf(originPort)));
		String resp = exchange(p, "GET http://127.0.0.1:" + originPort + "/hello HTTP/1.1\r\nHost: 127.0.0.1\r\nProxy-Connection: keep-alive\r\n\r\n");
		assertTrue(resp.startsWith("HTTP/1.1 200"), resp);
		assertTrue(resp.endsWith("hello from origin"), resp);
		assertTrue(blocks.isEmpty());
	}

	@Test
	void connectTunnelIsCheckedToo() throws IOException
	{
		int blockedPort = startGuard(AddressRules.defaults());
		String denied = exchange(blockedPort, "CONNECT 127.0.0.1:" + originPort + " HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
		assertTrue(denied.startsWith("HTTP/1.1 403"), denied);
		guard.close();

		int p = startGuard(new AddressRules(false, "127.0.0.1", null, true, String.valueOf(originPort)));
		try(Socket s = new Socket(InetAddress.getLoopbackAddress(), p))
		{
			s.setSoTimeout(10000);
			OutputStream out = s.getOutputStream();
			InputStream in = s.getInputStream();
			out.write(("CONNECT 127.0.0.1:" + originPort + " HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
			out.flush();
			byte[][] head = EgressGuard.readHead(in);
			assertTrue(new String(head[0], StandardCharsets.ISO_8859_1).startsWith("HTTP/1.1 200"));
			out.write(("GET /hello HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
			out.flush();
			String tunneled = new String(head[1], StandardCharsets.ISO_8859_1) + new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
			assertTrue(tunneled.contains("hello from origin"), tunneled);
		}
	}

	@Test
	void portsOtherThan80And443AreRefusedBeforeAnyLookup() throws IOException
	{
		int p = startGuard(AddressRules.defaults());
		// a name that does not resolve would be "unresolved" if it were looked up first
		String resp = exchange(p, "CONNECT no-such-host.invalid:22 HTTP/1.1\r\nHost: no-such-host.invalid\r\n\r\n");
		assertTrue(resp.startsWith("HTTP/1.1 403"), resp);
		assertTrue(resp.contains("port 22"), resp);
		assertEquals(List.of("no-such-host.invalid port"), blocks);
		String plain = exchange(p, "GET http://127.0.0.1:" + originPort + "/hello HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
		assertTrue(plain.contains("port " + originPort), plain);
	}

	@Test
	void nonProxyRequestIsRejected() throws IOException
	{
		int p = startGuard(AddressRules.defaults());
		String resp = exchange(p, "GET /hello HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
		assertTrue(resp.startsWith("HTTP/1.1 400"), resp);
	}
}
