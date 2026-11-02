# Overlay Studio design

How Overlay Studio is built: its parts, how a frame travels, the REST surface, the Manager bridge, the capture service,
the network rules for captured pages, how templates are written, and what the installer and updater do.

## Components

| Component | Runs in | Does |
|---|---|---|
| `OverlayStudioServer` | WSE, server listener | Registers the REST resources per `OverlayStudioServer`; reads the egress rules and the capture socket from `Server.xml` |
| `OverlayStudioModule` | WSE, application module | Transcoder listeners, per-stream state, draws overlay frames into each transcoder session, records outputs and status |
| `CaptureClient`, `WebPageCapture` (module side) | WSE | A handle on one capture in the service: fetches frames, keeps the last good one, reopens after a service restart |
| Capture service | Own JVM (Java 21), own account | Chrome over DevTools (chrome-headless-shell on Linux), shared by up to `pagesPerBrowser` pages, sandbox always on, launch limits, memory cap, profiles, the egress proxy; frames by screencast or screenshots over a Unix socket |
| `EgressGuard` | Capture service, one per capture | Local HTTP and CONNECT proxy Chrome must use; allows or blocks each connection by the resolved address |
| `OverlayStudioBridge` | WSE Manager | Forwards the page's calls to WSE REST server side, with the signed-in user's credentials |
| Plugin page | Browser, inside WSE Manager | All UI; plays HLS previews with its own copy of hls.js |
| Installer | Standalone JVM, JDK only | Install, uninstall, verify, Chrome update; setup window and command line |

Code shared between jars lives in `shared/`: the capture protocol (`Wire`), egress rules (`AddressRules`,
`EgressRules`), the browser pool (`CapturePool`) and the byte-preserving config edits (`ConfigEdits`) that both the
installer and the page's "add to application" use.

## One frame

1. The capture service captures each page at the interval the overlay asks for (`refreshRate`, default 0.5 s), by
   screencast or by screenshots, whichever costs less for that page (**Browsers**, below), numbers each frame and keeps
   Chrome's PNG as it came.
2. In WSE, one fetcher thread per capture asks for a frame newer than the last it has (waiting up to a second), decodes
   it, and publishes it.
3. A draw takes the latest decoded frame without waiting. Each page is keyed (pixels within `keyTolerance` of
   `keyColor` become transparent), cropped and scaled once per frame and size, then composed in z-order with its
   opacity onto the overlay canvas, off the transcoder's thread.
4. On the next decoded video frame the module hands the canvas to WSE: on the source before scaling (every output),
   or on each chosen output scaled to that output's real frame size.
5. WSE composites natively, scales and encodes. Dynamic overlays turn off GPU zero-copy.
6. The outputs are packetized as usual; the page plays the source and an output over HLS from the streaming port.

If the service is down, streams keep their last good frame and the module retries after 1 s, doubling to 30 s.

## REST surface

Base `/v1/{server}/plugin/overlaystudio`, registered as authorized REST resources by default. Every response uses WSE's
JSON envelope (`success`, `code`, `message`, `data`). `400` validation (including a blocked host, a URL with
credentials or a non-http scheme), `404` unknown application, stream or object, `409` conflicts (an unmanaged file in
the way, a template in use), `422` template not usable.

