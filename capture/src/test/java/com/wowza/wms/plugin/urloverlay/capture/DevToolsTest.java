/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;

/** The DevTools client against a small WebSocket server that answers as Chrome would. */
class DevToolsTest
{
	/** One WebSocket connection, server side: reads the client's text messages and sends its own. */
	static final class Peer
	{
		final Socket socket;
		final DataInputStream in;
		final OutputStream out;

		Peer(Socket socket) throws IOException
		{
			this.socket = socket;
			this.in = new DataInputStream(socket.getInputStream());
			this.out = socket.getOutputStream();
		}

		/** The next text message, or null when the client closed. */
		String read() throws IOException
		{
			ByteArrayOutputStream msg = new ByteArrayOutputStream();
			while(true)
			{
				int b0 = in.read();
				if(b0 < 0)
				{
					return null;
				}
				int b1 = in.read();
				long len = b1 & 0x7f;
				if(len == 126)
				{
					len = in.readUnsignedShort();
				}
				else if(len == 127)
				{
					len = in.readLong();
				}
				byte[] mask = new byte[4];
				in.readFully(mask);
				byte[] data = new byte[(int)len];
				in.readFully(data);
				for(int i = 0; i < data.length; i++)
				{
					data[i] ^= mask[i % 4];
				}
				int op = b0 & 0x0f;
				if(op == 8)
				{
					return null;
				}
				msg.write(data);
				if((b0 & 0x80) != 0)
				{
					return msg.toString(StandardCharsets.UTF_8);
				}
			}
		}

		synchronized void send(String text) throws IOException
		{
			byte[] data = text.getBytes(StandardCharsets.UTF_8);
			out.write(0x81);
			if(data.length < 126)
			{
				out.write(data.length);
			}
			else if(data.length < 65536)
			{
				out.write(126);
				out.write(data.length >> 8);
				out.write(data.length);
			}
			else
			{
				out.write(127);
				for(int i = 7; i >= 0; i--)
				{
					out.write((int)((long)data.length >> (8 * i)));
				}
			}
			out.write(data);
			out.flush();
		}
	}

	/** Serves one connection; handler gets each message the client sends, and the peer to answer on. */
	static int serve(BiConsumer<Map<String, Object>, Peer> handler) throws IOException
	{
		ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
		Thread.ofVirtual().start(() -> {
			try(server; Socket s = server.accept())
			{
				InputStream in = s.getInputStream();
				StringBuilder head = new StringBuilder();
				while(!head.toString().endsWith("\r\n\r\n"))
				{
					head.append((char)in.read());
				}
				String key = head.toString().lines().filter(l -> l.toLowerCase().startsWith("sec-websocket-key:")).findFirst().orElseThrow().split(":", 2)[1].trim();
				String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
						.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
				s.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n")
						.getBytes(StandardCharsets.US_ASCII));
				Peer p = new Peer(s);
				for(String m; (m = p.read()) != null;)
				{
					handler.accept(Json.object(m), p);
				}
			}
			catch(Exception e)
			{
				// the test ended the connection
			}
		});
		return server.getLocalPort();
	}

	static String answer(Object id, Object result)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", id);
		m.put("result", result);
		return Json.write(m);
	}

	@Test
	void callsGetTheirAnswersErrorsAndEvents() throws Exception
	{
		String big = "x".repeat(3 * 1024 * 1024);
		int port = serve((m, p) -> {
			try
			{
				switch(String.valueOf(m.get("method")))
				{
					case "Echo.it" -> p.send(answer(m.get("id"), m.get("params")));
					case "Fail.it" -> p.send("{\"id\":" + m.get("id") + ",\"error\":{\"code\":-32000,\"message\":\"No target with given id found\"}}");
					case "Event.please" -> {
						p.send("{\"method\":\"Page.screencastFrame\",\"params\":{\"data\":\"" + big + "\",\"sessionId\":7}}");
						p.send(answer(m.get("id"), Map.of()));
					}
					default -> {
						// never answered
					}
				}
			}
			catch(IOException e)
			{
				throw new RuntimeException(e);
			}
		});
		DevTools d = DevTools.connect("ws://127.0.0.1:" + port + "/devtools/page/1", "test", 5000);
		try
		{
			assertEquals(Map.of("a", 1L, "b", "two"), d.call("Echo.it", Map.of("a", 1, "b", "two"), 5000), "the result of its own call");
			DevTools.Failure f = assertThrows(DevTools.Failure.class, () -> d.call("Fail.it", null, 5000));
			assertTrue(f.getMessage().contains("No target"), f.getMessage());
			CompletableFuture<Map<String, Object>> event = new CompletableFuture<>();
			d.on("Page.screencastFrame", event::complete);
			d.call("Event.please", null, 5000);
			Map<String, Object> e = event.get(5, TimeUnit.SECONDS);
			assertEquals(big.length(), ((String)e.get("data")).length(), "a message of several MB arrives whole");
			assertEquals(7L, e.get("sessionId"));
			long t0 = System.currentTimeMillis();
			assertThrows(TimeoutException.class, () -> d.call("Never.answered", null, 300));
			assertTrue(System.currentTimeMillis() - t0 < 3000, "a call waits its timeout, no longer");
			assertEquals(Map.of("again", true), d.call("Echo.it", Map.of("again", true), 5000), "the connection still works after a timeout");
		}
		finally
		{
			d.close("test done");
		}
	}

	@Test
	void aClosedConnectionFailsWaitingCallsAtOnce() throws Exception
	{
		CompletableFuture<Peer> peer = new CompletableFuture<>();
		int port = serve((m, p) -> peer.complete(p));
		DevTools d = DevTools.connect("ws://127.0.0.1:" + port + "/devtools/page/1", "test", 5000);
		CompletableFuture<String> closed = new CompletableFuture<>();
		d.onClose(closed::complete);
		CompletableFuture<Exception> failed = new CompletableFuture<>();
		Thread.ofVirtual().start(() -> {
			try
			{
				d.call("Never.answered", null, 30_000);
				failed.complete(null);
			}
			catch(Exception e)
			{
				failed.complete(e);
			}
		});
		// the server goes away while the call waits, as when Chrome exits
		peer.get(5, TimeUnit.SECONDS).socket.close();
		long t0 = System.currentTimeMillis();
		Exception e = failed.get(10, TimeUnit.SECONDS);
		assertTrue(e instanceof DevTools.Failure, "the call failed, it did not time out: " + e);
		assertTrue(System.currentTimeMillis() - t0 < 5000, "at once, not after its 30 s timeout");
		assertTrue(closed.get(5, TimeUnit.SECONDS).contains("DevTools connection"), "close listeners hear of it");
		assertThrows(DevTools.Failure.class, () -> d.call("After.close", null, 1000), "later calls fail without waiting");
		assertTrue(!d.isOpen());
	}
}
