/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.wowza.wms.plugin.urloverlay.capture.PageRequest;
import com.wowza.wms.plugin.urloverlay.capture.Wire;
import com.wowza.wms.plugin.urloverlay.capture.Wire.Message;
import com.wowza.wms.plugin.urloverlay.egress.EgressRules;

/**
 * The module's handle on a capture against a stand-in service: frames arrive, the last one is kept while the service
 * is gone, and the capture is opened again once a restarted service answers.
 */
class CaptureClientTest
{
	/** A capture service that knows only what it opened since it started, as the real one does. */
	static final class FakeService implements AutoCloseable
	{
		final Path socket;
		final Color color;
		final int protocol;
		final Set<String> ids = ConcurrentHashMap.newKeySet();
		final List<PageRequest> opened = new CopyOnWriteArrayList<>();
		final AtomicInteger reloads = new AtomicInteger();
		// the latest frame's number; the background loop of the real service raises it
		final java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong(1);
		final AtomicInteger frameRequests = new AtomicInteger();
		volatile long slowMs = 0;
		ServerSocketChannel server;

		FakeService(Path socket, Color color, int protocol) throws IOException
		{
			this.socket = socket;
			this.color = color;
			this.protocol = protocol;
			Files.deleteIfExists(socket);
			server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
			server.bind(UnixDomainSocketAddress.of(socket));
			Thread t = new Thread(this::accept, "fake-capture-service");
			t.setDaemon(true);
			t.start();
		}

		void accept()
		{
			while(server.isOpen())
			{
				try
				{
					SocketChannel c = server.accept();
					Thread t = new Thread(() -> serve(c));
					t.setDaemon(true);
					t.start();
				}
				catch(IOException e)
				{
					return;
				}
			}
		}

		void serve(SocketChannel c)
		{
			try(c; InputStream in = Channels.newInputStream(c); OutputStream out = Channels.newOutputStream(c))
			{
				Message m;
				while((m = Wire.read(in)) != null && server.isOpen())
				{
					Map<String, Object> h = Wire.ok();
					byte[] body = null;
					switch(m.str(Wire.OP))
					{
						case Wire.HELLO -> h.put("protocol", protocol);
						case Wire.OPEN -> {
							opened.add(PageRequest.fromMap(m.header()));
							String id = "c" + ids.size();
							ids.add(id);
							h.put("capture", id);
						}
						case Wire.FRAME -> {
							frameRequests.incrementAndGet();
							if(!ids.contains(m.str("capture")))
							{
								h = Wire.error("Unknown capture");
								break;
							}
							sleep(slowMs);
							long since = m.num("since", -1);
							// a long poll: wait a little for a newer frame, as the service does
							long end = System.currentTimeMillis() + Math.min(200, m.num("waitMs", 0));
							while(seq.get() <= since && System.currentTimeMillis() < end)
							{
								sleep(5);
							}
							long now = seq.get();
							h.put("seq", now);
							h.put("capturedAt", System.currentTimeMillis());
							h.put("captureMs", 55);
							h.put("reloads", reloads.get());
							h.put("status", Map.of("state", "running", "sandbox", "on"));
							body = now > since ? png(color) : null;
						}
						default -> {
						}
					}
					Wire.write(out, h, body);
				}
			}
			catch(IOException e)
			{
				// the client went away
			}
		}

		@Override
		public void close() throws IOException
		{
			server.close();
			Files.deleteIfExists(socket);
		}
	}

	static void sleep(long ms)
	{
		try
		{
			Thread.sleep(ms);
		}
		catch(InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
	}

	/** Draws until the image the draws get satisfies the test, or five seconds pass. */
	static BufferedImage drawUntil(WebPageCapture page, java.util.function.Predicate<BufferedImage> ok) throws Exception
	{
		BufferedImage img = null;
		for(int i = 0; i < 250 && (img == null || !ok.test(img)); i++)
		{
			sleep(20);
			img = page.capture(80);
		}
		return img;
	}

	static byte[] png(Color c) throws IOException
	{
		BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
		img.setRGB(1, 1, c.getRGB());
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "png", out);
		return out.toByteArray();
	}

	static Path socketDir(Path fallback) throws IOException
	{
		// on some Windows machines a Unix socket under the user's temp folder binds but refuses connections
		String t = System.getProperty("jdk.net.unixdomain.tmpdir");
		return t == null ? fallback : Files.createTempDirectory(Path.of(t), "uo");
	}

	@AfterEach
	void restore()
	{
		CaptureClient.use(new CaptureClient(Path.of(CaptureClient.defaultSocket(CaptureClient.isWindows())), EgressRules.defaults(), 1000));
	}

