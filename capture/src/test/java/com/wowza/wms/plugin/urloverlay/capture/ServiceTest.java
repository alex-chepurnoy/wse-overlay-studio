/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.wowza.wms.plugin.urloverlay.capture.Wire.Message;

/** The service's requests with a stand-in browser, its socket, its account check and its configuration. */
class ServiceTest
{
	/** A page that answers with a fixed PNG and records what it was asked. */
	static final class FakePage implements CaptureHandler.Page
	{
		final byte[] png = { (byte)0x89, 'P', 'N', 'G', 1, 2, 3 };
		final List<Runnable> listeners = new ArrayList<>();
		final List<String> reloads = new ArrayList<>();

		@Override
		public void reloadNow(String why)
		{
			reloads.add(why);
		}
		int delay = -1;
		double rate = -1;
		long interval = -1;
		boolean started;
		long seq = 0;
		int fullStatus;

		/** A new frame, as the background loop would take one. */
		synchronized void frame()
		{
			seq++;
			notifyAll();
		}

		@Override
		public void start()
		{
			started = true;
		}

		@Override
		public void interval(long ms)
		{
			interval = ms;
		}

		@Override
		public synchronized long awaitFrame(long since, long waitMs) throws InterruptedException
		{
			long end = System.currentTimeMillis() + waitMs;
			long left;
			while(seq <= since && (left = end - System.currentTimeMillis()) > 0)
			{
				wait(left);
			}
			return seq;
		}

		@Override
		public byte[] lastPng()
		{
			return seq == 0 ? null : png;
		}

		@Override
		public synchronized long seq()
		{
			return seq;
		}

		@Override
		public long capturedAtMs()
		{
			return 1234;
		}

		@Override
		public long captureMs()
		{
			return 55;
		}

		@Override
		public Map<String, Object> status()
		{
			fullStatus++;
			return Map.of("state", "running", "sandbox", "on");
		}

		@Override
		public Map<String, Object> quickStatus()
		{
			return Map.of("state", "running", "sandbox", "on");
		}

		@Override
		public void pageDelay(int ms)
		{
			delay = ms;
		}

		@Override
		public void reloadEvery(double r)
		{
			rate = r;
		}

		@Override
		public void addReloadListener(Runnable r)
		{
			listeners.add(r);
		}

		@Override
		public void removeReloadListener(Runnable r)
		{
			listeners.remove(r);
		}
	}

	static final class FakePages implements CaptureHandler.Pages
	{
		final List<PageRequest> opened = new ArrayList<>();
		final FakePage page = new FakePage();
		int released;

		@Override
		public CaptureHandler.Page acquire(PageRequest r)
		{
			opened.add(r);
			return page;
		}

		@Override
		public void release(CaptureHandler.Page p)
		{
			released++;
		}
	}

	static Message open(CaptureHandler h)
	{
		Map<String, Object> m = Wire.header(Wire.OPEN);
		m.putAll(new PageRequest("https://scores.example/", 640, 360, 0x010203, true, "score", 1500, 5, 100, null).toMap());
		return h.handle(new Message(m));
	}

	static Message call(CaptureHandler h, String op, String id)
	{
		Map<String, Object> m = Wire.header(op);
		m.put("capture", id);
		return h.handle(new Message(m));
	}

