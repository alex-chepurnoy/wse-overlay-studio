/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.install;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Chrome for Testing and the chromedriver built for it, fetched at install time from Google's Chrome for Testing
 * service. The pair always matches and does not update itself, so a Chrome update on the server can never leave the
 * service with a chromedriver for another version. The zips unpack to chrome-win64/chrome.exe and chromedriver-win64/chromedriver.exe; chrome-linux64/chrome and
 * chromedriver-linux64/chromedriver.
 * <p>
 * Google publishes no checksum for these files, so the integrity guard is where they come from: the list only from
 * googlechromelabs.github.io and the zips only from storage.googleapis.com, both over HTTPS, with every redirect checked
 * against the same rule.
 */
final class ChromeDownload
{
	/** Google's list of the current Chrome for Testing builds per channel, with download URLs per platform. */
	static final String LIST = "https://googlechromelabs.github.io/chrome-for-testing/last-known-good-versions-with-downloads.json";
	/**
	 * Written last into a version folder, once both programs are unpacked: a folder without it is incomplete and is
	 * cleaned and unpacked again. The folder itself is never renamed after unpacking, because on Windows a scanner
	 * (antivirus or the search indexer) holding a freshly written file makes the rename fail with access denied.
	 */
	static final String COMPLETE = ".complete";
	private static final int MAX_REDIRECTS = 5;

	/** Where the list is read and which addresses the list and the zips may come from. */
	record Source(String listUrl, Predicate<URI> listAllowed, Predicate<URI> downloadAllowed)
	{
	}

	static final Source GOOGLE = new Source(LIST, u -> https(u, "googlechromelabs.github.io"), u -> https(u, "storage.googleapis.com"));

	/** Tests only: a stand-in for Google's service. Not a setting; nothing outside the tests sets it. */
	static volatile Source source = GOOGLE;

	private ChromeDownload()
	{
	}

	static boolean https(URI u, String host)
	{
		return u != null && "https".equalsIgnoreCase(u.getScheme()) && host.equalsIgnoreCase(u.getHost()) && u.getUserInfo() == null
				&& (u.getPort() == -1 || u.getPort() == 443);
	}

	/** One build: its version and, for this OS, where to get Chrome and chromedriver. */
	record Build(String version, String platform, String chromeUrl, String driverUrl)
	{
	}

	/** The extracted pair. */
	record Pair(Path dir, Path chrome, Path driver, String version)
	{
	}

	/** Download progress: which file (1 or 2), its bytes so far, and its size, -1 when the server does not say. */
	interface Progress
	{
		void update(int file, String name, long done, long total);
	}

	static String platform()
	{
		String os = System.getProperty("os.name", "").toLowerCase();
		String arch = System.getProperty("os.arch", "").toLowerCase();
		boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
		if(os.startsWith("windows") && x64)
		{
			return "win64";
		}
		if(os.startsWith("linux") && x64)
		{
			return "linux64";
		}
		return null;
	}

