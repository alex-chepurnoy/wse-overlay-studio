/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.capture;

import java.io.Closeable;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.wowza.wms.plugin.urloverlay.capture.Wire.Message;

/**
 * The service's Unix domain socket. Each connection is served on its own thread, one request at a time; the module
 * keeps a few connections, so a slow page does not hold up the others. Access is by the socket file's permissions.
 */
public final class CaptureServer implements Closeable
{
	private final Log log = Log.get();
	private final Path socket;
	private final CaptureHandler handler;
	private final ExecutorService threads = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("capture-conn-", 0).factory());
	private ServerSocketChannel server;
	private volatile boolean closed;

	public CaptureServer(Path socket, CaptureHandler handler)
	{
		this.socket = socket;
		this.handler = handler;
	}

	/** Binds the socket, removing a file a previous run left behind, and starts accepting. */
	public void start() throws IOException
	{
		if(socket.getParent() != null)
		{
			Files.createDirectories(socket.getParent());
		}
		// a socket file outlives its process; a new bind fails until it is gone
		Files.deleteIfExists(socket);
		server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
		server.bind(UnixDomainSocketAddress.of(socket));
		Thread t = Thread.ofPlatform().daemon(false).name("capture-accept").start(this::accept);
		log.info("UrlOverlay capture service listening on " + socket);
		t.setUncaughtExceptionHandler((th, e) -> log.error("UrlOverlay capture: accept loop ended", e));
	}

	private void accept()
	{
		while(!closed)
		{
			try
			{
				SocketChannel c = server.accept();
				threads.execute(() -> serve(c));
			}
			catch(IOException e)
			{
				if(!closed)
				{
					log.error("UrlOverlay capture: accepting a connection failed", e);
				}
			}
		}
	}

	void serve(SocketChannel c)
	{
		try(c; InputStream in = Channels.newInputStream(c); OutputStream out = Channels.newOutputStream(c))
		{
			Message first = Wire.read(in);
			if(first == null)
			{
				return;
			}
			Message hello = Wire.HELLO.equals(first.str(Wire.OP)) ? CaptureHandler.hello(first)
					: new Message(Wire.error("The first request on a connection must be hello"));
			Wire.write(out, hello.header(), hello.body());
			if(!hello.ok())
			{
				return;
			}
			Message m;
			while((m = Wire.read(in)) != null)
			{
				Message answer = handler.handle(m);
				Wire.write(out, answer.header(), answer.body());
			}
		}
		catch(IOException e)
		{
			// the module went away or restarted; its captures close when idle
		}
	}

	@Override
	public void close() throws IOException
	{
		closed = true;
		if(server != null)
		{
			server.close();
		}
		threads.shutdownNow();
		Files.deleteIfExists(socket);
	}
}