	@Test
	void openFrameUpdateAndClose()
	{
		FakePages pages = new FakePages();
		CaptureHandler h = new CaptureHandler(pages, s -> Map.of("limit", 4));
		Message o = open(h);
		assertTrue(o.ok());
		String id = o.str("capture");
		assertEquals(1500, pages.page.delay);
		assertEquals(5.0, pages.page.rate);
		assertEquals("https://scores.example/", pages.opened.get(0).url());

		assertTrue(pages.page.started, "frames are taken in the background from the open on");
		assertEquals(100, pages.page.interval);

		Message none = call(h, Wire.FRAME, id);
		assertTrue(none.ok());
		assertEquals(0, none.body().length, "nothing taken yet");
		assertEquals(true, none.header().get("unchanged"));

		// an update asking for a reload has the page load its address again; one without it does not
		Map<String, Object> up = Wire.header(Wire.UPDATE);
		up.put("capture", id);
		up.put("reloadRate", 5.0);
		assertTrue(h.handle(new Message(up)).ok());
		assertEquals(List.of(), pages.page.reloads);
		up.put("reload", true);
		assertTrue(h.handle(new Message(up)).ok());
		assertEquals(List.of("asked from the page"), pages.page.reloads);

		pages.page.frame();
		Message f = frame(h, id, -1, 0);
		assertArrayEquals(pages.page.png, f.body(), "Chrome's own PNG, as it came");
		assertEquals(1L, f.num("seq", -1));
		assertEquals(1234L, f.num("capturedAt", -1));
		assertEquals(55L, f.num("captureMs", -1));
		assertEquals(0, pages.page.fullStatus, "a frame answer never looks up Chrome's processes");
		Message same = frame(h, id, 1, 0);
		assertEquals(0, same.body().length, "a frame the module has is not sent again");

		// a frame request waits for the next frame, and returns as soon as it is taken
		new Thread(() -> {
			sleep(150);
			pages.page.frame();
		}).start();
		long t0 = System.currentTimeMillis();
		Message next = frame(h, id, 1, 3000);
		long waited = System.currentTimeMillis() - t0;
		assertEquals(2L, next.num("seq", -1));
		assertTrue(waited >= 100 && waited < 2000, "waited " + waited + " ms");
		assertTrue(frame(h, id, 2, 50).header().get("unchanged").equals(true), "and gives up after waitMs");

		pages.page.listeners.forEach(Runnable::run);
		assertEquals(1L, frame(h, id, 2, 0).num("reloads", -1), "a reload is counted for the module");

		Map<String, Object> u = Wire.header(Wire.UPDATE);
		u.put("capture", id);
		u.put("reloadRate", 2.0);
		u.put("intervalMs", 250);
		assertTrue(h.handle(new Message(u)).ok());
		assertEquals(2.0, pages.page.rate);
		assertEquals(250, pages.page.interval);

		Message st = h.handle(new Message(Wire.header(Wire.STATUS)));
		assertEquals(4L, st.num("limit", -1));
		assertEquals(1, ((List<?>)st.header().get("captures")).size());

		assertTrue(call(h, Wire.CLOSE, id).ok());
		assertEquals(1, pages.released);
		assertTrue(pages.page.listeners.isEmpty());
		Message gone = call(h, Wire.FRAME, id);
		assertFalse(gone.ok());
		assertEquals("Unknown capture", gone.str("error"), "the module opens it again on this answer");
		assertFalse(h.handle(new Message(Wire.header("dance"))).ok());
	}

