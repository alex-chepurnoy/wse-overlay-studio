# Wowza Overlay Studio 2.0.0

<!-- 1x-compat:begin -->
**Wowza Overlay Studio 2.0 replaces URL Overlay 1.x and is not compatible with it.** Uninstall URL Overlay first;
overlays, templates and settings made with 1.x are not carried over.
<!-- 1x-compat:end -->

## A new name, everywhere

<!-- 1x-compat:begin -->
URL Overlay is now **Wowza Overlay Studio** (in WSE Manager's top bar: **Overlay Studio**). Every identifier changed,
so scripts, settings and bookmarks written for 1.x must be redone:

| What | 1.x | 2.0 |
|---|---|---|
| REST base | `/v1/{server}/plugin/urloverlay` | `/v1/{server}/plugin/overlaystudio` |
| Manager bridge | `/enginemanager/urloverlay-api` | `/enginemanager/overlaystudio-api` |
| Server and application properties | `UrlOverlay*` (`UrlOverlayServer`, `UrlOverlayAllowHosts`, `UrlOverlayIndex`, ...) | `OverlayStudio*`, same suffixes |
| Template properties | `urlOverlay`, `urlOverlayEncodes` | `overlayStudio1` to `4`, `overlayStudioEncodes1` to `4` |
| Saved overlays and the install record | `conf.modules/urloverlay` | `conf.modules/overlaystudio` |
| Uploaded images | `content/urloverlay/` | `content/overlaystudio/` |
| Capture service and its account | `WowzaURLOverlayCapture`, `wse-url-overlay` | `WowzaOverlayStudioCapture`, `wse-overlay-studio` |
| Linux folders and package | `wse-url-overlay` | `wse-overlay-studio` |
| Windows folders | `Program Files\Wowza URL Overlay Service`, `ProgramData\WowzaURLOverlay` | `Program Files\Wowza Overlay Studio Service`, `ProgramData\WowzaOverlayStudio` |
| Module jar and Manager page | `wse-plugin-url-overlay-*.jar`, `wse-plugins/server/url-overlay` | `wse-plugin-overlay-studio-*.jar`, `wse-plugins/server/overlay-studio` |

The GitHub repository moved to `alex-chepurnoy/wse-overlay-studio`; the old address redirects.
<!-- 1x-compat:end -->

## Before you install

<!-- 1x-compat:begin -->
Uninstall URL Overlay 1.x first, with WSE stopped, the way it was installed:

- Setup program: Windows Settings, Apps, **Wowza URL Overlay**, Uninstall.
- Zip: 1.x's `uninstall.cmd` (add `--purge` to delete its saved overlays too).
- `.deb`: `sudo /opt/wse-url-overlay/uninstall.sh --purge` to delete its saved overlays, then
  `sudo apt remove wse-url-overlay`.
- Tarball: 1.x's `sudo ./uninstall.sh` (add `--purge` to delete its saved overlays too).

Every 2.0 installer refuses to run while 1.x is on the machine: the zip and tarball stop with exit code 9, and the setup
program and the `.deb` stop with the same message, naming what they found. If that is a folder 1.x's uninstall could
not delete, delete it by hand. What 1.x leaves (`conf.modules/urloverlay`, `content/urloverlay`, templates with
`urlOverlay` properties) is ignored; those templates draw no overlay until they are rebuilt in Overlay Studio. Full
steps: README, Before you install.
<!-- 1x-compat:end -->

## Release files

| File | For |
|---|---|
| `Wowza-Overlay-Studio-2.0.0.exe` | Windows setup program, with its own Java runtime |
| `wse-overlay-studio-2.0.0-windows.zip` | Windows, on WSE's Java |
| `wse-overlay-studio-2.0.0-linux.tar.gz` | Linux, on WSE's Java |
| `wse-overlay-studio_2.0.0_all.deb` | Debian and Ubuntu |

`SHA256SUMS` lists the SHA-256 of each.

## What is new

- **Five layer kinds in one overlay:** web pages, text, dates and times, shapes, and images, mixed freely and stacked
  in the order you set, up to 64 layers. The module draws text, shapes and images itself; only web pages use the
  capture service.
- **Dates and times inside any text**, as tokens: the date and time now, a countdown to a target (with the text to show
  at zero), a count-up, and the time since the stream started, in a format from a list or your own pattern, in any
  time zone and language. The server redraws them as they change, once a second or once a minute.
- **Create on the canvas, refine in the panel:** pick a kind from **Add layer** and click or drag it onto the canvas;
  then move, resize and turn it, and set its look, place and timing (show and hide times, fades, blinking) in the
  inspector.
- **Starting points:** shape presets, badges and bars (a live badge, a lower third, a title card, a ticker), a corner
  bug, date and time formats, and text styles.
- **Fonts that look the same in the editor and on the video:** Fira Sans, Fira Sans Condensed, Fira Mono and PT Serif
  ship with Overlay Studio, under the SIL Open Font License.
- **The server check:** the Overlays tab compares where the server draws each layer with the editor's canvas after every
  edit, and can lay the server's drawing over the canvas.
- **Up to four overlays per template,** each on the input or on chosen outputs, in an order you set. A template picks
  from the overlays made in the Overlays tab, with thumbnails, and **Copy** sends an overlay to other applications.
- **Live values:** a REST call sets a layer's text, colors, opacity or visibility while on air, without saving the
  overlay (`.../overlays/{name}/live`).

## Limits

- 64 layers in an overlay, and four overlays in a template.
- 2,000 characters in a text layer.
- An overlay file of at most 1 MB.
- An overlay's image layers add up to at most 32 million pixels once decoded (about 128 MB); an image file over 8 MB is
  not drawn. Uploads are at most 2 MB and 4096 pixels a side.
- An overlay's own width and height are at most 8192.
- Text draws only in the bundled fonts.
- The server check in the Overlays tab needs write access (a WSE administrator).
