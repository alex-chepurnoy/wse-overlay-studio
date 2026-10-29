/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.install;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Paints each screen of the setup window, and the removal dialog, to docs/images/setup-*.png with sample data, without a
 * window and without a WSE: the change lists come from the real planner run against a copy of the test fixture, with
 * its temporary path replaced by the sample one. A documentation tool in the test sources; it does not ship.
 * <p>
 * gradlew :installer:setupScreenshots
 */
public final class SetupScreenshots
{
	static final String HOME = "C:\\Program Files\\Wowza Media Systems\\Wowza Streaming Engine 4.12.0";
	static final String CHROME = "156.0.8078.73";

	public static void main(String[] args) throws Exception
	{
		Path outDir = Paths.get(args[0]);
		Files.createDirectories(outDir);
		UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		String[] plans = plans();
		List<String> written = new ArrayList<>();
		SwingUtilities.invokeAndWait(() -> {
			try
			{
				written.addAll(paintAll(outDir, plans[0], plans[1]));
			}
			catch(Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		written.forEach(System.out::println);
	}

	private static List<String> paintAll(Path outDir, String installPlan, String removePlan) throws Exception
	{
		List<String> out = new ArrayList<>();
		Path home = Paths.get(HOME);

		SetupWindow w = new SetupWindow();
		w.showFolder(List.of(new SetupWindow.Found(home, "4.12.0+5")), new SetupWindow.Found(home, "4.12.0+5"), null, false);
		out.add(save(w, outDir, "setup-1-install-folder.png"));

		w = new SetupWindow();
		w.showApps(List.of(new SetupWindow.AppRow("events", null, true, true), new SetupWindow.AppRow("live", null, true, true),
				new SetupWindow.AppRow("vod", "plays recorded video, so it cannot show overlays", false, false)));
		out.add(save(w, outDir, "setup-2-applications.png"));

		w = new SetupWindow();
		w.showChrome(new SetupWindow.ChromeState(null, CHROME, null), SetupWindow.ChromeChoice.DOWNLOAD);
		out.add(save(w, outDir, "setup-3-chrome.png"));

		w = new SetupWindow();
		List<String> summary = new ArrayList<>(List.of("Add URL Overlay to Wowza Streaming Engine 4.12.0 in " + HOME));
		summary.addAll(SetupWindow.Controller.summaryRest(List.of("events", "live"), CHROME, SetupWindow.ChromeChoice.DOWNLOAD, false));
		summary.add(SetupWindow.Controller.bridgeLine(true));
		summary.add(SetupWindow.Controller.captureLine());
		w.showReview(summary, installPlan, null, true);
		out.add(save(w, outDir, "setup-4-review.png"));

		w = new SetupWindow();
		w.showProgress("Downloading Chrome (1 of 2): 84 of 208 MB", 404, "Downloading Chrome for Testing " + CHROME + " and its chromedriver (about 220 MB)...\n"
				+ SetupWindow.Controller.shorten("  downloading https://storage.googleapis.com/chrome-for-testing-public/" + CHROME + "/win64/chrome-win64.zip\n", home),
				true);
		out.add(save(w, outDir, "setup-5-progress.png"));

		w = new SetupWindow();
		List<String> done = new ArrayList<>(SetupWindow.Controller.doneSteps("yes", true, false));
		done.add("The capture service, which draws the web pages, is running.");
		w.showDone(true, "URL Overlay is installed", "A few more steps and it is ready to use.", done,
				HOME + "\\logs\\urloverlay-install-20261028-101500.log");
		out.add(save(w, outDir, "setup-6-done.png"));

		w = new SetupWindow();
		w.showRemove(HOME, removePlan, null);
		out.add(save(w, outDir, "setup-remove.png"));
		return out;
	}

	private static String save(SetupWindow w, Path dir, String name) throws Exception
	{
		JComponent c = w.paintable();
		BufferedImage img = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		Object hints = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
		if(hints instanceof Map<?, ?> m)
		{
			g.addRenderingHints(m);
		}
		else
		{
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		}
		c.printAll(g);
		g.dispose();
		Path f = dir.resolve(name);
		ImageIO.write(img, "png", f.toFile());
		return f.toString();
	}

	/** The real install and removal plans for live and events, on a copy of the test fixture, shown at the sample path. */
	private static String[] plans() throws Exception
	{
		Path tmp = Files.createTempDirectory("uo-shots");
		try
		{
			Path home = tmp.resolve("wse");
			InstallerTest.copy(Paths.get(System.getProperty("urloverlay.fixtures"), "wse412"), home);
			InstallerTest.fakeServerJar(home, "4.12.0+5");
			InstallerTest.fakeManager(home, "6.2.19", "10.1.59");
			Path events = home.resolve("conf/events");
			Files.createDirectories(events);
			// a second live application, without VIF, so the sample shows nothing but URL Overlay
			String live = Files.readString(home.resolve("conf/live/Application.xml"), StandardCharsets.UTF_8);
			Files.writeString(events.resolve("Application.xml"), live, StandardCharsets.UTF_8);
			PrintStream quiet = new PrintStream(OutputStream.nullOutputStream());
			Payload payload = new Payload();
			List<Wse.App> apps = Wse.apps(home).stream().filter(a -> a.name().equals("live") || a.name().equals("events")).toList();
			Map<String, String> props = new java.util.LinkedHashMap<>(InstallSession.DEFAULTS);
			props.put("UrlOverlayCaptureSocket", "C:\\ProgramData\\WowzaURLOverlay\\capture.sock");
			Installer inst = new Installer(home, payload, quiet);
			Path plugin = Wse.pluginDir(home, null).getKey();
			List<Installer.Op> ops = new ArrayList<>(inst.planInstall(apps, props, plugin));
			ops.addAll(inst.planBridge(ManagerBridge.temp(home.resolve("manager")), true));
			String install = SetupWindow.Controller.describe(ops, home, List.of());
			inst.apply(ops, true, null);
			String remove = SetupWindow.Controller.describe(inst.planUninstall(false, false), home, List.of(Paths.get("C:/Program Files/Wowza URL Overlay/chrome-for-testing")));
			// the Chrome paths written into Server.xml are absolute: shown at the sample folder
			return new String[] { install.replace(home.toString(), HOME), remove.replace(home.toString(), HOME) };
		}
		finally
		{
			try(Stream<Path> s = Files.walk(tmp))
			{
				s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
			}
		}
	}

	private SetupScreenshots()
	{
	}
}