| Path (under base) | Verbs | Purpose |
|---|---|---|
| `/info` | GET | Versions, OS, CPU count, capture service and Chrome state, egress rules and recent blocks, Chrome update state, write access |
| `/capture/limit` | GET, POST | Pages captured at once: what the service runs at, its ceiling, and a new number with no restart (**Settings** in the page); kept in `conf.modules/overlaystudio/capture-limit.json` and used over `maxCaptures` |
| `/vhosts/{vh}/applications` | GET | Applications with the module, read from disk |
| `/vhosts/{vh}/applications/{app}/state` | GET | Everything one application's page shows, with a revision; `?rev=` answers `unchanged` |
| `/vhosts/{vh}/applications/{app}/streams` | GET | Streams, the template each loads, restart needed |
| `/vhosts/{vh}/applications/{app}/module` | GET, POST | Whether the module is in `Application.xml`; add it, optionally restarting the application |
| `/vhosts/{vh}/applications/{app}/overlays` | GET | Named overlays |
| `.../overlays/{name}` | GET, POST, DELETE | One named overlay |
| `.../overlays/{name}/preview` | POST | Capture an unsaved page once and return keyed and raw PNGs; writes nothing |
| `.../overlays/{name}/objects/{id}/capture` | GET, POST | Last capture of a named overlay's page; POST `{"reload": true}` reloads the page |
| `/vhosts/{vh}/applications/{app}/templates/assign` | GET, POST | Dry run (GET) or apply (POST) a template to a stream or the application |
| `/vhosts/{vh}/applications/{app}/templates/order` | GET, POST | The application's `Transcoder/Templates` list |
| `.../instances/{inst}/streams/{stream}/overlay` | GET, POST, PUT, DELETE | Per-stream overlay (older setups; the page uses templates) |
| `.../streams/{stream}/overlay/objects` | GET, POST | Its pages |
| `.../streams/{stream}/overlay/objects/{id}` | GET, POST, PUT, DELETE | One page |
| `.../streams/{stream}/overlay/objects/{id}/capture` | GET | Last keyed capture as base64 PNG; `?raw=true` unkeyed |
| `.../streams/{stream}/status` | GET | Transcoder state and outputs, draw timing, each capture's state |
| `.../streams/{stream}/preview` | GET | HLS URLs for the source and each output |
| `.../streams/{stream}/outputs/{encode}/snapshot` | GET | A JPEG of an output's last key frame, decoded by WSE's thumbnailer, with what it cost; `?since=` answers unchanged. Experimental: the page does not depend on it |
| `.../streams/{stream}/restart` | POST | Reset a pulled stream or disconnect an RTMP or RTP publisher |
| `/vhosts/{vh}/templates` | POST | Write a new template from the builder's spec |
| `/vhosts/{vh}/templates/{t}/spec` | POST | Rewrite a template the module wrote |
| `/vhosts/{vh}/templates/{t}/use` | POST | Give an existing template an overlay, as a copy or in place |
| `/vhosts/{vh}/templates/{t}/overlay` | POST | Change which overlay a template carries and on which outputs |
| `/vhosts/{vh}/templates/{t}/copies` | GET, POST | Per-stream copies of a template; update them |
| `/vhosts/{vh}/templates/{t}/validate` | GET | Whether a template can carry an overlay, and why not |
| `/vhosts/{vh}/template-starters`, `.../{starter}` | GET, POST | The three starter templates; write one |
| `/vhosts/{vh}/images` | GET, POST | Image files for image overlays; upload or delete an upload |

Outside the module the page calls one WSE resource: `DELETE /v2/servers/_defaultServer_/vhosts/_defaultVHost_/transcoder/templates/{name}`.

**Write access.** Every POST, PUT and DELETE needs a user WSE REST authenticated who is in the `admin` group of
`conf/admin.password`. Reads need any authenticated user. With REST authentication off (`AuthenticationMethod none`,
or `OverlayStudioServer=unauthorized`) there is no user, so every change is refused and reads stay open. The draft
preview counts as a change, since it opens a browser on any URL. `/info` reports `write.allowed` and the reason, and the
page goes read only.

**Names and paths.** Template and stream names used as file names match `[A-Za-z0-9][A-Za-z0-9._-]*`, at most 100
characters, without `..`. Every path variable is one segment after decoding; every write is checked to land in its
directory (`transcoder/templates/` or `conf.modules/overlaystudio/`). Template XML with a `DOCTYPE` is refused.

## The Manager bridge

WSE REST stays on 127.0.0.1 behind its `IPWhiteList`, and the page runs on the Manager's origin. The bridge is a
Spring controller the installer places in the Manager:

```
browser --(Manager session cookie + security token)--> 8088 /enginemanager/overlaystudio-api/**
bridge in the Manager --(signed-in user's credentials, server side)--> WSE REST on the session's server URL
browser --(HLS)--> 1935, unchanged
```

**How it loads.** The Manager's start scripts pass `--tempDirectory=<manager>/temp`, which the Manager's launcher
mounts as the web application's `WEB-INF/classes`, and Spring scans `com.wowza.wms.webapp` for controllers. So class
files under `manager/temp/com/wowza/wms/webapp/overlaystudio/` load at Manager start without changing any Manager file.
The installer places them only when `WMSManager.war` carries `spring-webmvc-6.*`, the Manager's Tomcat is 10.1, and a
start script passes the temp directory; otherwise it skips the bridge and says why. The bridge uses only Spring and
Jakarta types, no Wowza classes.

