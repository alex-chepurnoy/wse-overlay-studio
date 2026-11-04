# Overlay Studio for Wowza Streaming Engine

<!-- 1x-compat:begin -->
Wowza Overlay Studio 2.0 replaces URL Overlay 1.x and is not compatible with it. Uninstall URL Overlay first
([Before you install](#before-you-install)); overlays, templates and settings made with 1.x are not carried over.
<!-- 1x-compat:end -->

Overlay Studio draws overlays into the transcoded outputs of a Wowza Streaming Engine (WSE) live stream. An overlay
holds up to 64 layers: live web pages (a scoreboard, a lower third), text, dates, clocks and countdowns, shapes and
images. A WSE module draws the layers into the transcoder before the outputs are encoded; headless Chrome renders web
pages in a separate, sandboxed capture service. A page inside WSE Manager builds the overlays and the transcoder
templates that carry them (up to four overlays each), assigns templates to streams, and shows the result live.

## How it fits together

```
 Browser (signed in to WSE Manager)
    |                                              |
    | Manager port 8088                            | streaming port 1935
    | (HTTPS or SSH tunnel)                        | (HLS previews only)
    v                                              |
 +-------------------------------------+           |
 | WSE Manager                         |           |
 |  Overlay Studio page (plugin)       |           |
 |  Overlay Studio bridge              |           |
 +-------------------------------------+           |
    |                                              |
    | server side, signed-in user's credentials    |
    v                                              v
 +-----------------------------------------------------------------+
 | WSE                                                             |
 |  REST on 127.0.0.1:8087                                         |
 |  OverlayStudioServer  REST resources, egress rules              |
 |  OverlayStudioModule  per application, draws in the transcoder  |
 +-----------------------------------------------------------------+
    |
    | Unix domain socket
    v
 +-----------------------------------------------------------------+
 | Capture service WowzaOverlayStudioCapture (unprivileged user)   |
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
| Java | Nothing to install on Windows with the setup program, which carries its own Java 21 runtime. The zip, tarball and `.deb` run the installer and the capture service on WSE's bundled Java, which must be 21 or later for the capture service (WSE 4.12.0 ships 21.0.3; earlier versions not checked). Their scripts look for one (`WMSJAVA_HOME`, then WSE's own, then the `PATH`) and stop with exit 8 when none is 21 or later |
| OS | Windows x64 (Windows 10 or Server 2019 and later, for Unix domain sockets) or Linux x86_64. Chrome for Testing is downloaded for `win64` and `linux64` only; the `.deb` installs on any architecture, but elsewhere Chrome must be given with `--chrome` ([Chrome](#chrome)) |
| Rights | Administrator (Windows) or root (Linux): the install creates a service and its account |
| Network at install | `googlechromelabs.github.io` and `storage.googleapis.com` for Chrome (chrome-headless-shell on Linux, Chrome for Testing on Windows), unless you pass your own Chrome with `--chrome`. A reinstall reuses a complete Chrome already on disk; on Linux, an install from before 1.15.0 downloads the headless shell once |
| Linux fonts | `fontconfig` and one font package (`fonts-dejavu-core` on Debian and Ubuntu). Without them no text can be drawn, on video or in captured pages. The `.deb` depends on both; the zip and tarball installers say how to install them when they are missing |

## Install

Stop WSE first: the installer refuses to change a running WSE. Every file it edits is backed up first as
`<file>.overlaystudio-<time>.bak`, and the run is logged to `logs/overlaystudio-install-<time>.log` in the WSE folder.

### Before you install

<!-- 1x-compat:begin -->
Overlay Studio 2.0 replaces URL Overlay 1.x and is not compatible with it. Every installer refuses to run while 1.x is
on the machine (the zip and tarball with exit code 9, the setup program and the `.deb` with the same message), so
uninstall 1.x first, with WSE stopped, the way it was installed:

- Setup program: Windows Settings, Apps, **Wowza URL Overlay**, Uninstall.
- Zip: 1.x's `uninstall.cmd` (add `--purge` to delete its saved overlays too).
- `.deb`: `sudo /opt/wse-url-overlay/uninstall.sh --purge` to delete its saved overlays, then
  `sudo apt remove wse-url-overlay`.
- Tarball: 1.x's `sudo ./uninstall.sh` (add `--purge` to delete its saved overlays too).

If the refusal names a folder that 1.x's uninstall could not delete (`C:\ProgramData\WowzaURLOverlay`,
`C:\Program Files\Wowza URL Overlay Service`, `/opt/wse-url-overlay` or `/etc/wse-url-overlay`), delete it by hand.
Overlays, templates and settings made with 1.x are not carried over: what 1.x leaves (`conf.modules/urloverlay`,
`content/urloverlay`, templates with `urlOverlay` properties) is ignored, and those templates draw no overlay until they
are rebuilt in Overlay Studio.
<!-- 1x-compat:end -->

**Windows, setup program**
1. Stop the Wowza Streaming Engine service, then run `Wowza-Overlay-Studio-<version>.exe`; it asks for administrator rights.
2. Pick the WSE install and the live applications to add Overlay Studio to.
3. Choose to download Chrome, point at your own, or skip.
4. Review every change, file by file, then Install. It can restart the Manager for you.
5. Start WSE. Screenshots: [docs/BUILD.md](docs/BUILD.md#installing-on-windows).

**Windows, zip**
1. Stop the Wowza Streaming Engine service.
2. Unzip `wse-overlay-studio-<version>-windows.zip` and open a Command Prompt in that folder.
3. `install.cmd --list-apps` to see the applications.
4. `install.cmd --appName live` (Windows asks for administrator rights).
5. Start WSE and restart WSE Manager.

**Linux, .deb (Debian, Ubuntu)**
1. `sudo systemctl stop WowzaStreamingEngine`
2. `sudo apt install ./wse-overlay-studio_<version>_all.deb` (server-level parts only; `apt` cannot ask which applications).
3. `sudo /opt/wse-overlay-studio/install.sh --list-apps`
4. `sudo /opt/wse-overlay-studio/install.sh --appName live`
5. `sudo systemctl restart WowzaStreamingEngine WowzaStreamingEngineManager`

**Linux, tarball (any distribution)**
1. `sudo systemctl stop WowzaStreamingEngine`
2. `tar xzf wse-overlay-studio-<version>-linux.tar.gz && cd wse-overlay-studio-<version>`
3. `sudo ./install.sh --list-apps`
4. `sudo ./install.sh --appName live`
5. `sudo systemctl restart WowzaStreamingEngine WowzaStreamingEngineManager`

Useful options: `--dry-run` prints every change and writes nothing; `--yes` asks nothing; `--home <path>` picks the
WSE install when there are several; `--pages <n|auto>` sets the pages captured at once ([Upgrade](#upgrade)).
`--help` lists all of them. Running the installer again changes only what is missing.

### Upgrade

Install the new version the same way as the first time, with WSE stopped, then start WSE and restart the Manager. The
`.deb` upgrades with `sudo apt install ./wse-overlay-studio_<version>_all.deb` and installs over the old version without
taking it out first. What carries over, and what to know:

- **Pages captured at once.** A new install sizes it from the server's cores and memory. An upgrade keeps the number
  already set, which is 4 for anything installed before 1.15.0. To size it from the server, run the zip's, the
  tarball's or the `.deb`'s installer with `--pages auto` (for example `sudo /opt/wse-overlay-studio/install.sh
  --pages auto`), or raise it in **Settings** in the page. The setup program has no `--pages`; use Settings. A number
  set in Settings is used over the installer's until `--pages` is given, and the installer says which is in force.
- **Linux Chrome.** An install from before 1.15.0 downloads chrome-headless-shell once.
- Saved overlays and templates stay.

### Uninstall

1. Stop WSE (`sudo systemctl stop WowzaStreamingEngine`, or the Wowza Streaming Engine service on Windows). Every way of
   removing Overlay Studio stops with exit 3 while WSE runs.
2. Remove it the way it was installed:
   - Setup program: Windows Settings, Apps, **Wowza Overlay Studio**, Uninstall, or **Uninstall Overlay Studio** in the Start
     menu.
   - Zip: `uninstall.cmd` in the unzipped folder.
   - `.deb`: `sudo apt remove wse-overlay-studio`.
   - Tarball: `sudo ./uninstall.sh` in the unpacked folder; unpack it again if it is gone.
3. Start WSE and restart the Manager.

Saved overlays in `conf.modules/overlaystudio` are kept. `--purge` on `uninstall.cmd` or `uninstall.sh` deletes them as
well; `apt remove` and `apt purge` keep them, so run `sudo /opt/wse-overlay-studio/uninstall.sh --purge` before
`apt remove` to delete them. If a removal stops partway (the setup program or `apt` reports it), finish it with the zip's
`uninstall.cmd` or the tarball's `uninstall.sh` of the same version.

### Chrome

The capture service runs its own Chrome: chrome-headless-shell on Linux, Chrome for Testing on Windows, downloaded during
the install into the service's program folder and updated daily (two versions are kept; `autoUpdate=false` in
`capture.properties` stops the update). Without network access to the two hosts under Requirements, or on a system
with no Chrome for Testing build, pass your own: `--chrome <path>` with `--no-download`. When the installer reports
`Chrome: not found`, pages cannot be captured until one of these is done and the installer is run again.

### Exit codes

| Code | Meaning |
|---|---|
| 0 | Done, or nothing to do |
| 1 | Bad arguments |
| 2 | WSE not found, or more than one found without `--home` |
| 3 | Preflight failed: a folder cannot be written, or WSE is running |
| 4 | WSE older than 4.11.1 |
| 5 | Failed and rolled back; also a failed daily Chrome update |
| 6 | Failed and the rollback is incomplete; the log names what is left |
| 7 | Failed before anything was changed |
| 8 | The zip's or tarball's script found no Java 21 or later, or no installer jar beside it |
| 9 | An earlier, incompatible version is installed ([Before you install](#before-you-install)) |

## Quick start

Open WSE Manager, choose **Overlay Studio** in the top bar, pick an application, and press **Create a new overlay** on
On air. Four steps follow:

1. **Overlay.** Paste a web page's URL; it is captured once and placed on a live stream.
2. **Template.** Pick the renditions that carry the overlay.
3. **Stream.** Pick a publishing stream, or name one that has not started; a dry run says what changes first.
4. **Preview.** Watch each output with the overlay.

The Overlays, Templates and Streams tabs edit each part on its own afterwards: the Overlays tab adds text, clocks,
shapes and images to an overlay, and a template picks up to four overlays.

Full walkthrough, settings and troubleshooting: [docs/USAGE.md](docs/USAGE.md). How it works:
[docs/DESIGN.md](docs/DESIGN.md). Building from source: [docs/BUILD.md](docs/BUILD.md).

## Security model

- **Who can change things.** Every write needs a WSE REST user in the `admin` group of `conf/admin.password`. Other
  signed-in users get a read-only page. With WSE REST authentication off, nothing can be changed.
- **What is exposed.** Nothing new. A remote browser needs only the Manager port (8088, over HTTPS or an SSH tunnel)
  and the streaming port (1935) for previews. WSE REST stays on 127.0.0.1; the installer never edits `RESTInterface`.
  The page never sees the REST password: the bridge forwards its calls server side with the signed-in user's
  credentials, only for Overlay Studio's own REST paths, template deletes and application restarts.
- **Where Chrome runs.** Never inside WSE. The capture service runs Chrome under its own account (Linux system user
  `wse-overlay-studio` under systemd, with an AppArmor profile where Ubuntu restricts user namespaces; Windows virtual
  account `NT SERVICE\WowzaOverlayStudioCapture` under nssm). Chrome's sandbox is always on, with no setting to turn it
  off. The service refuses to run as root or LocalSystem.
- **What pages can reach.** Every request Chrome makes goes through a proxy that blocks private, loopback,
  link-local and cloud metadata addresses, this server's own addresses, and ports other than 80 and 443, unless
  `Server.xml` allows them. Only `http` and `https` URLs without credentials are accepted.

## Known limits

- The Manager bridge is an **unsupported customization of WSE Manager**: Spring controller classes placed in
  `manager/temp`, which the Manager loads as its own. Uninstall Overlay Studio (or delete
  `manager/temp/com/wowza/wms/webapp/overlaystudio/`) before opening a Manager support case.
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
- A stream runs either a Video Intelligence (VIF) template or an Overlay Studio template, never both.
- A template carries at most four overlays, and an overlay at most 64 layers.
- Text draws only in the bundled fonts (Fira Sans, Fira Sans Condensed, Fira Mono, PT Serif); italic is in the Fira Sans
  families only. WSE draws no overlay on NETINT zero-copy frames (from source, untested).
- A page can still signal through the DNS names it looks up, even when the connection is refused.
- Pages at once (`maxCaptures`) are sized from the server's cores and memory on a new install, 2 to 32; an upgrade
  keeps the number set ([Upgrade](#upgrade)). Streams showing the same page share one capture.
- The setup program is not code signed.
- No automated test runs the module in a real WSE or the bridge in a real Manager. The page's tests run it in a
  browser against a fake WSE ([docs/DESIGN.md](docs/DESIGN.md#testing)).

## License

Wowza Public License 1.0 ([LICENSE.txt](LICENSE.txt)). Third-party components: [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
