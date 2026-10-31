# URL Overlay for Wowza Streaming Engine

URL Overlay burns a live web page, such as a scoreboard, a clock or a lower third, into the transcoded outputs of a
Wowza Streaming Engine (WSE) live stream. Headless Chrome renders the page in a separate, sandboxed capture service,
and a WSE module draws each frame into the transcoder before the outputs are encoded. A page inside WSE Manager builds
the overlays and the transcoder templates that carry them, assigns templates to streams, and shows the result live.

## How it fits together

```
 Browser (signed in to WSE Manager)
    |                                              |
    | Manager port 8088                            | streaming port 1935
    | (HTTPS or SSH tunnel)                        | (HLS previews only)
    v                                              |
 +-------------------------------------+           |
 | WSE Manager                         |           |
 |  URL Overlay page (plugin)          |           |
 |  URL Overlay bridge (manager/temp)  |           |
 +-------------------------------------+           |
    |                                              |
    | server side, signed-in user's credentials    |
    v                                              v
 +-----------------------------------------------------------------+
 | WSE                                                             |
 |  REST on 127.0.0.1:8087                                         |
 |  UrlOverlayServer   REST resources, egress rules                |
 |  UrlOverlayModule   per application, draws into the transcoder  |
 +-----------------------------------------------------------------+
    |
    | Unix domain socket
    v
 +-----------------------------------------------------------------+
 | Capture service WowzaURLOverlayCapture (own unprivileged user)  |
 |  Chrome (headless shell on Linux), sandbox always on            |
 |  egress proxy: every connection Chrome makes is checked         |
 +-----------------------------------------------------------------+
    |
    v
 The web page (scoreboard, clock, lower third)
```