**Routes.**

| Route | Does |
|---|---|
| `GET /overlaystudio-api/status` | `{"bridge":"overlaystudio","version":...,"signedIn":...}`; the page compares the version with its own |
| `GET /overlaystudio-api/token` | The session's security token, which the Manager requires on every POST |
| `GET, POST /overlaystudio-api/rest/**` | Forwards to WSE REST. GET as GET; PUT and DELETE travel as POST with `?method=` |

**Rules.** Only module paths (`/v1/server/plugin/overlaystudio/...`) and template DELETE are forwarded; anything else is
`403`. Paths with dot segments, doubled or back slashes, or encoded dots, slashes, backslashes, `%` or NUL are refused.
The target is the session's server URL, never one from the request. Bodies are capped at 2 MB; JSON, text and image
types only. The Manager's own interceptor requires the token and the admin group on every POST, and the module checks
the admin group again. Credentials are never logged or returned, and `WWW-Authenticate` is not passed on. Connect
timeout 5 s, read timeout 60 s.

**Ports.** Operators need the Manager port (8088, preferably HTTPS) and a streaming port. HLS previews go straight to
the streaming port; WSE's HTTP streamer sends `Access-Control-Allow-Origin: *` by default.

**Limits.** It runs in the Manager's JVM in Wowza's package namespace and relies on two undocumented behaviors (the
temp mount and the scan package), so it is an unsupported Manager customization: uninstall it before a Manager support
case. The WSE update tool leaves `manager/temp` alone; a WSE installed fresh needs Overlay Studio installed again. It
does not stop the Manager from writing the REST password into plugin pages.

## The capture service