	@Test
	void framesArriveAndTheLastIsKeptWhileTheServiceIsGone(@TempDir Path dir) throws Exception
	{
		Path sock = socketDir(dir).resolve("c.sock");
		EgressRules rules = new EgressRules(true, "10.0.0.0/8", "ads.example", true, "8080");
		CaptureClient client = new CaptureClient(sock, rules, 50);
		AtomicInteger reloaded = new AtomicInteger();
		WebPageCapture page = new WebPageCapture(new WebPageCapture.Settings("https://scores.example/", 640, 360, new Color(1, 2, 3), true, "score"),
				() -> client, () -> 1500, reloaded::incrementAndGet);
		page.reloadEvery("layer", 5);

		BufferedImage first;
		try(FakeService s = new FakeService(sock, Color.RED, Wire.PROTOCOL))
		{
			// a draw never waits: before the first frame it gets nothing, at once
			long t0 = System.nanoTime();
			assertNull(page.capture(80));
			assertTrue((System.nanoTime() - t0) / 1_000_000 < 50, "the draw path does not wait on the service");
			first = drawUntil(page, img -> img.getRGB(1, 1) == Color.RED.getRGB());
			assertEquals(Color.RED.getRGB(), first.getRGB(1, 1));
			assertEquals(1L, page.frameSeq());
			assertEquals("running", page.status().get("state"));
			Map<?, ?> timing = (Map<?, ?>)page.status().get("timing");
			assertEquals(100L, ((Number)timing.get("intervalMs")).longValue(), "the service is asked for frames at the overlay's refresh");
			assertEquals(55L, ((Number)timing.get("screenshotMs")).longValue());
			assertNotNull(timing.get("decodeMs"));
			PageRequest sent = s.opened.get(0);
			assertEquals(rules, sent.rules(), "WSE's egress rules travel with the capture");
			assertEquals(1500, sent.pageDelayMs());
			assertEquals(5.0, sent.reloadRate());
			assertEquals(0x010203, sent.keyRgb());

			// draws between frames get the same image, decoded once
			assertSame(first, page.capture(80));
			assertSame(first, page.capture(80));

			// a new frame and a reload in the service
			s.reloads.incrementAndGet();
			s.seq.incrementAndGet();
			BufferedImage before = first;
			BufferedImage second = drawUntil(page, img -> img != before);
			assertNotSame(first, second);
			assertEquals(2L, page.frameSeq());
			for(int i = 0; i < 100 && reloaded.get() == 0; i++)
			{
				sleep(20);
			}
			assertEquals(1, reloaded.get(), "a reload in the service redraws the layer");

			// a service slow to answer does not slow the draws
			s.slowMs = 1500;
			sleep(100);
			t0 = System.nanoTime();
			for(int i = 0; i < 20; i++)
			{
				assertSame(second, page.capture(80));
			}
			assertTrue((System.nanoTime() - t0) / 1_000_000 < 100, "twenty draws while the service stalls");
			s.slowMs = 0;
			first = second;
		}

		// the service stops: the stream keeps its picture and the page says how to start the service
		for(int i = 0; i < 250 && !WebPageCapture.SERVICE_NOT_RUNNING.equals(page.status().get("state")); i++)
		{
			sleep(20);
		}
		assertSame(first, page.capture(80));
		assertEquals(WebPageCapture.SERVICE_NOT_RUNNING, page.status().get("state"));
		String why = String.valueOf(page.status().get("lastError"));
		assertTrue(why.contains("systemctl start WowzaURLOverlayCapture") && why.contains("net start WowzaURLOverlayCapture"), why);
		assertFalse(why.contains("Exception"), "no exception names for an operator: " + why);

		// a restarted service has forgotten the capture: it is opened again and frames resume
		try(FakeService s = new FakeService(sock, Color.BLUE, Wire.PROTOCOL))
		{
			BufferedImage again = drawUntil(page, img -> img.getRGB(1, 1) == Color.BLUE.getRGB());
			assertEquals(Color.BLUE.getRGB(), again.getRGB(1, 1), "reconnected after the backoff");
			assertEquals(1, s.opened.size(), "opened once on the new service");
			assertEquals("running", page.status().get("state"));
		}
		page.close();
	}

	@Test
	void unchangedFramesAreNotSentAgain(@TempDir Path dir) throws Exception
	{
		Path sock = socketDir(dir).resolve("c.sock");
		CaptureClient client = new CaptureClient(sock, EgressRules.defaults(), 50);
		try(FakeService s = new FakeService(sock, Color.RED, Wire.PROTOCOL))
		{
			WebPageCapture page = new WebPageCapture(new WebPageCapture.Settings("https://x.example/", 64, 64, new Color(1, 2, 3), true, "x"),
					() -> client, () -> 0, null);
			BufferedImage img = drawUntil(page, x -> true);
			assertNotNull(img);
			sleep(600);
			assertSame(img, page.capture(80), "no new frame, so the same decoded image");
			Map<?, ?> timing = (Map<?, ?>)page.status().get("timing");
			assertEquals(1L, ((Number)timing.get("framesReceived")).longValue(), "the frame was sent once though the module kept asking");
			assertTrue(s.frameRequests.get() >= 2, "the fetcher kept asking for newer frames");
			page.close();
		}
	}

	@Test
	void anotherProtocolIsReportedNotRetriedAsIfDown(@TempDir Path dir) throws Exception
	{
		Path sock = socketDir(dir).resolve("c.sock");
		CaptureClient client = new CaptureClient(sock, EgressRules.defaults(), 50);
		try(FakeService s = new FakeService(sock, Color.RED, Wire.PROTOCOL + 1))
		{
			WebPageCapture page = new WebPageCapture(new WebPageCapture.Settings("https://x.example/", 64, 64, new Color(1, 2, 3), true, "x"),
					() -> client, () -> 0, null);
			page.capture(0);
			for(int i = 0; i < 250 && page.status().get("lastError") == null; i++)
			{
				sleep(20);
			}
			String why = String.valueOf(page.status().get("lastError"));
			assertTrue(why.contains("capture protocol") && why.contains("same URL Overlay version"), why);
			assertTrue(s.opened.isEmpty());
		}
	}

	@Test
	void theServiceStatusSaysWhenItIsNotRunning(@TempDir Path dir) throws Exception
	{
		CaptureClient client = new CaptureClient(socketDir(dir).resolve("none.sock"), EgressRules.defaults(), 50);
		Map<String, Object> st = client.serviceStatus();
		assertEquals(false, st.get("running"));
		assertTrue(String.valueOf(st.get("message")).contains("not running"));
	}
}
