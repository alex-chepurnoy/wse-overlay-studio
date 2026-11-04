# Wowza Overlay Studio 2.0.0

Overlay Studio draws overlays into the transcoded outputs of a Wowza Streaming Engine live stream: live web pages,
text, dates, clocks and countdowns, shapes and images, built in a page inside WSE Manager.

## Release files

| File | For |
|---|---|
| `Wowza-Overlay-Studio-2.0.0.exe` | Windows setup program, with its own Java runtime |
| `wse-overlay-studio-2.0.0-windows.zip` | Windows, on WSE's Java |
| `wse-overlay-studio-2.0.0-linux.tar.gz` | Linux, on WSE's Java |
| `wse-overlay-studio_2.0.0_all.deb` | Debian and Ubuntu |

`SHA256SUMS` lists the SHA-256 of each. Requirements and install steps: README.

## What it does

- **Five layer kinds in one overlay:** web pages, text, dates and times, shapes, and images, mixed freely and stacked
  in the order you set, up to 64 layers. The module draws text, shapes and images itself; only web pages use the
  capture service, which runs Chrome sandboxed under its own account.
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
- **Transcoder templates built in the page,** each carrying up to four overlays on the input or on chosen outputs, in an
  order you set, picked from the overlays you made, with thumbnails. **Copy** sends an overlay to other applications.
- **Streams and On air:** put a template on a stream with a dry run first, watch each output, and see what each one
  carries.
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