| | Linux | Windows |
|---|---|---|
| Service | systemd `WowzaOverlayStudioCapture.service` | `WowzaOverlayStudioCapture`, run by a copy of WSE's `nssm` |
| Account | system user and group `wse-overlay-studio`, no login shell | virtual account `NT SERVICE\WowzaOverlayStudioCapture` |
| Java | WSE's `java/bin/java` | A Java runtime copied next to the program |
| Program | `/opt/wse-overlay-studio/capture/`, root's, read only to the service | `C:\Program Files\Wowza Overlay Studio Service\capture\` |
| Chrome | `/opt/wse-overlay-studio/chrome-for-testing/<version>/` | `C:\Program Files\Wowza Overlay Studio Service\chrome-for-testing\<version>\` |
| Settings | `/etc/wse-overlay-studio/capture.properties` (root:wse-overlay-studio 0640) | `C:\ProgramData\WowzaOverlayStudio\capture.properties` |
| Work and logs | `/var/lib/wse-overlay-studio/`, `/var/log/wse-overlay-studio/` | `C:\ProgramData\WowzaOverlayStudio\work\` (service account only), `logs\` |
| Socket | `/run/wse-overlay-studio/capture.sock` | `C:\ProgramData\WowzaOverlayStudio\capture.sock` |
| Limits | `MemoryMax` (`maxBrowserMemoryMB` for each page the server is sized for, at least `maxCaptures`, within three quarters of its memory, plus 768 MB), `CPUQuota` (the larger of 200% and 16% of a core for each such page, in whole cores), `TasksMax=1024` | Per-page memory restart in the service only |
| Hardening | `ProtectSystem=strict`, `ProtectHome`, `PrivateTmp`, `NoNewPrivileges`, `UMask=0007` | ACLs: data folder for SYSTEM, Administrators and the service; `work\` for the service only |
| Supervision | `Restart=on-failure` | nssm restarts on exit |

**Sandbox.** Always on; there is no setting to turn it off, and the service refuses to run as root or LocalSystem. A
Chrome that cannot start sandboxed fails its captures with the reason. The systemd unit leaves out the settings that
would break Chrome's sandbox (`RestrictNamespaces`, `PrivateUsers`, `MemoryDenyWriteExecute`, `SystemCallFilter`).
Where AppArmor 4 restricts unprivileged user namespaces (Ubuntu 23.10 and later), the installer adds
`/etc/apparmor.d/wse-overlay-studio-chrome`, granting `userns` to `chrome-for-testing/*/chrome-headless-shell-linux64/chrome-headless-shell`
and, for installs from before the headless shell, `chrome-for-testing/*/chrome-linux64/chrome`, and nothing else.

**Access.** WSE's user reaches the socket by file permissions alone. On Linux the socket's folder is group
`wse-overlay-studio`, mode 0770; WSE running as root connects already, and a WSE running as another user (the owner of
`conf/Server.xml`) is added to the group by the installer.

**Browsers.** Captures are pooled by page (URL, browser size, key color, background): every layer showing the same
page, on any stream, uses one browser, kept open 15 s after its last user so an edit reuses it. Layers sharing a page
reload it at the shortest `reloadRate` any of them asks for. At most `maxCaptures` pages: 4 when nothing sets it, sized
from the server by a new install (2 to 32), and changed from **Settings** while the service runs. One Chrome holds up to
`pagesPerBrowser` pages (4) with the same network rules, each in a browser context of its own with its own egress proxy
and storage, as a window of its own. A Chrome over `maxBrowserMemoryMB` (1024) per page it holds restarts the page with
the largest JavaScript heap and takes no new pages until it has emptied and closed. Each DevTools call times out after
15 s (a message not sent within 10 s ends the connection) and a page load after 30 s; a page that does not answer is
opened again. A crashed page or Chrome reopens with backoff up to 60 s. Pages get no JavaScript dialogs: `alert`,
`confirm` and `prompt` return what a dismissed one does, because a dialog the old document opens as a reload starts
cannot be answered and would block the page. Each page is captured by screencast when it repaints at most 1.5 times per
capture interval, and by screenshots when faster, probed again every 5 minutes. Captures nobody asked a frame of for a
minute are closed, so a WSE that stopped does not keep browsers open. Each Chrome gets a fresh profile, deleted on
close.

**Chrome switches.** Headless, a fixed window size with the viewport pinned through DevTools, scrollbars hidden,
`--disable-gpu`, background networking, sync, extensions, component update and similar background traffic off,
background timer throttling off, `--proxy-server` pointing at the capture's `EgressGuard` with loopback not bypassed,
and WebRTC limited to proxied traffic. `--disable-dev-shm-usage` on Linux.

**Protocol.** Each message is a 4-byte magic, the header length and body length as big-endian ints, a UTF-8 JSON
header, and a binary body (the PNG, never base64). Every connection starts with `hello`, carrying the protocol version
(currently 2) both ways; a different version is refused with a message to install matching versions. Then `open`
(page, viewport, key color, label, delay, reload rate, frame interval and WSE's egress rules), `update`, `frame`
(names the last frame number held; waits up to a second for a newer one, or answers "unchanged" with no body),
`status` and `close`. The module keeps up to 40 connections, one request at a time on each, so a slow page holds up
only its own connection; the service serves each connection on its own virtual thread. A restarted service answers
"Unknown capture" and the module opens the capture again.

## Egress rules

Every connection Chrome makes goes through its capture's `EgressGuard`, a proxy on 127.0.0.1 that accepts only
loopback clients and handles plain HTTP and `CONNECT` (HTTPS, WebSockets). It resolves the host itself, checks every
address, and connects to the checked address, so a DNS answer cannot change between check and connect.

Rules, in order:

1. Port other than 80 or 443, unless listed in `OverlayStudioAllowPorts`: block (checked before any lookup).
2. A host that does not resolve: block.
3. `OverlayStudioBlockHosts` match, by name or by address: block.
4. A Chrome service host (`clients2.google.com`, `accounts.google.com`, `www.google.com`, `update.googleapis.com`,
   `edgedl.me.gvt1.com`, `optimizationguide-pa.googleapis.com`) with `OverlayStudioBlockChromeServices` on: block unless
   the host is in `OverlayStudioAllowHosts`. Counted in `/info`, not logged one by one.
5. Cloud metadata (`169.254.169.254`, `fd00:ec2::254`): block unless an address or range entry allows it (a hostname
   entry or `OverlayStudioAllowPrivateNetworks` never does).
6. Loopback, link-local, unspecified, and this server's own interface addresses (read again every minute): block
   unless an exact address entry allows it.
7. `OverlayStudioAllowHosts` match, by name or by address: allow.
8. Private, CGNAT (`100.64.0.0/10`), reserved, unique-local (`fc00::/7`), site-local and multicast: block unless
   `OverlayStudioAllowPrivateNetworks`.
9. Otherwise allow.

Every address a name resolves to is checked; one blocked address blocks the host.

A blocked request gets `403`; the capture's status records the address and rule, and the error log names the host,
address, rule and page. The REST write path runs the same check on the typed URL to fail fast. Only `http` and
`https` URLs without `user:password` are accepted. URLs are logged without their query string. The lookup itself
still reaches the resolver, so a page can signal through the names it asks for.

## Transcoder templates

Templates are ordinary WSE files in the virtual host's `transcoder/templates/`. A template carries an overlay
through two template properties, `overlayStudio1` (the overlay's name) and `overlayStudioEncodes1` (the outputs that show it;
empty means every output), and needs `Decode/Video/EnableDynamicOverlays` true. Encode names in `overlayStudioEncodes1` are
matched as whole-string regular expressions against the encode name and its output stream name. Templates the module
writes carry `Managed by Overlay Studio` in their description; a file without it is never overwritten.

**Builder.** `TemplateBuilder` writes a template from the page's spec: outputs (video, passthrough or audio), size and
fit mode (`fit-height`, `fit-width`, `letterbox`, `crop`, `stretch`, or the source's size), source region
(`FrameSize/SourceRectangle`), frame-rate divisor (`SkipFrameCount`), codec and profile, bitrate, and for H.264 rate
control and speed (`mainconcept.bit_rate_mode`, `mainconcept.max_bit_rate`, `mainconcept.performance_level`),
keyframes, HDR to SDR (`Convert8bit`), audio codec with channels and sample rate (`Audio/Resample`), captions
(`copyCEA608CaptionsFromSourceStream`), expert parameters by name, minimum decode frame rate, up to eight image
overlays (`Decode/Video/Overlays` for every output, `Encode/Video/Overlays` per output, indexes 1 to 8), and adaptive
groups (`StreamNameGroups`, with `WowzaAudioOnly` or `WowzaVideoOnly` renditions). Hardware sets decoder, scaler and
encoder names together: CPU (`default`), NVIDIA (`NVCUVID_EVA`, `CUDA_EVA`, `NVENC_EVA`), Intel (`IQSV`) or NETINT.
Default sizes and bitrates come from WSE's own `transcode.xml`; 1080p and 540p are Overlay Studio's choice. The URL
overlay draws in slot 16 (`OverlayStudioIndex`), clear of image overlays and of the slot VIF uses.

**Validator.** A template is usable when it parses, has `EnableDynamicOverlays` true, and has at least one enabled
output whose video codec is not `PassThru` or `Disable`. Warnings: an enabled `PassThru` output, an implementation name
not found in WSE. A VIF template (one with a `videoIntelligence` encode) is marked unusable: a stream runs either VIF or
Overlay Studio, and at run time the module draws nothing on a VIF session and logs one warning.

**Existing templates.** `use` with `copy` writes a new file with dynamic overlays on and the overlay properties set;
the original is untouched. `inplace` changes the file itself, only for a customer's own template (never the five WSE
ships, never VIF's); it is backed up as `<template>.xml.overlaystudio-<time>.bak` and recorded in the install manifest.

**Assigning.** WSE loads, for each stream, the first template in the application's `Transcoder/Templates` list that
exists. Stream scope writes `transcoder/templates/<stream>.xml`, a managed copy, and puts `${SourceStreamName}.xml`
first in the list if absent; the existing entry stays as everyone else's fallback. Application scope replaces the
fallback, after the dry run shows what changes. `Application.xml` is written through WSE's own `TranscoderAppConfig`,
the class behind the Manager's Transcoder page. If `LiveStreamTranscoder` is empty it becomes `transcoder`; another
value without `transcoder` is refused. The order endpoint takes at most 12 entries and backs up `Application.xml`.

**Live changes.** Every template write makes running streams that loaded that file pick up the overlay it now names
and its output list at once, through the dynamic overlays the module registered on every output at start. Output
changes (other outputs, sizes, bitrates, or another file chosen by the list) are detected by a fingerprint of the
loaded file and reported as `restartNeeded`, because WSE builds a stream's transcoder only when the stream starts.

## Installer

`java -jar wse-plugin-overlay-studio-installer-<version>.jar Install | Uninstall | Verify | UpdateChrome [options]`. The
setup program, `install.cmd` and `install.sh` all run this jar.

**Install steps.**

1. Find WSE (`--home`, `-Dcom.wowza.wms.ConfigHome`, `WMSAPP_HOME`, then the single install found) and read its version
   from `lib/wms-server.jar`; refuse below 4.11.1.
2. Preflight: no 1.x on the machine (exit 9, below), write access to every folder the plan touches, WSE not running,
   on Windows no jar in use.
   <!-- 1x-compat:begin -->
   The 1.x check (`LegacyInstallCheck`) looks for `lib/wse-plugin-url-overlay-*.jar`, the `UrlOverlayServer` listener
   in `Server.xml`, a `UrlOverlayModule` entry in any `Application.xml`, 1.x's Manager plugin folder in either place and
   its bridge under `manager/temp`, the `WowzaURLOverlayCapture` service and its folders, and on Linux the
   `wse-url-overlay` package in dpkg's installed state. 1.x's install manifest is not a signal: its uninstall can leave
   it behind. The `.deb` checks the same before unpacking (`Conflicts: wse-url-overlay`, then `preinst`, in every WSE
   home it can find), and the setup program in a launch condition (1.x's upgrade code, the service key, its
   ProgramData and program folders). Only installing runs it, so 2.0 can always be removed.
   <!-- 1x-compat:end -->
3. Discover applications from disk (every virtual host's `conf/<app>/Application.xml`). Live applications are
   selectable; VOD applications are not; applications that have the module are not offered again. An unattended run
   (`--yes`) changes no application unless `--appName` or `--all-live-apps` names them.
4. Download Chrome (below), unless a path is given or `--no-download`.
5. Set up the capture service: program, settings, account, unit or Windows service, AppArmor profile.
6. Back up each file before its first edit (`<file>.overlaystudio-<yyyyMMdd-HHmmss>.bak`), recording its SHA-256.
7. `Server.xml`: the server listener and `OverlayStudioServer`, `OverlayStudioAllowPrivateNetworks`,
   `OverlayStudioBlockChromeServices` and `OverlayStudioCaptureSocket`, each only when absent. `RESTInterface` is never
   touched.
8. Each chosen `Application.xml`: the module entry.
9. `lib/`: the module jar only, replacing an older one.
10. `transcoder/templates/`: the three starters (`overlaystudio-cpu.xml`, `overlaystudio-gpu-eva.xml`,
    `overlaystudio-passthrough.xml`), only if absent.
11. The plugin page into `manager/wse-plugins/server/overlay-studio/`, and the bridge into `manager/temp/` when the
    Manager passes the check.
12. Write the manifest, start the capture service and report whether it runs.

Edits are text insertions, not a DOM rewrite: a small tag scanner finds the element and the new lines go before its
closing tag with the file's own indentation, line endings and byte order mark. Every other byte stays as it was.

**Manifest.** `conf.modules/overlaystudio/install-manifest.json` records every file added, every element inserted, the
backup and post-install SHA-256 of every edited file, and the capture service's record (accounts it created, group
memberships it added, folders, units, scheduled task). Saved overlays live beside it in
`conf.modules/overlaystudio/<application>/`.

**Rollback.** Changes are applied in order and remembered; a failure undoes what the run wrote, the capture service
included, and exits 5 (6 if something could not be put back). Running the installer again changes only what is
missing; an unchanged install writes nothing and makes no backup.

**Uninstall.** The capture service is stopped first. Each edited file whose hash still matches the post-install hash
is restored byte for byte from its backup; a file changed since keeps those changes and loses only the recorded
insertions. Added files, the plugin page, the bridge, the module jar and unedited starter templates are removed
(`--keep-templates` keeps the starters), then the capture service with everything its record names, including a
`wse-overlay-studio` user only if the install created it. Saved overlays are kept unless `--purge`. Backups are kept and
listed. `--remove-app` takes the module out of chosen applications by the same hash rule. On Windows, uninstall uses
`takeown` on the service's work folder; on a non-English Windows its prompt answer was not checked.

**Packages.** The setup program is built with `jpackage` and WiX 3.14.1 and carries its own runtime; its last step
opens the setup window, which elevates itself. Removing it runs `Uninstall --yes` first. The `.deb` installs to
`/opt/wse-overlay-studio/`, runs `install.sh --yes` on configure (always exiting 0 and saying how to finish on failure)
and `uninstall.sh --yes` on removal. Packages are not code signed.

## Chrome download and updates

The installer reads Google's Stable list
(`https://googlechromelabs.github.io/chrome-for-testing/last-known-good-versions-with-downloads.json`) and downloads
the `win64` Chrome for Testing zip, or the `linux64` chrome-headless-shell zip, from `storage.googleapis.com`, over HTTPS only, with every
redirect checked against those hosts. Google publishes no checksums for these files, so the hosts are the integrity
guard. The zips unpack straight into the version folder; a `.complete` file written last marks it whole, and an
incomplete folder is removed and unpacked again. Zip entries that would land outside the folder are refused. A complete
Chrome already on disk is reused, so a reinstall downloads nothing. The installer stops the capture service, and waits
for its Chrome processes to end, before it changes any Chrome folder.