Source: `ui/` (the page), `bridge/`, `module/`, `capture/`, and `installer/` with `packaging/` for the installers
([docs/BUILD.md](docs/BUILD.md#project-layout)).

## Requirements

| | |
|---|---|
| WSE | 4.11.1 or later (the installer refuses older versions), with the transcoder licensed |
| WSE Manager | One the bridge was built for: Spring 6 in `WMSManager.war`, Tomcat 10.1, and `--tempDirectory=<manager>/temp` in its start script. The installer checks all three. Checked on 4.12.0 and 4.12.1; on a Manager that fails the check the bridge is not installed and the page cannot reach WSE. In practice this means Manager 4.12 or later (4.11 Managers not checked) |
| Java | Nothing to install on Windows with the setup program, which carries its own Java 21 runtime. The zip, tarball and `.deb` run the installer and the capture service on WSE's bundled Java, which must be 21 or later for the capture service (WSE 4.12.0 ships 21.0.3; earlier versions not checked) |
| OS | Windows x64 (Windows 10 or Server 2019 and later, for Unix domain sockets) or Linux x86_64. Chrome for Testing is downloaded for `win64` and `linux64` only |
| Rights | Administrator (Windows) or root (Linux): the install creates a service and its account |
| Network at install | `googlechromelabs.github.io` and `storage.googleapis.com` for Chrome (chrome-headless-shell on Linux, Chrome for Testing on Windows), unless you pass your own Chrome with `--chrome`. A reinstall reuses a complete Chrome already on disk; on Linux, an install from before 1.15.0 downloads the headless shell once |
| Linux fonts | `fontconfig` and a font package; the `.deb` recommends them with Chrome's libraries |

## Install

Stop WSE first: the installer refuses to change a running WSE. Every file it edits is backed up first as
`<file>.urloverlay-<time>.bak`, and the run is logged to `logs/urloverlay-install-<time>.log` in the WSE folder.

**Windows, setup program**
1. Stop the Wowza Streaming Engine service, then run `Wowza-URL-Overlay-<version>.exe`; it asks for administrator rights.
2. Pick the WSE install and the live applications to add URL Overlay to.
3. Choose to download Chrome, point at your own, or skip.
4. Review every change, file by file, then Install. It can restart the Manager for you.
5. Start WSE. Screenshots: [docs/BUILD.md](docs/BUILD.md#installing-on-windows).

**Windows, zip**
1. Stop the Wowza Streaming Engine service.
2. Unzip `wse-url-overlay-<version>-windows.zip` and open a Command Prompt in that folder.
3. `install.cmd --list-apps` to see the applications.
4. `install.cmd --appName live` (Windows asks for administrator rights).
5. Start WSE and restart WSE Manager.

**Linux, .deb (Debian, Ubuntu)**
1. `sudo systemctl stop WowzaStreamingEngine`
2. `sudo apt install ./wse-url-overlay_<version>_all.deb` (server-level parts only; `apt` cannot ask which applications).
3. `sudo /opt/wse-url-overlay/install.sh --list-apps`
4. `sudo /opt/wse-url-overlay/install.sh --appName live`
5. `sudo systemctl restart WowzaStreamingEngine WowzaStreamingEngineManager`

**Linux, tarball (any distribution)**
1. `sudo systemctl stop WowzaStreamingEngine`
2. `tar xzf wse-url-overlay-<version>-linux.tar.gz && cd wse-url-overlay-<version>`
3. `sudo ./install.sh --list-apps`
4. `sudo ./install.sh --appName live`
5. `sudo systemctl restart WowzaStreamingEngine WowzaStreamingEngineManager`

Useful options: `--dry-run` prints every change and writes nothing; `--yes` asks nothing; `--home <path>` picks the
WSE install when there are several. `--help` lists all of them. Running the installer again changes only what is
missing. Uninstall: Windows Settings, Apps (setup program), `uninstall.cmd` (zip), `sudo apt remove wse-url-overlay`
(`.deb`) or `sudo ./uninstall.sh` (tarball).

## Quick start

Open WSE Manager, choose **URL Overlay** in the top bar, pick an application, and press **Create a new overlay** on
On air. Four steps follow:

1. **Overlay.** Paste a page URL; it is captured once and placed on a live stream.
2. **Template.** Pick the renditions that carry the overlay.
3. **Stream.** Pick a publishing stream, or name one that has not started; a dry run says what changes first.
4. **Preview.** Watch each output with the overlay.

The Overlays, Templates and Streams tabs edit each part on its own afterwards.

Full walkthrough, settings and troubleshooting: [docs/USAGE.md](docs/USAGE.md). How it works:
[docs/DESIGN.md](docs/DESIGN.md). Building from source: [docs/BUILD.md](docs/BUILD.md).

## Security model

- **Who can change things.** Every write needs a WSE REST user in the `admin` group of `conf/admin.password`. Other
  signed-in users get a read-only page. With WSE REST authentication off, nothing can be changed.
- **What is exposed.** Nothing new. A remote browser needs only the Manager port (8088, over HTTPS or an SSH tunnel)
  and the streaming port (1935) for previews. WSE REST stays on 127.0.0.1; the installer never edits `RESTInterface`.
  The page never sees the REST password: the bridge forwards its calls server side with the signed-in user's
  credentials, only for URL Overlay's own REST paths, template deletes and application restarts.
- **Where Chrome runs.** Never inside WSE. The capture service runs Chrome under its own account (Linux system user
  `wse-url-overlay` under systemd, with an AppArmor profile where Ubuntu restricts user namespaces; Windows virtual
  account `NT SERVICE\WowzaURLOverlayCapture` under nssm). Chrome's sandbox is always on, with no setting to turn it
  off. The service refuses to run as root or LocalSystem.
- **What pages can reach.** Every request Chrome makes goes through a proxy that blocks private, loopback,
  link-local and cloud metadata addresses, this server's own addresses, and ports other than 80 and 443, unless
  `Server.xml` allows them. Only `http` and `https` URLs without credentials are accepted.

## Known limits

- The Manager bridge is an **unsupported customization of WSE Manager**: Spring controller classes placed in
  `manager/temp`, which the Manager loads as its own. Uninstall URL Overlay (or delete
  `manager/temp/com/wowza/wms/webapp/urloverlay/`) before opening a Manager support case.
- The bridge does not stop WSE Manager itself from writing the REST password into every plugin page's JavaScript;
  only a Manager change can.
- A WSE installed fresh, or a Manager whose start script or Spring version changes, needs the installer run again.
- **Chrome for Testing has no published checksums.** Downloads are trusted only because they come over HTTPS from
  `googlechromelabs.github.io` and `storage.googleapis.com`, every redirect included.
- Chrome is updated by a daily check (current and previous version kept, rollback on failure). Turn it off with
  `autoUpdate=false` in `capture.properties`.
- **No Windows Job Object limits yet.** Linux caps the service with systemd (`MemoryMax`, `CPUQuota`, `TasksMax`);
  Windows has only the service's own per-browser memory restart.
- New outputs, sizes or bitrates apply when a stream restarts. **Restart now** handles pulled streams and RTMP or RTP
  publishers; any other pushed stream must be restarted at its encoder.
- A stream runs either a Video Intelligence (VIF) template or a URL Overlay template, never both.
- A page can still signal through the DNS names it looks up, even when the connection is refused.
- Pages at once (`maxCaptures`) are sized from the server's cores and memory at install, 2 to 32; `--pages` sets
  another number. Streams showing the same page share one capture.
- The setup program is not code signed.
- No automated test runs the module in a real WSE, the bridge in a real Manager, or the page in a browser
  ([docs/DESIGN.md](docs/DESIGN.md#testing)).

## License

Wowza Public License 1.0 ([LICENSE.txt](LICENSE.txt)). Third-party components: [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