	static Message frame(CaptureHandler h, String id, long since, long waitMs)
	{
		Map<String, Object> m = Wire.header(Wire.FRAME);
		m.put("capture", id);
		m.put("since", since);
		m.put("waitMs", waitMs);
		return h.handle(new Message(m));
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

	@Test
	void capturesNobodyAsksForAreClosed()
	{
		FakePages pages = new FakePages();
		CaptureHandler h = new CaptureHandler(pages, s -> Map.of());
		open(h);
		assertEquals(0, h.closeIdle(System.currentTimeMillis()));
		assertEquals(1, h.closeIdle(System.currentTimeMillis() + CaptureHandler.IDLE_MS + 1));
		assertEquals(1, pages.released);
		assertEquals(0, h.open());
	}

	@Test
	void theSocketReplacesAStaleFileAndChecksTheProtocol(@TempDir Path dir) throws Exception
	{
		Path sock = socketDir(dir).resolve("c.sock");
		Files.writeString(sock, "left by an earlier run");
		try(CaptureServer server = new CaptureServer(sock, new CaptureHandler(new FakePages(), s -> Map.of())))
		{
			server.start();
			try(SocketChannel c = SocketChannel.open(StandardProtocolFamily.UNIX))
			{
				c.connect(UnixDomainSocketAddress.of(sock));
				OutputStream out = Channels.newOutputStream(c);
				InputStream in = Channels.newInputStream(c);
				Map<String, Object> h = Wire.header(Wire.HELLO);
				h.put("protocol", Wire.PROTOCOL);
				Wire.write(out, h, null);
				Message hello = Wire.read(in);
				assertTrue(hello.ok());
				assertEquals(CaptureVersion.VERSION, hello.str("version"));
				Wire.write(out, Wire.header(Wire.STATUS), null);
				assertTrue(Wire.read(in).ok());
			}
			try(SocketChannel c = SocketChannel.open(StandardProtocolFamily.UNIX))
			{
				c.connect(UnixDomainSocketAddress.of(sock));
				Map<String, Object> h = Wire.header(Wire.HELLO);
				h.put("protocol", 99);
				Wire.write(Channels.newOutputStream(c), h, null);
				InputStream in = Channels.newInputStream(c);
				Message no = Wire.read(in);
				assertFalse(no.ok());
				assertNull(Wire.read(in), "and the connection is closed");
			}
		}
		assertFalse(Files.exists(sock), "the socket file goes with the service");
	}

	/**
	 * Where a test socket can live. On some Windows machines a Unix socket under the user's temp folder binds but
	 * refuses connections; the build points jdk.net.unixdomain.tmpdir somewhere that works, so tests use that.
	 */
	static Path socketDir(Path fallback) throws java.io.IOException
	{
		String t = System.getProperty("jdk.net.unixdomain.tmpdir");
		return t == null ? fallback : Files.createTempDirectory(Path.of(t), "uo");
	}

	@Test
	void neverAsRootOrLocalSystem()
	{
		assertNotNull(AccountCheck.refusal(false, "root", null));
		assertNull(AccountCheck.refusal(false, "wse-url-overlay", null));
		assertNotNull(AccountCheck.refusal(true, "WIN-HOST$", AccountCheck.SYSTEM_SID), "LocalSystem by its SID");
		assertNotNull(AccountCheck.refusal(true, "SYSTEM", null), "or by its name when the SID cannot be read");
		assertNull(AccountCheck.refusal(true, "WowzaURLOverlayCapture", "S-1-5-80-1234"), "the service's virtual account");
		assertNull(AccountCheck.refusal(true, "root", "S-1-5-21-1"), "root means nothing on Windows");
	}

	@Test
	void configurationDefaultsAndWarnings()
	{
		Properties p = new Properties();
		p.setProperty("socket", "/run/wse-url-overlay/capture.sock");
		p.setProperty("workDir", "/var/lib/wse-url-overlay");
		p.setProperty("chromedriver", "/opt/wse-url-overlay/chrome-for-testing/1/chromedriver");
		p.setProperty("maxCaptures", "zero");
		p.setProperty("sandbox", "off");
		ServiceConfig c = ServiceConfig.of(p);
		assertEquals(Path.of("/run/wse-url-overlay/capture.sock"), c.socket());
		assertEquals(4, c.maxCaptures());
		assertEquals(1024, c.maxBrowserMb());
		assertEquals(4, c.pagesPerBrowser());
		assertTrue(c.warnings().stream().noneMatch(w -> w.contains("chromedriver")), "the setting of earlier versions is accepted quietly");
		assertNull(c.logFile(), "stdout unless a log file is named");
		assertNull(c.chrome());
		assertTrue(c.warnings().stream().anyMatch(w -> w.contains("sandbox")), "an unknown key is reported, and does nothing: " + c.warnings());
		assertTrue(c.warnings().stream().anyMatch(w -> w.contains("maxCaptures")), c.warnings().toString());
		p.remove("socket");
		assertThrows(IllegalArgumentException.class, () -> ServiceConfig.of(p));
	}
}