`UpdateChrome` runs daily: a systemd timer `WowzaOverlayStudioChromeUpdate.timer` (`OnCalendar=daily`,
`RandomizedDelaySec=4h`, `Persistent=true`) on Linux, a scheduled task `WowzaOverlayStudioChromeUpdate` as SYSTEM with a
4-hour random delay on Windows. Each run:

1. Stops if `capture.properties` says `autoUpdate=false` or names a Chrome outside the Chrome folder.
2. Stops if the Chrome in use is the newest Stable, except on Linux when it is still Chrome for Testing from before
   1.15.0: that install moves to the headless shell of the same version, once; a move that failed is tried again only
   for the next Stable version.
3. Downloads the new Chrome next to the current one; a failure here changes nothing.
4. Sets ownership, rewrites only the `chrome` line of `capture.properties` (dropping a `chromedriver` line left by an
   earlier version), restarts the service,
   and waits up to 90 s for it to report, through its socket, Chrome at the new path.
5. On failure puts `capture.properties` back byte for byte and restarts on the previous Chrome.
6. On success keeps the new and the previous version and deletes older ones.

Each run writes `chrome-update.json` next to `capture.properties`; `/info` and the Server view show it. By hand:
`sudo /opt/wse-overlay-studio/update-chrome.sh`, or as administrator `update-chrome.cmd` or `overlaystudio-cli.exe UpdateChrome`.