	/** A client that follows no redirect by itself; {@link #send} checks each one. */
	static HttpClient client()
	{
		return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(20)).build();
	}

	// sends a GET, following up to MAX_REDIRECTS redirects, each only to an address the rule allows
	private static <T> HttpResponse<T> send(HttpClient http, URI uri, Predicate<URI> allowed, Duration timeout, HttpResponse.BodyHandler<T> body)
			throws IOException, InterruptedException
	{
		for(int k = 0; k <= MAX_REDIRECTS; k++)
		{
			if(!allowed.test(uri))
			{
				throw new IOException("Refused " + uri + ": Chrome for Testing is only fetched from Google's own addresses over HTTPS");
			}
			HttpResponse<T> r = http.send(HttpRequest.newBuilder(uri).timeout(timeout).build(), body);
			int c = r.statusCode();
			if(c != 301 && c != 302 && c != 303 && c != 307 && c != 308)
			{
				return r;
			}
			if(r.body() instanceof InputStream in)
			{
				in.close();
			}
			String to = r.headers().firstValue("location").orElse(null);
			if(to == null)
			{
				throw new IOException(uri + " redirected without a Location");
			}
			uri = uri.resolve(to);
		}
		throw new IOException("Too many redirects from " + uri);
	}

	/** The Stable build for this platform, from the list. */
	@SuppressWarnings("unchecked")
	static Build stable(HttpClient http, String platform) throws IOException, InterruptedException
	{
		Source src = source;
		HttpResponse<String> r = send(http, URI.create(src.listUrl()), src.listAllowed(), Duration.ofSeconds(30), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if(r.statusCode() != 200)
		{
			throw new IOException("Chrome for Testing list answered " + r.statusCode());
		}
		Map<String, Object> root = (Map<String, Object>)Json.parse(r.body());
		Map<String, Object> stable = (Map<String, Object>)((Map<String, Object>)root.get("channels")).get("Stable");
		Map<String, Object> downloads = (Map<String, Object>)stable.get("downloads");
		String version = String.valueOf(stable.get("version"));
		if(!version.matches("\\d+(\\.\\d+){3}"))
		{
			throw new IOException("The Chrome for Testing list gives an unexpected version: " + version);
		}
		Build b = new Build(version, platform, url((List<Object>)downloads.get("chrome"), platform), url((List<Object>)downloads.get("chromedriver"), platform));
		for(String u: List.of(b.chromeUrl(), b.driverUrl()))
		{
			if(!src.downloadAllowed().test(URI.create(u)))
			{
				throw new IOException("Refused " + u + ": Chrome for Testing is only fetched from Google's own addresses over HTTPS");
			}
		}
		return b;
	}

	@SuppressWarnings("unchecked")
	private static String url(List<Object> list, String platform) throws IOException
	{
		for(Object o: list)
		{
			Map<String, Object> m = (Map<String, Object>)o;
			if(platform.equals(m.get("platform")))
			{
				return String.valueOf(m.get("url"));
			}
		}
		throw new IOException("No " + platform + " download in the Chrome for Testing list");
	}

	/** The pair already unpacked and complete for this version under chromeRoot, or null. */
	static Pair existing(Path chromeRoot, String version, String platform)
	{
		Path dir = chromeRoot.resolve(version);
		Path chrome = chromeIn(dir, platform), driver = driverIn(dir, platform);
		return Files.isRegularFile(dir.resolve(COMPLETE)) && Files.isRegularFile(chrome) && Files.isRegularFile(driver) ? new Pair(dir, chrome, driver, version)
				: null;
	}

	static Path chromeIn(Path dir, String platform)
	{
		return dir.resolve("chrome-" + platform).resolve(platform.startsWith("win") ? "chrome.exe" : "chrome");
	}

	static Path driverIn(Path dir, String platform)
	{
		return dir.resolve("chromedriver-" + platform).resolve(platform.startsWith("win") ? "chromedriver.exe" : "chromedriver");
	}

	static Pair fetch(HttpClient http, Path dir, Build b, PrintStream out) throws IOException, InterruptedException
	{
		return fetch(http, dir, b, out, null, () -> false);
	}

	/**
	 * Downloads both zips and unpacks them straight into dir, then writes the completion marker. ChromeFolders picks dir:
	 * one nothing uses, absent or already cleared. On failure or cancel the folder is removed again; when that is not
	 * possible (a scanner still holds a file) it stays without its marker and a later run cleans it.
	 * @param progress told about each block received; may be null
	 * @param cancelled polled while downloading; true stops the download
	 */
	static Pair fetch(HttpClient http, Path dir, Build b, PrintStream out, Progress progress, BooleanSupplier cancelled)
			throws IOException, InterruptedException
	{
		deleteTree(dir);
		Files.createDirectories(dir);
		try
		{
			int n = 0;
			for(String url: List.of(b.chromeUrl(), b.driverUrl()))
			{
				n++;
				String name = n == 1 ? "Chrome" : "chromedriver";
				out.println("  downloading " + url);
				Path zip = dir.resolve(name + ".zip.download");
				HttpResponse<InputStream> r = send(http, URI.create(url), source.downloadAllowed(), Duration.ofMinutes(15), HttpResponse.BodyHandlers.ofInputStream());
				try(InputStream in = r.body())
				{
					if(r.statusCode() != 200)
					{
						throw new IOException(url + " answered " + r.statusCode());
					}
					long total = r.headers().firstValueAsLong("content-length").orElse(-1);
					copy(in, zip, n, name, total, progress, cancelled);
				}
				unzip(zip, dir);
				deleteQuietly(zip);
			}
			if(!Files.isRegularFile(chromeIn(dir, b.platform())) || !Files.isRegularFile(driverIn(dir, b.platform())))
			{
				throw new IOException("The downloads do not hold " + chromeIn(dir, b.platform()).getFileName() + " and "
						+ driverIn(dir, b.platform()).getFileName() + " where expected");
			}
			Files.writeString(dir.resolve(COMPLETE), b.version() + System.lineSeparator(), StandardCharsets.UTF_8);
			return new Pair(dir, chromeIn(dir, b.platform()), driverIn(dir, b.platform()), b.version());
		}
		catch(IOException | InterruptedException | RuntimeException e)
		{
			try
			{
				deleteTree(dir);
			}
			catch(IOException x)
			{
				// left without its marker; the next run removes it
			}
			throw e;
		}
	}

	private static void copy(InputStream in, Path zip, int file, String name, long total, Progress progress, BooleanSupplier cancelled) throws IOException
	{
		byte[] buf = new byte[64 * 1024];
		long done = 0;
		try(OutputStream o = Files.newOutputStream(zip))
		{
			for(int k; (k = in.read(buf)) >= 0;)
			{
				if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
				{
					throw new InterruptedIOException("Download cancelled");
				}
				o.write(buf, 0, k);
				done += k;
				if(progress != null)
				{
					progress.update(file, name, done, total);
				}
			}
		}
	}

	// Extracts a zip, refusing entries that would land outside the target. Java's zip reader does not carry Unix
	// permissions, so on Linux the programs (ELF files and scripts) are made executable again.
	static void unzip(Path zip, Path target) throws IOException
	{
		Path root = target.toAbsolutePath().normalize();
		try(ZipInputStream in = new ZipInputStream(Files.newInputStream(zip)))
		{
			for(ZipEntry e; (e = in.getNextEntry()) != null;)
			{
				Path p = root.resolve(e.getName()).normalize();
				if(!p.startsWith(root))
				{
					throw new IOException("Zip entry outside the target: " + e.getName());
				}
				if(e.isDirectory())
				{
					Files.createDirectories(p);
					continue;
				}
				Files.createDirectories(p.getParent());
				Files.copy(in, p, StandardCopyOption.REPLACE_EXISTING);
				if(!Preflight.WINDOWS && isProgram(p))
				{
					p.toFile().setExecutable(true, false);
				}
			}
		}
	}

	private static boolean isProgram(Path p) throws IOException
	{
		try(InputStream in = Files.newInputStream(p))
		{
			byte[] h = in.readNBytes(4);
			return (h.length == 4 && h[0] == 0x7F && h[1] == 'E' && h[2] == 'L' && h[3] == 'F') || (h.length >= 2 && h[0] == '#' && h[1] == '!');
		}
	}

	/**
	 * Deletes a tree. A file a scanner still holds open is tried again for a few seconds before the delete gives up, since
	 * Windows refuses to delete a file that another program has open.
	 */
	static void deleteTree(Path dir) throws IOException
	{
		if(dir == null || !Files.exists(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS))
		{
			return;
		}
		IOException last = null;
		for(int attempt = 0; attempt < 5; attempt++)
		{
			try(var s = Files.walk(dir))
			{
				for(Path p: (Iterable<Path>)s.sorted(java.util.Comparator.reverseOrder())::iterator)
				{
					Files.deleteIfExists(p);
				}
				return;
			}
			catch(IOException e)
			{
				last = e;
				try
				{
					Thread.sleep(500L * (attempt + 1));
				}
				catch(InterruptedException x)
				{
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		throw last;
	}

	private static void deleteQuietly(Path p)
	{
		try
		{
			Files.deleteIfExists(p);
		}
		catch(IOException e)
		{
			// a leftover download file inside the version folder is harmless
		}
	}
}