## Logging

Module lines go through WSE's logger to `logs/wowzastreamingengine_access.log` (INFO) and
`wowzastreamingengine_error.log` (WARN), and all contain `OverlayStudio`. Logged: module start and access mode, overlay
and template changes with the REST user, template assignments with the list before and after, refused requests,
egress blocks, and streams skipped for running a VIF template. The capture service logs browser opens, reloads,
retries and closes to its own log. Diagnostic: `-Doverlaystudio.egress.trace=true` on the capture service's JVM logs every
request line a proxy receives.

## Testing

Unit tests run with JUnit 5 per subproject (`./gradlew :<subproject>:test`):

| Subproject | Covers |
|---|---|
| `module` | Encode matching, crop and scaled draws, keying, the draw double buffer, live template change detection, stream file loading, path and name checks, write access refusals, template builder, validator and file operations, image library, named overlays, template order, the capture client; a coexistence test that no class, REST path or property collides with VIF's overlay plugin |
| `capture` | Egress rules (every special range and its boundaries, allow and block lists, ports, Chrome service hosts) and the proxy over real sockets; browser pool sharing and grace; memory and session limits; Chrome arguments that keep the proxy and sandbox; the socket protocol and the service over a real socket, with a stand-in page in place of Chrome; refusal to run as root or LocalSystem |
| `bridge` | Allowed paths and methods, path tricks, the method override, body cap, credentials forwarded server side and never returned, target taken from the session |
| `installer` | Byte-exact edits and restore, edits by others kept on uninstall, rollback after a failure at each step, idempotent re-runs, application discovery, the capture service's unit, AppArmor profile, nssm and ACL commands (through a stand-in system), a running WSE stopping the run before anything is written, Chrome download host, redirect and zip checks, the Chrome updater's rollback |

Not automated:

- The module inside a real WSE: loading, REST registration, drawing on a live transcode.
- The bridge inside a real Manager: the Manager's token interceptor, the admin role, class loading from `manager/temp`.
- The plugin page in a browser.
- Real Chrome through the egress guard, and the process reaper.
- Generated templates loaded by WSE's own transcoder code rather than the module's validator.
- Write access for a non-admin through real WSE REST authentication.
- The installer as root against a real Linux install, and upgrades from one release to the next.
