# 2.0 plan: text, date and time, shapes and images beside web pages

Status: draft for Alex's review. Code citations are line numbers in the local checkout (1.15.0, branch
`capture-rework`); the pull requests start from GitHub `main` at 1.15.1, where a few packaging lines sit a little lower
(noted where it matters). **2.0 is a clean break from URL Overlay 1.x**: everything is renamed, nothing from
1.x is read or migrated, and 1.x must be uninstalled first (decided by Alex, 2026-10-31; only two 1.x installs exist,
both test machines). Every code claim cites a file and line in this repository (paths relative to the repo
root; `M/` is `module/src/main/java/com/wowza/wms/plugin/urloverlay/`, `I/` is
`installer/src/main/java/com/wowza/wms/plugin/urloverlay/install/`). Anything not checked is marked **unverified**.

## 1. Where 1.15.1 stands (the code 2.0 starts from)

The module already draws far more than web pages. The page offers only web pages.

| Fact | Evidence |
|---|---|
| `ObjectConfig.draw()` draws rectangle, line, string, ellipse, triangle, image and webPage | `M/rest/ObjectConfig.java:782-1024` |
| A string with `useDate` is formatted with `SimpleDateFormat` | `ObjectConfig.java:876` |
| Every type gets displayStart/Stop, flashRate, rotate, opacity, shadows, gradients | `ObjectConfig.java:700-780`, `:1467-1489` |
| Named overlays already hold a list of objects of any type; only a webPage gets the URL check | `M/templates/OverlayStore.java:81-130` |
| All layers are drawn by Java2D into one ABGR image, sorted by zOrder | `M/OverlayImage.java:422-491`, `M/rest/OverlayConfig.java:583-599` |
| Preview and capture REST refuse anything but webPage | `M/rest/NamedOverlayPreviewConfig.java:37-39`, `NamedOverlayCaptureConfig.java:69-71`, `:89-91` |
| The page creates only webPage objects; other types get only a Place tab | `ui/js/uo-overlay.js:618`, `:663` |
| A template carries one named overlay, in transcoder slot 16 | `M/templates/TemplateValidator.java:34-35`, `M/rest/OverlayConfig.java:50` |
| Template images are WSE's own `<Overlay>` entries, slots 1 to 8, not drawn by the module | `M/templates/TemplateBuilder.java:104-115`, `:623-648` |
| WSE keeps transcoder overlays in a `HashMap` keyed by index, any integer accepted on the Java side | `C:\wowza\wse\wms\src\java\com\wowza\wms\transcoder\model\TranscoderStreamSourceVideo.java:21-22`, `:153-190` |
| WSE's REST config base ignores unknown JSON fields | `C:\wowza\wse\wms\src\java\com\wowza\wms\rest\ConfigBase.java:54` |

### Defects that block shapes and text today

| # | Defect | Evidence | Effect |
|---|---|---|---|
| D1 | The refresh timer redraws only when a web page has a new frame | `M/OverlayImage.java:224-231`, `ObjectConfig.java:1097-1110` | A clock never ticks; flash and display windows are checked only when something else redraws |
| D2 | `getFont` ignores its `name` and `size` arguments (it uses `style`, `:379`) and builds from fields; the cache key and the font disagree | `ObjectConfig.java:375-381` | Latent: strings derive their drawn size separately (`:869`) and `awt_font(scaled)` is unused, so no wrong output is known. Fixed while rewriting font handling |
| D3 | Bold and italic come from the font name, `fontStyle` is never used | `ObjectConfig.java:337`, `:640-649` | Style picker would do nothing |
| D4 | Named overlays drop overlay-level style defaults (font, color, stroke, shadow) | `OverlayStore.java:31`, `OverlayConfig.java:294-301` | Defaults set on the overlay are lost on save |
| D5 | A partial PUT drops viewport, key, paintBackground and crop fields | `ObjectConfig.java:1491-1639` | A text edit through REST resets a page's key color |
| D6 | POST without `type` throws a NullPointerException | `ObjectConfig.java:1711` | 500 instead of 400 |
| D7 | `equals()` skips gradients, image, x2 to y3, fontSize and crop, and has no `hashCode` | `ObjectConfig.java:589-631` | Latent: nothing in the module calls it. Completed or removed so new code cannot rely on it |
| D8 | `evenCoords` rounds the stored fields in place on every draw | `ObjectConfig.java:685-695` | Values drift up by one on odd input and the saved file differs from the draw |
| D9 | `flashRate` counts draws, and each output scale counts separately | `ObjectConfig.java:696`, `:755-758` | Blink speed depends on draw rate and number of outputs |
| D10 | `rotate` uses -1 for "not set" and is applied only when greater than 0 | `ObjectConfig.java:135`, `:774-778`, merge `:1559-1562` | Counter-clockwise rotation impossible |
| D11 | Font, color, stroke and gradient caches are static, unsynchronized, unbounded `HashMap`s; the gradient key includes coordinates | `ObjectConfig.java:71-74` | Races between draw threads; memory grows with every drag and scale |
| D12 | Per-stream PUT changes live objects and sorts the live list without `overlayObjectLock` (`ObjectConfig.java:541-543`), while a draw walks it holding that lock (`OverlayImage.java:451-458`), so the race is one-sided | `ObjectConfig.java:540-545`, `OverlayConfig.java:574-578`, `OverlayImage.java:451-458` | Torn draws or `ConcurrentModificationException` |
| D14 | The encode scaling denominator is the source size at stream start but the overlay's own size after a save | `UrlOverlayModule.java:1121-1129`, `:766-767`, `:808-810`, `:1335-1345` | An overlay whose `w`,`h` differ from the source changes size on the encodes after its first save |
| D13 | One `drawingImage` flag per overlay is shared by every output scale | `OverlayImage.java:47`, `:365-388` | Outputs draw one after another, a frame apart; a slow draw makes an output skip an update |
| D15 | Leaving a panel field rebuilds the save bar, and the field is left on the mousedown of a click on the bar | `ui/js/ovs-overlay.js:699` (`change` calls `onField`), `ui/js/ovs-core.js:404-425` (`U.saveBar` rewrites the bar's `innerHTML`) | The first click on Save or Discard straight after typing does nothing; the second works. Found by the page smoke tests (`tests/ui/smoke.spec.js`). **Fixed** ahead of the page work: `U.saveBar` rewrites the bar only when what it shows changes |

## 2. Goals for 2.0

1. Five layer kinds in one overlay: **Web page, Text, Date and time, Shape, Image**, mixed freely and stacked by order.
   Date and time can sit inside any text ("Live from Boston, 3:45 PM").
2. Create on the canvas, refine in the right-hand panel.
3. Ready-made starting points: shape presets, date and time formats, text styles, a font list that looks the same in
   the page and on the video.
4. Templates offer Web page, Text, Date and time, Shape and Image, each picked from what is saved or from a preset.
5. More than one overlay per template, each on its own outputs, in an order the user sets.
6. A product name that says it does more than URLs: **Wowza Overlay Studio**, applied to every identifier (section 5).
7. **Not compatible with URL Overlay 1.x.** 2.0 reads no 1.x overlay, template, setting or REST path, and its
   installers refuse to run while 1.x is installed. The README, USAGE and release notes say so at the top.

An overlay is the group: it already holds several web pages, and in 2.0 it holds any mix of the five kinds, which
save, go on a template and go on air together. What stays out of scope is grouping **inside** an overlay.

Out of scope for 2.0: animation beyond fade, user-uploaded fonts, multi-select and sub-groups inside an overlay
(moving several layers of one overlay as one; presets are built as single layers so they need neither), touch editing, scripting or data binding beyond the live text call in section 3.6, and
non-web-page kinds in the four-step task (it stays a web page flow; `ui/js/uo-task.js:95-160` is built around a URL and
a test capture).

## 3. Module changes

### 3.1 Redraw scheduler and draw path (fixes D1, D9, D13)

- Each object reports `nextChangeAt(now)`:
  - a web page: "on new frame", as today;
  - text with a time token: the next boundary of its smallest unit (the next second for `ss`, the next minute for
    `mm`; tokens finer than a tenth of a second are refused);
  - blink: the next toggle; fade: every 100 ms while fading; a display window: its start or stop;
  - a static object: never.
- `OverlayImage` keeps one timer per overlay that sleeps until the earliest `nextChangeAt` (and the web page frame
  check at `refreshRate` when it has pages). Redraws are capped at 10 per second per overlay.
- **Content revision** (always built): a counter on each `OverlayImage` (one per stream and slot), bumped under the
  image lock (below), together with a `resetUuid()` so the hand-off gate sees it, by a live write reaching it, a
  per-stream PUT on its stream, or an `imageFile` change (3.5);
  never by ticks or page frames. A named-overlay save replaces the stream's `OverlayImage` altogether
  (`UrlOverlayModule.java:244-277`, `OverlayConfig.java:418-431`), so it starts a new one at revision 0 rather than
  bumping. A live write snapshots the live values together with the revision bump under the image lock, so a draw
  never pairs new live text with the previous revision's box.
- **Live writes and saves are ordered** by one lock per overlay in the live store (3.6): the store merge, the list of
  images a write must reach, and the installation of a new `OverlayImage` after a save (today built at
  `UrlOverlayModule.java:264-269`, `OverlayConfig.java:418-431`, installed at `UrlOverlayModule.java:762-770`) all
  take it. A new image takes its live snapshot after it is installed, under that lock, so a write is never lost
  between the old image and the new one. It fixes the bounding box (below). The existing `uuid` keeps its one job,
  "the image changed, hand it off" (`OverlayImage.java:355`, `:483-485`).
- **Every draw publishes one record per scale, under one lock per `OverlayImage`** (the "image lock"). The record is
  immutable: buffer, `w`, `h`, `x`, `y`, `uuid`. The module reads that record instead of calling
  `getCurrentImageBuffer` and then the separate `getWidth`/`getHeight`/`getxPos`/`getyPos` fields
  (`OverlayImage.java:526-544`, read at `UrlOverlayModule.java:1290-1303`). Today those fields never change for an
  `OverlayImage`; with bounding boxes per content revision (below) they do, and a buffer must never be paired with
  another revision's size (`createOverlayFrame`, `:666-719`). Every `resetUuid()` (save, live write, per-stream PUT,
  scheduler tick, page frame) also takes the image lock, so a publish and a reset never interleave (today
  `mergeOverlayConfig` resets outside any lock, `OverlayImage.java:293`).
- **Page frames per scale** (always built): today a page layer's "frame already drawn" marker is one field
  (`drawnSeq = seq`, `ObjectConfig.java:1009`; `hasNewFrame`, `:1109`; read by `RefreshTimer`,
  `OverlayImage.java:224-231`). With scales drawing in parallel, one scale drawing a newer frame would hide it from
  another. So the marker is kept per layer and scale, committed only when that scale's draw publishes, and a frame
  counts as new while any **active** scale has not published it. One `onBeforeScaleFrame` runs per source frame and
  loops over the source and every encode itself (`UrlOverlayModule.java:1171`, `:1278`, `:1326`), so every target is
  called back on every frame; "called back" cannot be the test. A scale is active when, on the current frame, the
  per-frame path **would hand this overlay to a target at that scale**: decided before the `uuid` gate (`:1250`) from
  `getOverlaySource()` for the source (scale 1.0, `:1176`), and from `overlayEncode()` and not `isPassThrough` for each
  encode, which maps the encode to its scaling factor (`:1340-1345`). The set is recomputed every frame, so a scale
  the overlay no longer goes to (moved from source to encodes, an encode removed or no longer matching) drops out at
  once with its markers. Asking for a buffer is not the test either: once a draw is handed off no scale asks again
  until the `uuid` changes (`:1290-1291`, `:1348-1349`).
- **Scale denominator, canvas size and published box are three things** (fixes D14).
  - **Denominator:** an encode's scaling factor is its height over the **source** height, always. 1.x is inconsistent:
    at stream start the decoder notifier writes the source size into `streamWidth`/`streamHeight`
    (`UrlOverlayModule.java:1121-1129`), but a later save copies the overlay's own size there (`setOverlayImage`,
    `:766-767`; also `addOverlayImageQueue`, `:808-810`), and the encode path divides by it (`:1335-1345`). 2.0 divides by
    the existing `sourceWidth`/`sourceHeight` (`:190-193`, written only by the transcoder at `:1124-1125`, already
    read by `checkOverlayFitsSource`, `:634-635`) and retires `streamWidth`/`streamHeight`. Layer
    coordinates are in source pixels (the page says so, `ui/js/uo-overlay.js:676`).
  - **Canvas size, per slot:** the overlay's `w`,`h`, or the source size when they are 0. `saveNewObject` today writes
    a stream-level size and builds the image from it (`OverlayConfig.java:410-418`), so a `w` = 0 overlay inherits
    whatever the stream holds; 2.0 passes the slot's canvas size explicitly. Unchanged by bounding boxes.
  - **Published box:** the crop origin and size inside the canvas, carried only in the published record.
  - (`checkOverlayFitsSource` reads the image's own size and the source size, `:627-645`, and is unaffected.)
- **The boundary redraw:** at each `nextChangeAt` the scheduler calls `resetUuid()` and the next frame starts the
  draw for each scale on its own in-flight flag, as today. Spike S2 (`docs/perf/2026-11-02-s2-redraw-cost.md`) measured
  it: with bounding-box drawing (below), 12 streams redraw all four outputs within 2.5 ms of the second even on the
  8-core AWS server, so no draw-ahead scheme is built. An overlay that must use the full canvas can show a clock change
  one or two frames late at 12 streams on 8 cores (38 to 56 ms), which a seconds clock does not visibly show.
- **Inline draws** (`threadDrawing: false`, drawn on the transcoder thread, `OverlayImage.java:362-380`): the scheduler still decides when the image changes by calling `resetUuid()`; the
  next frame then draws inline as today. The per-scale in-flight flag does not apply to them.
- **Per target, not per stream:** one in-flight draw per output scale instead of one per overlay (fixes D13), and the
  last image sent is tracked per target (the source, or each encode) instead of the single `lastImageUuid` every encode
  overwrites today (`M/UrlOverlayModule.java:1294`, `:1350`), so an encode whose draw finished late still gets it.
- **Draw the layers' bounding box, not the full frame** (required: S2 found a full-canvas redraw is almost all
  clearing and converting the frame, and the box cuts a clock's redraw 35 to 40 times), wherever the overlay has no
  full-frame layer: allocate, clear and
  convert only that region (`createOverlayFrame` already crops at hand-off, `M/UrlOverlayModule.java:666-719`; this
  moves the crop before drawing). The box covers shadow, stroke, outline, a text layer's background box and
  rotation, keeps an even origin per scale,
  and is fixed per content revision (with text boxes at their `w`,`h`), so a ticking clock reuses its buffer instead of
  allocating one each draw (the spare buffer is reused only at the same length, `M/OverlayImage.java:478`). Text whose
  width changes between draws without a revision (a token in text without `box`) counts as spanning
  the frame's full width over its line height, so a wider second is never clipped.
- Blink moves to seconds: `blinkSeconds` (3.3a), from wall-clock time so every output blinks together. `flashRate`
  (counted in draws) is removed.
- `SharedFrameTest.anOverlayWithNoPageNeverAsksForARedraw` (`module/src/test/.../SharedFrameTest.java:71-83`) stays
  true for static objects and gains siblings for each dynamic kind.

### 3.2 Text (fixes D2, D3)

Fields on `string` (kept as the type name; the page calls it Text). With no 1.x data to honor, these mean the same on
every layer; there is no format marker:

| Field | Meaning |
|---|---|
| `font`, `fontSize`, `fontStyle` | Family, pixels at source size (1 to 1,000), style (plain, bold, italic, bolditalic). `fontStyle` alone sets the style; the font name is only a family (the style-from-name parsing at `ObjectConfig.java:337`, `:640-649` is removed) |
| `box` | `true` makes `x`,`y`,`w`,`h` a text box: wraps at `w`, clips at `h`. Without it, `x`,`y` is the text's baseline start, as drawn today (`ObjectConfig.java:868-905`). The page shows and takes a free text's Y as the top of its first line and stores the baseline, so saved overlays and the REST API keep their meaning |
| `align` | left, center, right inside the box |
| `lineHeight` | Multiple of the font size, default 1.2 |
| `background`, `padding`, `radius` | Box behind the text, for a badge or lower third in one layer. With `box`, the background fills `w`,`h` and `padding` insets the text inside it; without `box`, the background is the text's extent plus `padding` on each side |
| `outlineColor`, `outlineWidth` | Text stroke (0 to 20 px), readable over any video |
| `autoFit` | Shrink the font until the text fits the box, down to 50% |

`getFont` is rewritten to build from its arguments. `useDate` (a whole string as one `SimpleDateFormat` pattern,
`ObjectConfig.java:876`) is removed; date and time are tokens (3.3).

### 3.3 Date and time as text tokens

Instead of a new type, text may contain tokens, so date and time can sit inside any string:

| Token | Draws |
|---|---|
| `{time:<pattern>}` | Now, in the layer's zone |
| `{countdown:<pattern>}` | Time left to the layer's `target`; `endText` when it reaches zero |
| `{countup:<pattern>}` | Time since `target` |
| `{streamtime:<pattern>}` | Time since this stream started |

Layer fields: `timeZone` (IANA, default the server's zone, always shown in the panel), `locale` (default `en-US`),
`target` (ISO date-time with offset), `endText`. Literal braces are written `{{` and `}}`. A token runs from `{` to
the first `}` outside single quotes, so `{time:HH 'o}clock'}` is one token. A lone `{` or `}` outside
a token (including `{time:HH}}`, a token followed by a stray brace) is refused with 400. Golden tests cover all three.

Two pattern languages, both checked at save (400 with the reason):

- `{time:...}` uses `java.time` `DateTimeFormatter` patterns.
- `{countdown}`, `{countup}` and `{streamtime}` format a duration, which `DateTimeFormatter` cannot. Their grammar:
  `d` days, `H`/`HH` hours, `m`/`mm` minutes, `s`/`ss` seconds, `S` tenths; doubled letters zero-pad to two digits;
  text in single quotes is literal. **The largest unit in the pattern is a total**, the others are remainders: a
  three-day countdown in `H:mm:ss` shows `72:00:00`, and 90 minutes in `mm:ss` shows `90:00`. Example:
  `d'd' HH:mm:ss`.
- A countdown or count-up with no `target` is valid: it draws `endText` (empty when unset), and the page's layer row
  and Format tab say "No target set". Countdown presets set no target, so Create overlay always succeeds and the
  user sets the target in the Format tab. Samples (preset lists, Format tab, `GET /format` without `target`) use a
  nominal target one hour after the moment the layer was opened in the editor, or the preset list was opened (fixed
  for that session and sent as `target`, so the sample does not jump back at each refetch); the canvas draws such a layer from that sample, faded,
  with a "No target set" tag, so it stays visible and clickable. The render check's comparison image draws `endText`
  for it, as the server does.
- A countdown past its target shows `endText` (empty when unset). A count-up before its target shows zero. Stream time
  starts at zero when the stream's transcoder starts and restarts with it.

### 3.3a Timing fields

On every layer (all optional; absent means always shown):

| Field | Meaning |
|---|---|
| `showFrom`, `hideAt` | ISO date-time with offset (the panel enters it in the layer's `timeZone`, shown) or `stream+N`, seconds after the stream's transcoder started |
| `blinkSeconds` | On for N s, off for N s, from wall-clock time |
| `fadeIn`, `fadeOut` | 0 to 2 s, at the start and end of the show window; redraws every 100 ms only while fading |

They replace the earlier `displayStart`, `displayStop` and `flashRate`, which are removed.

### 3.4 Shapes (fixes D8, D10)

Kept: rectangle (rounded with `arcWidth`/`arcHeight`), ellipse, line, triangle. Added:

- `lockAspect`, a saved field, on rectangle, ellipse and image (square and circle in the page). Absent means on for an
  image and off for a rectangle or ellipse; the page always writes it.
- `arrowHead` on line: none, end, both.
- `dash` on every outline: solid, dashed, dotted.
- `rotation` (degrees, any sign, absent means none) replaces `rotate`, which used -1 for "not set" and applied only
  values above 0 (`ObjectConfig.java:135`, `:774`).
- Even rounding moves to draw time and stops mutating fields.

### 3.5 Images drawn by the module

- An `image` layer names its picture with `imageFile`: a file in the virtual host's `content/` folder, checked with
  `ImageLibrary.safeFile` (`M/templates/ImageLibrary.java:151`) and, on every load, the library's listing limits:
  8 MB and 4096 px a side (`:54`, `:109`; uploads stay at 2 MB, `:34-35`). The shared picker hides files over those
  limits for Image layers. An `image` layer with no `imageFile` is valid and draws nothing (the
  Corner bug preset, 4.3); `safeFile` is not called for it.
  The scheduler checks its modification time every 10 s; on a change it acquires the new cache key, releases the old
  one, and bumps the content revision (3.1), so a replacement of a different pixel size gets a new box, also for an
  otherwise static overlay. (`base64Image` is removed, 3.10.)
- **Size.** Today an image draws at the file's own pixel size times the scale and ignores `w`,`h` (`imageDrawSize`,
  `ObjectConfig.java:990-991`, `:1408-1412`). In 2.0 an image layer is drawn into its `w`,`h`, so the canvas box,
  resize handles and preset boxes mean what they show. A `w` or `h` of 0 or less, or absent (an `ObjectConfig` defaults
  them to -1, `ObjectConfig.java:120`, `:123`), means native size (as `webPageDrawSize` already treats 0,
  `:1400-1405`), with the other side from the aspect. When `w`,`h` do not match the file's aspect (after an
  `imageFile` replacement, or a REST write): with `lockAspect` on, the module fits the image inside `w`,`h`, centered
  (contain); with it off, it stretches to fill. The canvas SVG uses `preserveAspectRatio="xMidYMid meet"` and `"none"`
  to match, so the render check agrees. Choosing a file sets `h` from the file's aspect at the current `w`; the Place
  tab offers lock aspect for images, on by default.
- A missing file draws nothing for that layer and is reported in `/status` and on the layer row.
- **Decoded images are shared.** Today each object decodes its own copy (`decoded`, `ObjectConfig.java:1033-1072`),
  and a named overlay is loaded as new objects for every stream (`UrlOverlayModule.java:264`, `OverlayConfig.java:418`),
  so 12 streams would hold 12 copies. A JVM-wide decoded-image cache, keyed by file and modification time, is shared by streams, the draft render and thumbnails. Entries
  are reference-counted by the layers using them (a clone takes its own reference, 3.6a; an `imageFile` change
  releases the old key and acquires the new one); only entries no layer references are evicted, least recently used
  first, once the cache passes 512 MB. Referenced images are never evicted, so the bound is on what is kept for
  reuse; referenced memory is bounded instead by the per-overlay pixel budget (3.10) and reported in `/status` (total
  decoded bytes) with a warning above 1 GB.
- This is the Image layer inside an overlay (timed, ordered with text and shapes). Template images (WSE's own, slots 1
  to 8) stay as they are; section 4.5 says how the Templates tab tells them apart.

### 3.6 Live text over REST

- `POST .../overlays/{name}/live/{id}` with `{"text": "HOME 2 - 1 AWAY"}` (also `color`, `background`, `opacity`
  and `hidden`) sets a **live value** on one layer, for score bugs and tickers fed by an outside system. Layer ids are
  editable in the panel (4.2) so a feed can address `score` rather than `text-3`.
- Live text is validated like saved text: 2,000 characters, token patterns and brace rules.
- **Owner:** one store per JVM, keyed by virtual host, application and overlay name, shared by every application
  instance's module (each instance runs its own module object today, reached through `reapplyNamedOverlay`,
  `UrlOverlayModule.java:284-304`). A write merges into the store and, on every running stream's image for that
  overlay in any template slot, bumps the content revision and calls `resetUuid()`. A write while no stream uses the overlay is stored and used when one
  starts.
- Merged at draw time. A write bumps the content revision of each `OverlayImage` using the overlay (3.1) and calls
  `resetUuid()`, and does nothing else: no file rewrite, no `reapplyNamedOverlay`, no new `OverlayImage`
  (which today reallocates buffers and resets `updated_at`, `UrlOverlayModule.java:754-776`,
  `ObjectConfig.java:345-347`), and no modified-time change for the page's conflict check
  (`ui/js/uo-overlay.js:164-167`).
- They survive a WSE restart through a sidecar file per overlay in a `.live/` subfolder of the application's overlay
  folder, named `<name>.live` (not `.json`, so `OverlayStore.list`, which lists every `*.json`, `OverlayStore.java:52`,
  never sees it). One writer per JVM, at most once a second, atomic (temporary file and move); loaded on first use.
  Deleting the overlay deletes its sidecar. `DELETE .../live` clears every value and `POST .../live` with
  `{"clear": "<id>"}` clears one; saving the overlay keeps them unless
  the layer is gone. Renaming a layer's id in the page moves its live value to the new id on save (the page sends
  `renamed: {"old": "new"}` in the save body), and the save answer lists the rename so the feed's owner can be told; writes to the old id get 404.
- `GET .../overlays/{name}/live` returns the current values, `{"<id>": {text, color, background, opacity,
  hidden}}`, readable by read-only users. The open editor polls it every 2 s, paused while the browser tab is hidden
  (as the state poll is, `ui/js/uo-store.js:45-49`).
- The page shows a "Live value" badge on such a layer, with the value and Clear (Clear carries `data-write`, so
  read-only users see the badge without it). The draft render (3.8) draws the saved
  values, not live ones, so the check compares like with like.
- Rate limit 20 writes per second per overlay, 429 above. Same write access as every other change (`M/rest/WriteAccess.java`);
  a narrower role for feeds is left for later.
- The per-stream PUT keeps working and now merges every field (fixes D5).

### 3.6a Thread safety (fixes D11, D12)

- Font, color and stroke caches become bounded concurrent caches (256 entries each). The gradient cache is removed:
  a `GradientPaint` holds its start and end points, so it cannot be shared across positions, and building one per draw
  is cheap.
- An overlay's object list is copy-on-write, and so are the objects in it: an edit clones each object it changes
  (today a per-stream PUT changes the live object in place, `oldObj.copyFrom(this)`, `ObjectConfig.java:540-545`),
  gives the clone its own reference on each shared resource, the decoded-image cache entry (3.5) as well as the page
  capture's `POOL.acquire` (so the old object's later release is
  only a reference-count decrement, `ObjectConfig.java:1385-1394`, and a draw never finds a null capture and opens a
  new one, `:1113-1126`), builds a new sorted list and swaps it in under `overlayObjectLock`, while also holding the image lock (3.1) so the
  swap, the content revision bump and `resetUuid()` happen together and no draw pairs the new list with the old box. A draw
  takes the list once and walks its own snapshot. Replaced objects are disposed only after draws that started before
  the swap finish (`mergeOverlayConfig` disposes them at once today, `OverlayImage.java:288-292`). The draft render (3.8)
  uses its own objects and never touches a live list.
- `OverlayStore.write` becomes atomic (temporary file and move), so `loadStreamFile` never reads half a file
  (`OverlayStore.java:128`).

### 3.7 Fonts

- Bundle four SIL Open Font License families in the module jar, static files from `github.com/google/fonts`
  (`ofl/`), registered with `Font.createFont` at start (decided 2026-10-31, section 9):

  | Family | Use | Files | Scripts beyond Latin | Same-width digits |
  |---|---|---|---|---|
  | Fira Sans | General text, the default | Regular, Bold, Italic | Cyrillic, Greek | No |
  | Fira Sans Condensed | Lower thirds, tickers | Regular, Bold, Italic | Cyrillic, Greek | No |
  | Fira Mono | Clocks, countdowns, the default for date and time presets | Regular, Bold | Cyrillic, Greek | Yes |
  | PT Serif | Serif text | Regular, Bold | Cyrillic | Yes |

  About 3.9 MB in all. None covers CJK or emoji. Spike S3 (`docs/perf/2026-10-31-font-spike.md`) settled how they are
  used:
  - **Static files only, one per weight** (Regular, Bold). `new Font(family, Font.BOLD, size)` then picks the Bold
    file; a variable font gives only its default weight and a fake bold.
  - **Italic** has no automatic fallback (Java draws the Regular face upright). Fira Sans and Fira Sans Condensed ship
    Italic files; for every other case (Fira Mono and PT Serif italic, and bold italic in any family, since no Bold
    Italic files are bundled) the module slants the face with a shear transform.
  - **Clocks need same-width digits.** Java2D cannot switch on a font's optional tabular digits, so the date and time
    presets default to a family whose digits are already the same width.
  - **Text draws with fractional metrics on** (`KEY_FRACTIONALMETRICS`; the module leaves it off today) and
    antialiasing on: spike S4 (`docs/perf/2026-11-02-s4-browser-vs-java-text.md`) found that closest to the browser's
    drawing, 0.4% median width difference against Chrome on Windows and Linux.
  - Each license goes into `licenses/` and `THIRD-PARTY-NOTICES.md`.
- **Linux needs fontconfig and one system font for any text.** S3 showed that without them every font call throws
  ("Fontconfig head is null"), bundled fonts included; web pages, shapes and images still draw. So:
  - the `.deb` depends on `fontconfig` and `fonts-dejavu-core`;
  - the tarball's installer checks for them (`fc-list` finds at least one font) and, when missing, prints the command
    (`apt install fontconfig fonts-dejavu-core`, or `dnf install fontconfig dejavu-sans-fonts`) and installs anyway
    with text unavailable;
  - the module catches the failure at font registration: `/status` and `/fonts` report "Text is unavailable on this
    server: install fontconfig and one font package", the page shows it in the Text and Date and time panels, and
    text layers draw nothing instead of failing the overlay;
  - README's Linux requirements list both packages.
  The JVM option `-Dsun.awt.fontconfig=<file>` with a `fontconfig.properties` naming the bundled Fira files also
  works (S3), but it is JVM-wide and turns every logical font in WSE into Fira, so it is documented as a workaround
  for servers that cannot install packages, not set by the installer.
- `GET /fonts` returns registered families with their styles, and any bundled family that a system font of the same
  name shadows. `registerFont` returns true even for a duplicate (S3), so the module detects a clash by checking which
  face `new Font(family, ...)` resolves to after registering. Java's logical fonts (Serif,
  SansSerif, Monospaced, Dialog, DialogInput) and installed system fonts stay valid. `GET /fonts/check?font=&chars=`
  (the text's distinct characters only, at most 512) returns which of them the font cannot draw (`Font.canDisplayUpTo`) and the family
  Java will actually use, for the warnings in 4.2a.
- Every `GET /format` answer includes the server's default zone and locale, which the panel shows as the defaults.
- `GET /format?pattern=&kind=&zone=&locale=&target=&now=&streamStart=&count=` returns `count` samples (at most 120)
  for a pattern, one per step starting at `now`, and the validation error if any. The step is the larger of the
  pattern's smallest unit and one second, so 120 samples always cover two minutes of the canvas's once-a-second tick. In the editor, stream time
  counts from when the overlay was opened, labeled "stream time is simulated here". This lets the page show samples
  and check patterns before save without a write (the browser cannot format `DateTimeFormatter` patterns or the
  duration grammar in the server's zone). The page calls it debounced and caches answers.
- The same font files ship in the plugin page and load with `@font-face`, so the canvas matches the video.
- No font uploads in 2.0: parsing untrusted font files inside WSE is a risk not worth taking yet.

### 3.8 Draft render

- `POST .../overlays/{name}/render` takes the unsaved overlay plus a frozen `now` and `streamStart` (blink state follows from `now`, since blink runs
  on wall-clock time, 3.3a), and
  returns one PNG
  of its text, shapes and images at the requested width (at most the source width, 1920 if unknown), drawn by the
  same code as the stream at source scale. **Web page layers are left out**: it never opens a capture (drawing a page
  would call `POOL.acquire`). Live values are not applied. Writes nothing. Rate limit 4 per second per user.
- The request never carries image data: image layers name their `imageFile`, which the server reads.
- It is a POST, so it needs write access like every POST through the Manager (`docs/DESIGN.md:116`); read-only users
  do without the check (4.4).
- `GET .../overlays/{name}/thumbnail?width=` returns a small PNG of the **saved** overlay. It **never opens a
  capture**: a web page layer is drawn from the frame of a capture that is already open in the pool (an overlay on
  air, or one open in the editor), otherwise as a gray box with the page's host name. A countdown or count-up with no
  target draws its "No target set" sample, faded, as the canvas does, instead of the empty `endText`. Captures live only in memory
  (`WebPageCapture.java:91`) and drawing a page layer the normal way would open one (`ObjectConfig.java:1113-1126`).
  Width snaps to 160, 320 or 640; cached per overlay and width until the overlay file, an `imageFile` it uses, or the
  page frame used (older than 60 s) changes; 4 draws per second per server, cached answers free. A GET, so read-only users and the Templates picker
  (4.5) use it without the render's rate limit.
- `NamedOverlayPreviewConfig` keeps its web-page-only purpose (testing a URL before saving).

### 3.9 Several overlays per template

- A template carries up to four module overlays, in properties `overlayStudio1` to `overlayStudio4` with
  `overlayStudioEncodes1` to `4` (replacing `urlOverlay`/`urlOverlayEncodes`, `TemplateValidator.java:34-35`).
- Each gets its own transcoder slot, 16 to 19, from `OverlayStudioIndex` upward; property order is stacking order once
  spike S1 confirmed (`docs/perf/2026-11-02-s1-overlay-stacking.md`): within a stage WSE draws slots in ascending
  order, so a higher slot is on top, and the module's slots 16 to 19 draw over the template's images 1 to 8; every
  Encode overlay draws over every Decode overlay. Same on CPU and NVIDIA (EVA). WSE draws no overlays on NETINT
  zero-copy frames (from source, untested), which the README states.
  A named overlay's own `overlayIndex` (`OverlayStore.java:31`; slot 1 uses it today, `UrlOverlayModule.java:1247`)
  is removed: slots come only from the template. A custom `OverlayStudioIndex` (read like `UrlOverlayIndex` today,
  `UrlOverlayModule.java:225`) moves all four; one whose slots would land on the template's image slots 1 to 8 or on
  VIF's slot is refused with a log line and a `/status` message.
- Every place that assumes one overlay changes:
  - `noteTemplate` and the per-stream name and encode maps (`M/UrlOverlayModule.java:864-908`);
  - `applyNamedOverlay` and `reapplyNamedOverlay` (`:244-304`), so a save reaches every stream naming it in any slot;
  - `StreamInfo`'s single image and slot state (`:196-205`) becomes a list, and the stream size writers move to the
    source-size field and per-slot canvas sizes of 3.1: the decoder notifier (`:1121-1129`), `setOverlayImage`
    (`:766-767`), `addOverlayImageQueue` (`:808-810`) and `saveNewObject`'s per-stream setters
    (`OverlayConfig.java:410-418`); and the one other reader, the MATCHSOURCE branch, which takes the destination size
    from `getStreamWidth`/`getStreamHeight` (`UrlOverlayModule.java:1333-1337`) and switches to the source size;
  - the first-frame transparent fill covers every slot used (`:1186-1216`);
  - `structure()` exclusion keys (`:316`), so adding `overlayStudio2` is not read as an output change needing a restart;
  - template assignment and `templatesUsing` (`M/templates/TemplateFiles.java:234-239`, `:494-509`), which is the real
    DELETE check behind `NamedOverlayConfig` (`M/rest/NamedOverlayConfig.java:56-71`);
  - `TemplateBuilder` (`M/templates/TemplateBuilder.java:520-526`), `TemplateValidator`'s single-overlay result and
    `OverlaysConfig` (`M/rest/OverlaysConfig.java:50`);
  - `reloadTemplateFile` (`M/UrlOverlayModule.java:354-395`), which reads one overlay after a template edit;
  - the draw queue (`queueDrawings`), one entry per stream and slot;
  - `POST /templates/{t}/use` (`M/rest/TemplateUseConfig.java:31`), used by the four-step task (`ui/js/uo-task.js:287`)
    and "Use it with an overlay": a copy keeps the source template's overlays, and "in place" adds the overlay as the
    next free slot. The task's `already` check (`uo-task.js:252`) looks at every
    slot, so it never asks for a copy of a template that already carries the overlay in slot 2 to 4.
- Slot rules:
  - The transparent first-frame fill (`:1187`, frame 0 only today) runs for each slot on its first use, so an overlay
    added to a running stream gets it too.
  - Removing `overlayStudio3` from a running template clears slot 18 on the source and every encode.
  - The per-stream REST overlay (`.../streams/{stream}/overlay`) always uses the first slot.
  - One overlay named twice in one template is refused.
- **REST shapes for the page.** Every response that names a template's overlay today (`/state`, `/streams`,
  `/status`, `/templates/{t}/validate`, `/templates/{t}/copies`) carries `overlays: [{name, encodes, slot}]`, and
  `POST /templates/{t}/overlay` (used for in-place templates, `ui/js/uo-template.js:1040`) takes `overlays: [...]`.
  While the page still uses the single `overlay`/`encodes` (until PR 11, section 8), responses also carry them as slot
  1, and `/overlay`, `/spec`, `POST /templates` and `/use` also accept them as slot 1.

### 3.10 Validation

- **Refused (400 naming the field):** a missing or unknown `type` (D6); any malformed or out-of-range field, for
  example token patterns and the brace rules, `fontSize` outside 1 to 1,000, colors, `timeZone`, `locale`, `target`,
  `showFrom`/`hideAt`, `blinkSeconds`, fades, `box`, `align`, `lineHeight`, `padding`, `radius`, outline, `autoFit`,
  `rotation`, `lockAspect`, `arrowHead`, `dash`, and `imageFile` outside `content/`; and the limits below.
- A layer `id` must match `[A-Za-z0-9][A-Za-z0-9._-]{0,63}` (the page's rule today, `ui/js/uo-overlay.js:851`) and not
  contain `..` (the bridge refuses any path with `..`, `bridge/.../UrlOverlayBridge.java:250`), and must be unique in
  the overlay. A `renamed` map may not name an unknown old id, rename two ids to one, or chain. A live POST field must
  fit its type (`color`, `background` colors, `opacity` 0 to 100, `hidden` boolean, `text` as saved text).
- **Warned, still saved:** a font not installed on this server (an overlay copied from another server). The response
  carries `warnings: [{id, field, message}]`, shown in 4.2a.
- `renamed` is never stored and never part of `hash`.
- Overlay-level style defaults are stored (fixes D4).
- **Images come from files only.** `base64Image` is removed: an Image layer names a file through `imageFile`, uploaded
  through the image library (`M/rest/ImagesConfig.java`). That keeps overlay files small and every page request under
  the Manager bridge's 2 MB cap (`UrlOverlayBridge.java:49`).
- Limits: 64 layers per overlay; text 2,000 characters; overlay file 1 MB; decoded image pixels at most 32 million
  per overlay (about 128 MB); overlay-level `w`,`h` at most 8192.

## 4. Page changes

### 4.1 Overlays tab

- **Adding a layer:** one **Add layer** menu, under the layer list and in the empty inspector: Web page, Text, Date
  and time, the eight shapes (rectangle, rounded rectangle, square, ellipse, circle, line, arrow, triangle) and
  Image, each with its icon. The overlay row's menu offers the same kinds. While placing, a banner on the canvas says
  what a click does, with Cancel. "Date and time" adds a Text layer holding `{time:HH:mm:ss}` and opens its Format
  tab. (An Add bar above the canvas was dropped on 2026-11-03: it repeated the menu and placing showed no sign on the
  canvas. PM vault: `60-decisions\2026-11-03-overlay-studio-add-layer-menu-and-stream-template-control.md`.)
- **Words:** the tab says "layer" where it says "page" for anything that is not a web page ("No layer selected",
  `uo-overlay.js:655`; the coordinates hint, `:454`; the delete confirm, `:939`; "No pages yet.", `:529`; "Actions
  for page" and "Remove page", `:537`; the Help rows, `uo-shell.js:383-385`; the shared id check's "A page with this
  name exists.", `uo-overlay.js:851`; the inspector tab list's "Page settings", `:665`; the canvas label, `:383`;
  tooltips at `:408` and `:962`; "The draft has no pages left.", `:891`; "Add pages in the editor", `:1045`; and in
  `uo-shell.js`, "An overlay holds the pages to burn in", `:204`, and the Help intro, `:388`, which also becomes "a
  template names up to four overlays"; the save bar's "add page" and "remove page", `uo-overlay.js:56`, `:72`; the
  "Removed page ... from the draft" toast, `:604`; "Select a page in the list or on the canvas", `:656`; the Place
  tab tip, `:676`; "Every page of <name>", `:682`; "Pages can still be placed on a frame of the assumed size.",
  `:398`; the layer row tooltip "the top page draws on top", `:533`). Counts from the overlay list's `pages`
  field (every object, `OverlaysConfig.java:42`) read "N layers" where shown (New overlay's copy start,
  `uo-overlay.js:1044`; the Streams badge, `uo-stream.js:57`).
- **New overlay** (`uo-overlay.js:1033-1131`): "Start from" offers One web page, **A preset** (a list of every preset
  in 4.3 except text styles, grouped by kind, with a sample), a copy, or Empty (today One page, Copy or Empty, `:1043-1045`). The test
  capture runs only for a web page. Create overlay writes it and opens the editor, as today (`:1121`), where the
  preset's text is edited like any layer. Opening New overlay with a preset while another New overlay draft is unsaved
  asks before replacing it (today `U.newOverlayFrom` replaces it silently, `:1036`).
- **Place on the canvas:** click places the layer at a default size at the click point; drag draws it at the dragged
  size; Escape cancels. Choosing a kind moves focus to the canvas in placing mode, where Enter places it in the
  frame's center. Web page skips placing: it opens its URL form (`startAdd`) and the page appears at today's default
  spot once added. In the overlay row's menu (`uo-shell.js:198`, a flat list built by `U.rowMenuHtml`,
  `uo-core.js:360`) the kinds are flat rows ("Add web page", "Add text", "Add date and time", "Add shape...",
  "Add image", "Add from a preset..."); "Add shape..." opens the overlay with the Shape menu open, and "Add from a
  preset..." opens it with the preset list open. Web page keeps its inspector form for the URL
  (`startAdd`, `uo-overlay.js:833-862`). New layers get ids `text-1`, `shape-1`, ... under the existing id rule (`:851`);
  the id is editable in the panel header (unique within the overlay, and refused if it contains `..`, matching the
  module, 3.10), because live values (3.6) address it. `startAdd`, Duplicate and the panel header share that one id
  check.
- **Edit on the canvas:** move and resize as today (`startDrag`, `:464-502`), plus a rotation handle, end-point handles
  on lines and arrows, vertex handles on triangles, and double-click on text to edit it in place (Enter or F2 on the
  active layer does the same from the keyboard, replacing today's Enter on a focused `.pg`, `uo-overlay.js:1024`; on
  any other kind Enter moves focus to the panel's first field). The active layer is always the selected one; when Tab
  enters the canvas with nothing selected, the front layer becomes active and selected. Escape deselects, as today
  (`uo-overlay.js:1026`), leaving no active layer; the next arrow, Page Up or Page Down selects the front layer.
- **Selecting:** a 6 px hit margin around thin lines; Alt+click (without moving) cycles through layers under the
  pointer. The canvas is one Tab stop: a container with `role="application"`, `aria-roledescription="canvas"` and
  `aria-activedescendant` (replacing today's focusable `role="button"` box per page, `uo-overlay.js:425`); each layer
  element has `role="img"` with its accessible name (4.4), announced as the active descendant changes. Arrow keys
  nudge the active layer, as today's per-box handler does. Inside, Page Up and Page Down select the next layer toward the front or back, and Tab leaves for the panel. The layer list remains the full keyboard route to every layer.
- **Order:** the list's top row is the front layer, as today (`reorder()` writes zOrder, `:581-585`).
- **Guides:** snap to the frame's edges and center, the safe area and other layers' edges and centers, with a visible
  guide line. Ctrl while dragging disables snapping (Alt is taken by cycling). Even rounding stays.
- **Help** (`ui/index.html:13`, `uo-shell.js:381-395`) lists every new shortcut.
- **Layer list:** rows show a kind icon and a name (text content with tokens shown as their sample, shape kind, file
  or host). Menu adds Duplicate, Bring to front, Send to back, and Hide on canvas, which only hides it in this
  browser and is never saved.
- **Changing kind:** a shape's kind can change within its geometry (rectangle, rounded rectangle, square, ellipse,
  circle share a box; line and arrow share end points). Other changes mean a new layer.
- **Undo** covers every new edit through the existing stack (`snapshot`/`restore`, `:83-100`).
- **Read-only** (`U.applyAccess`, `ui/js/uo-core.js:609-620`): the Add bar, handles and panel fields are disabled like
  today's controls (`[data-write]`). The canvas stays a Tab stop so layers can be heard: Page Up and Page Down move the
  active descendant for announcement, the panel shows the layer's values read-only, and nudge, edit and Enter do
  nothing (today read-only boxes are not selectable, `uo-overlay.js:422`, and keys return early, `:1014`).
- Touch editing is out of scope; the canvas is mouse and keyboard.

### 4.2 Right-hand panel per kind

| Kind | Tabs |
|---|---|
| Web page | Page, Place, Key and crop, Timing: today's page delay and reload rate (`uo-overlay.js:678`) plus the 3.3a fields below |
| Text | Text: style presets at the top (4.3), content, font, size, style, color, align. "Box and outline" (background, padding, radius, outline, line height, auto-fit) is collapsed until opened. Then Place, Timing |
| Text with a date or time token | A Format tab first: what to show (now, countdown, count-up, stream time), the preset list with live samples, free pattern, zone (the server's zone by default, named), locale. Target and end text appear only for countdown and count-up. Then as Text |
| Shape | Shape (kind, fill, gradient, outline color, width, dash, corner radius, arrow head), Effects (shadow, opacity), Place (x, y, w, h, rotation, lock aspect), Timing |
| Image | Image (the library picker, `pickerHtml`/`bindPicker` in `uo-template.js:478-534`, moved into a shared file that works on any target instead of the template draft's `d.images`; PR 8), Place, Timing |

Timing for every kind (fields in 3.3a): show from and hide at (a date-time entered in the layer's zone, which is
named, or seconds after the stream starts), blink every N seconds, fade in and out (0 to 2 s).

### 4.2a Problems the page shows

| Problem | Where it shows |
|---|---|
| Font not on this server (an overlay copied from another server, or a logical font resolving differently on Linux) | Layer row warning and the font field: "Drawn as Dialog on this server" |
| Characters the font lacks (non-Latin text in a bundled Latin font) | Text field: "These characters will not draw in <font>: ..." from the server's `Font.canDisplayUpTo` |
| Bad token pattern | The field, from `GET /format` (3.7) as the user types |
| Zone differs from the browser's | The Format tab always names the zone; samples and the canvas use the server's zone, from `GET /format` |
| Image file deleted | Layer row and Image tab: "File missing: <name>", the layer draws nothing. The image library's delete (shared with Templates, `uo-template.js:511`) warns when an overlay's Image layer uses the file |
| Live value set | Layer row badge with the value and Clear (3.6) |
| Countdown or count-up with no target | Layer row and Format tab: "No target set"; canvas shows the sample faded (3.3) |
| Save warnings (3.10) | Layer row and field, after save; the save still succeeds |
| Text that does not fit even at the auto-fit floor | Text tab: "Text is cut off at the box's edge" |
| Limits: 64 layers, four overlays per template, eight fixed images | The entries that hit their limit disable with the reason; the others stay (in Templates, Image (fixed) stays available at four overlays) |
| A layer partly or wholly outside the frame (a preset placed for 1920 x 1080 on a 720p background stream) | Layer row: "Outside the frame of <stream>", with **Fit to this stream**, which scales every layer's position and size by the stream's size over 1920 x 1080 |
| Draft render failed or rate-limited | The check line says "Server check unavailable", retried on the next edit |
| Server drawing differs from the canvas | See 4.4 |

Fields go through the existing registry (`ui/js/uo-fields.js`) so `tests/ui/labels_test.py` checks every new key
against the module.

### 4.3 Presets

Shape, badge and bar presets each create a single layer, so it moves, times and deletes as one thing (no groups
needed). A date and time format preset creates a Text layer holding that token, in the Caption style, in the top right
at 5% from the edges. Text styles are settings applied to a selected Text layer, not starts; they are not offered by
New overlay or the Templates picker.

The editor's Add menu ends with **From a preset...**, the same grouped list New overlay offers, so a Live badge,
Lower third or a date format can be added to an existing overlay.

Where a preset lands when there is no click point, as fractions of the frame (x, y, width, height). The frame is
1920 x 1080 from New overlay and the Templates picker, which have no background stream, as `U.newPage` assumes today
(`uo-overlay.js:622`); from the editor's "From a preset..." it is the background stream's source size when one is
chosen (`S.preview`), otherwise 1920 x 1080. Shapes centered at 20% of the width with their natural aspect;
Live badge top left (0.04, 0.05, 0.10, 0.06); Lower third (0.05, 0.75, 0.50, 0.14); Title card centered (0.15, 0.40,
0.70, 0.20); Ticker strip along the bottom (0, 0.92, 1, 0.08); date and time presets as a right-aligned text box
(`box: true`, `align: right`) at (0.65, 0.05, 0.30, 0.07); Corner bug (0.86, 0.05, 0.10, 0.10). All text presets are
`box: true`, so their position is their top-left corner. A plain kind placed from the Add bar takes its position
from the click or drag instead.

- **Shapes:** the eight in the Add menu.
- **Badges and bars** (a Text layer with its box): Live badge (white bold "LIVE" on a red rounded box, blinking every
  second if wanted), Lower third (name over role on a dark bar, two lines in one box and one size; a smaller role line is a second
  layer), Title card, Ticker strip.
- **Corner bug:** an Image layer in the top right at 10% of the width, waiting for a file; it saves without one, draws
  nothing and shows "No image chosen" on its row.
- **Date and time formats**, each with a sample in the list: `HH:mm:ss`, `h:mm:ss a`, `HH:mm`, `yyyy-MM-dd`,
  `EEE, MMM d`, `MMMM d, yyyy`, `yyyy-MM-dd HH:mm:ss`, `yyyy-MM-dd HH:mm:ss z` (for recordings), countdown
  `H:mm:ss`, countdown `d'd' HH:mm:ss` (for events days away), countdown `mm:ss`, stream time `H:mm:ss`.
- **Text styles** (top of the Text tab): Title, Lower third name, Lower third role, Caption, Ticker; each sets font,
  size, weight, color and box.

### 4.4 Showing layers on the canvas

- An image layer is boxed at its `w`,`h`, or its native size where they are 0 or less (3.5), not `size()`'s 640 x 360
  default (`uo-overlay.js:36`).
- Each layer is its own element in the `.frame`, stacked by zOrder with the web page `.pg` boxes, so a text layer can
  sit between two pages. Text, shapes and images are drawn in the browser (one small SVG per layer, scaled like
  `position()`,
  `uo-overlay.js:440-446`) for instant feedback while dragging and typing, with the bundled fonts loaded by
  `@font-face`. Text elements set `white-space: pre`, since SVG otherwise collapses runs of spaces and the
  browser's text comes out narrower than the server's (S4). Each SVG layer carries an accessible name (kind and content).
- **What the canvas shows over time:** the canvas is live. Token samples tick once a second from `GET /format`
  results computed ahead (3.7), blink animates, and fades play. Layers outside their show window are drawn faded at
  25% with a "Hidden now, shows at <time>" or "Hidden since <time>" tag, so they stay editable. A layer with a live value shows the live value
  with its badge (4.2a), except while that layer is selected for editing: then the canvas draws all its saved values,
  and the panel says which fields are live ("Live text and color are on air instead of these") next to Clear. A "Freeze" toggle stops time for editing.
- 600 ms after the last edit the page builds a comparison image, not a copy of the visible canvas: every layer the
  server would draw at one frozen `now` (including layers hidden on the canvas, excluding layers
  outside their show window), saved (not live) values, no tags or handles, web page layers masked out. It asks for a
  draft render (3.8) with the same `now`, phase and a stream start equal to `now` (so stream time and `stream+N`
  read zero in both), and compares the two by drawn boxes, not pixels (antialiasing differs on every glyph edge even when
  text looks the same, S4): each text layer's advance width within 3% and each edge of its drawn box within 8 px at
  source scale, shapes and images within 2 px. Within that it shows "Matches the server's drawing"; otherwise "Differs from the server's
  drawing" in words, with a toggle to show the server's. It says "server's drawing", not "video", because output
  scaling is not part of the check. The result is announced through the existing polite live region.
- Read-only users get no check: the panel says "Server check needs write access".
- Web pages keep their capture images (`startCaptures`, `uo-overlay.js:268-281`).

### 4.5 Templates tab

- The Decode and Encode Overlays sections (`overlaysHtml`, `ui/js/uo-template.js:456-469`) get one **Add** menu with
  the kinds the request names: Web page, Text, Date and time, Shape, Image.
- **Web page, Text, Date and time, Shape**, and **Image in an overlay** under Image, all open the same overlay picker,
  filtered to that kind:
  - Saved overlays of the application shown in the header that contain the kind, with a thumbnail (`GET .../thumbnail`, 3.8;
    a web page layer shows its current frame only when a capture is open, otherwise a gray box with its host).
    Thumbnails load one at a time with a placeholder, and a 429 waits and retries. Each entry lists what is in the
    overlay. An overlay holding a clock and a bar is found
    under Text, Date and time and Shape. An "All" filter shows every overlay.
  - The presets from 4.3 for that kind, under "New from a preset" (for Web page, "New web page overlay", which opens
    New overlay's One web page start). Picking one does not add it to the template at
    once. It opens **New overlay** (4.1) with that preset chosen and a name suggested; Create overlay writes it in the
    application shown in the header and opens the editor, where the placeholder text ("Name", "Role") is edited. The
    editor shows a banner "Adding to template <t>" ("Adding to the new template" for an unsaved new template, whose
    draft is stashed under `''`, `uo-template.js:145`, `:157`, and which the return opens as `template:`) with
    **Add to template and go back**. The template is left unsaved with the overlay added, for the user to Save. The
    button saves the overlay if needed (a refused save or a conflict keeps the user in the editor with the error, as Save does), returns to the
    template, and adds the overlay to the stage the user picked from. For in-place and overlay-only templates it is
    added to that form's overlay list (`S.form`, `uo-template.js:932-953`) on all outputs, for the user to narrow. The template draft
    survives the trip (`stash()` keeps it in `mem`, `uo-template.js:144-149`; `select()` restores it and flags a
    conflict if the template changed meanwhile, `:166-169`). Leaving the editor another way adds nothing to the
    template; the overlay stays saved. A taken name is refused on New overlay, as today.
  - A new template's `d.overlays` starts **empty**: `blank()` and `copyOf()` ("New master from this one",
    `uo-template.js:876-882`, called at `:1097`) stop pre-filling the application's first overlay (`:78-80`, `:157`),
    so returning to an unsaved new template adds only the chosen overlay. `U.templateSpecFor` (`:84-88`), used by the
    four-step task, sets the outputs first and then builds one entry for its overlay in Decode on every video output,
    as today's `urlStage` does. The "Original + overlay copy" preset (`:66-67`) gets a hint, "Add an overlay for the
    overlay output", and `problem()` (`:825`) flags it while the preset is still in effect and its overlay output
    (by the preset's output index) is in no entry's `outputs`. Any output edit, add or removal already clears the
    preset (`d.preset = null`, `uo-template.js:683`, `:696`, `:771`), which ends the check. For this, a Decode entry's
    `outputs` always lists every video output.
  - Overlays the template already carries are shown disabled in the picker, "Already in this template".
  - Read-only users see no Add menu at all (so no "New from a preset"; New overlay would send them home anyway,
    `uo-overlay.js:1126`), and no Copy to other applications.
  - "Combine several layers in one overlay in the Overlays tab" as the hint, because a template has four overlay
    slots and one overlay can hold up to 64 layers.
- **Image** offers two entries: **Image (fixed)**, WSE's own image layers (`imageCard`, `:439-454`), up to eight,
  drawn by WSE with no timing; and **Image in an overlay**, the picker above filtered to overlays with Image layers
  (including the Corner bug preset). A tip under the menu says which to use when; on stacking it keeps today's
  "fixed images draw under the overlay" (`uo-template.js:421`) for the same stage, which S1 confirmed, and adds that
  anything on an output draws over everything on the input.
- **Data for the picker:** `GET .../overlays` (`M/rest/OverlaysConfig.java:42`, today only a page count) adds
  `kinds: [...]`, `imageFiles: [...]`, and `hash` per overlay (of the overlay as `OverlayStore` would write it, keys
  sorted, so a hand-edited file and its copy match); the list today carries
  name, pages, modified, templates and showingOn (`OverlaysConfig.java:35-51`). `kinds` uses a fixed vocabulary, `webPage`, `text`,
  `datetime`, `shape`, `image`: a `string` reports `text`, plus `datetime` when it holds a token; rectangle, ellipse, line and triangle report `shape`; unknown types report nothing. A module test
  covers the mapping.
- **Overlays are per application, templates are per virtual host.** Which applications load a template cannot be read
  reliably (an application's `<Templates>` is often a pattern such as `${SourceStreamName}.xml`, as `AppScanner`
  returns it, `M/templates/AppScanner.java:54`). So the overlay card names the overlay as it exists in the application in
  the header, and offers **Copy to other applications**: a list of every application with the module
  (`/vhosts/{vh}/applications`), each checked with `GET .../applications/{app}/overlays` and marked "has it",
  "differs" or "missing" by comparing `hash`. Copying is a GET from one application and a POST to the other; copying
  over "differs" asks first, and says so when the overlay is on air there, because the POST reapplies it to running
  streams at once (`NamedOverlayConfig.java:46`). Image files are shared by the virtual host, so a copy needs no image
  transfer. Hidden for read-only users (`[data-write]`). Today a missing overlay shows "does not exist"
  (`uo-template.js:434`); overlays are saved per application (`USAGE.md:53`). The image library's delete warning checks
  `imageFiles` across the same applications.
- **Data model:** the page's single `d.overlay`, `d.urlSome` and per-output `o.overlay` flag (`uo-template.js:412-417`,
  `placeUrl` `:496-500`) become a list `d.overlays = [{name, stage, outputs}]`, each with its own Decode or Encode
  stage and output pills. The "one video output goes in Decode" rule (`:463`) applies per overlay. Overlay cards get
  move up and down like image cards, within their stage only (Decode overlays always draw under Encode ones), which
  sets slot order (3.9).
- The "one URL overlay per template" text and rule (`:413-421`, `:463-466`) become "up to four overlays", and `problem()`
  (`:825`) checks each overlay has an output.
- Cards show the kind icons and the thumbnail instead of only the name.
- **Every template kind, not only the builder** (`select`, `uo-template.js:150-174`):
  - **In-place and overlay-only** (`renderOverlayOnly`, `:932-953`): the same overlay list and Add menu, saved through
    `POST /templates/{t}/overlay` with `overlays: [...]` (3.9).
  - **Other templates and "Use it with an overlay"** (`renderOther`/`useHtml`, `:859-928`; outputs tables `:852`,
    `:866`): "Carries" lists every overlay with its outputs.
  - **Copies** (`renderCopy`, `:836-846`): show the master's overlays, as today for one.
  - **VIF**: unchanged, still not offered.

### 4.6 Other screens and names

- The four-step task (`ui/js/uo-task.js`) stays a web page flow in 2.0.
- Every screen that reads one overlay per stream or template reads the `overlays` list (3.9): Streams, including the
  template-switch confirm (`ui/js/uo-stream.js:50`, `:226-278`); On air (`ui/js/uo-home.js:24-34`); the left list
  (`ui/js/uo-shell.js:185`, `:220`); and the Overlays tab's "On air on" badge (`onAirHere`, `uo-overlay.js:403`). Each
  lists every overlay with kind icons.
- Product-name strings on these screens follow section 5 (for example the VIF note, `uo-template.js:262`).

## 5. Name: Wowza Overlay Studio, everywhere

Decided by Alex on 2026-10-31: the name is **Wowza Overlay Studio** (Manager menu "Overlay Studio"), applied to every
identifier, with no migration from 1.x (PM vault: `60-decisions\2026-10-31-url-overlay-2-0-name-wowza-overlay-studio.md`,
`60-decisions\2026-10-31-overlay-studio-2-0-full-rename-no-1x-compatibility.md`). Only two 1.x installs exist, both
test machines.

### What is renamed

| Today (1.x) | 2.0 |
|---|---|
| Java packages `com.wowza.wms.plugin.urloverlay.*` (module, capture, shared, installer) | `com.wowza.wms.plugin.overlaystudio.*` |
| Bridge package `com.wowza.wms.webapp.urloverlay` (`I/ManagerBridge.java:32`) | `com.wowza.wms.webapp.overlaystudio` |
| Classes `UrlOverlayModule`, `UrlOverlayServer`, `UrlOverlayBridge`; module `<Name>UrlOverlayModule</Name>` and `<Description>URL Overlay</Description>` (`shared/.../ConfigEdits.java:18-19`, `:93-94`) | `OverlayStudioModule`, `OverlayStudioServer`, `OverlayStudioBridge`; `OverlayStudioModule`, "Overlay Studio" |
| REST base `/v1/{server}/plugin/urloverlay` (29 resources) | `/v1/{server}/plugin/overlaystudio` |
| Manager bridge path `/enginemanager/urloverlay-api` (`ui/js/uo-core.js:14`) | `/enginemanager/overlaystudio-api` |
| Server.xml and Application.xml properties `UrlOverlay*` (`UrlOverlayServer`, `UrlOverlayCaptureSocket`, `UrlOverlayAllowHosts`, `UrlOverlayIndex`, ...) | `OverlayStudio*`, same suffixes. Not `Overlay*`: `OverlayServer` and its siblings belong to wse-plugin-overlays, which VIF installs |
| Template properties `urlOverlay`, `urlOverlayEncodes`; marker "Managed by URL Overlay" (`TemplateValidator.java:30-35`) | `overlayStudio1` to `4`, `overlayStudioEncodes1` to `4` (3.9); "Managed by Overlay Studio" |
| `conf.modules/urloverlay` (`M/templates/ModuleFiles.java:17`), install manifest inside it | `conf.modules/overlaystudio` |
| Image uploads `content/urloverlay/` (`M/templates/ImageLibrary.java:33`) | `content/overlaystudio/` |
| Capture service `WowzaURLOverlayCapture`, account `wse-url-overlay` (`I/CaptureSetup.java:36-37`) | `WowzaOverlayStudioCapture`, `wse-overlay-studio` |
| Windows program folder `Program Files\Wowza URL Overlay Service` (`I/CaptureSetup.java:73`); service and Chrome update descriptions (`I/CaptureSetup.java:380`, `:464`; `I/ChromeUpdate.java:132`, `:147`, `:169`) | `Program Files\Wowza Overlay Studio Service`; descriptions naming Overlay Studio |
| Linux `/opt`, `/etc`, `/var/lib`, `/run`, `/var/log` folders `wse-url-overlay`; AppArmor `wse-url-overlay-chrome` (`I/CaptureSetup.java:80-83`, `:429-432`) | `wse-overlay-studio`; `wse-overlay-studio-chrome` |
| Windows `ProgramData\WowzaURLOverlay` (`I/CaptureSetup.java:74`); Chrome update task and timer `WowzaURLOverlayChromeUpdate` (`I/ChromeUpdate.java:45`) | `ProgramData\WowzaOverlayStudio`; `WowzaOverlayStudioChromeUpdate` |
| Manager plugin folder `wse-plugins/server/url-overlay` (`I/Wse.java:276-278`); menu "URL Overlay" (`ui/config.json:3`) | `wse-plugins/server/overlay-studio`; "Overlay Studio" |
| Page: `window.UrlOverlay`, `localStorage` prefix `urloverlay.`, `url-overlay.css`, `uo-*.js`, CSS prefix `uo-` | `window.OverlayStudio`, `overlaystudio.`, `overlay-studio.css`, `ovs-*.js`, `ovs-` |
| Jars `wse-plugin-url-overlay`, `wse-plugin-url-overlay-installer`, `wse-url-overlay-capture`; Gradle project and Maven group | `wse-plugin-overlay-studio`, `wse-plugin-overlay-studio-installer`, `wse-overlay-studio-capture`; `wse-overlay-studio`, `com.wowza.wms.plugin.overlaystudio` |
| `APP_NAME` "Wowza URL Overlay", `DEB_NAME` `wse-url-overlay`, launchers "Uninstall URL Overlay" and `urloverlay-cli` (`packaging/build_dist.py:25`, `:35`, `:228-229`), and the names in the setup program's custom actions: the launcher at `packaging/windows/resources/main.wxs:109`, `urloverlay-cli.exe` at `:114-115`, the ids `UoRunInstaller` and `UoRunUninstaller` | "Wowza Overlay Studio", `wse-overlay-studio`, "Uninstall Overlay Studio", `overlaystudio-cli`, `OsRunInstaller`, `OsRunUninstaller` |
| Windows `UPGRADE_UUID` (`build_dist.py:34`) | **A new UUID**, so the 2.0 setup program never treats 1.x as an earlier version of itself (it detects 1.x separately, below) |
| Thread names, log prefix `UrlOverlayModule:`, the `UrlOverlayLog` class, installer log `urloverlay-install-*.log`, backup and probe suffix `.urloverlay-` (`I/Installer.java:830`, `I/Preflight.java:37`) | `OverlayStudio...`, `OverlayStudioLog`, `overlaystudio-install-*.log`, `.overlaystudio-` |
| Starter templates `urloverlay-cpu`, `urloverlay-gpu-eva`, `urloverlay-passthrough` (`M/templates/TemplateFiles.java:41`, `module/src/main/resources/starters/`) | `overlaystudio-cpu`, `overlaystudio-gpu-eva`, `overlaystudio-passthrough` |
| Script names `UO_JAVA`, `UO_JAR`, `UO_DIR`, `uo_try`, `uo_v` (`packaging/linux/find-java.sh:7-31`, `packaging/windows/find-java.cmd`) | `OVS_JAVA`, `OVS_JAR`, `OVS_DIR`, `ovs_try`, `ovs_v` |
| Page theme attribute `data-uo-theme` and CSS variables `--uo-*` (`ui/css/url-overlay.css:814`, `ui/js/uo-shell.js:443`); the capture service's `-Durloverlay.egress.trace` (`capture/.../EgressGuard.java:39`) | `data-ovs-theme`, `--ovs-*`; `-Doverlaystudio.egress.trace` |
| The bridge's identity in its status answer, `"bridge":"urloverlay"` (`bridge/.../UrlOverlayBridge.java:76`), which the page checks (`ui/js/uo-core.js:26`) | `"overlaystudio"`, on both sides |
| Tests that name files or packages. Only `tests/fixtures/` is on GitHub `main`; `tests/ui/labels_test.py` (which names `uo-fields.js` and the package, `:24-25`) is ported from the local checkout in PR 3 under the new names. The local spikes, oracle, mutants, perf and integration folders are not ported | the new file names and package |
| Documents shipped in every package (`README.md`, `THIRD-PARTY-NOTICES.md`, and `docs/USAGE.md`, `docs/DESIGN.md`, `docs/BUILD.md`, which hold every old path, property and command) | Renamed mechanically in PR 2 so they stay true at every step; the 2.0 content arrives in PR 12 |
| GitHub repository `alex-chepurnoy/wse-url-overlay` | `alex-chepurnoy/wse-overlay-studio` (GitHub redirects the old URL; no shipped code names it) |

The rename is mechanical and lands first (PR 2), so every later pull request is written with the new names. The local
working folder `C:\builds\wse-url-overlay` and the vault's `45-builds\url-overlay-module\` keep their names; renaming
them would break links and is not part of the product.

### 2.0 refuses to install over 1.x

2.0 does not migrate, so the two must never be installed together: both would add a module to the same applications
and draw into the same transcoder slots.

**The 1.x check** looks for any of: `lib/wse-plugin-url-overlay-*.jar`; the `UrlOverlayServer` listener in
Server.xml; a `UrlOverlayModule` entry in any Application.xml; the Manager plugin folder
`wse-plugins/server/url-overlay`, in either location 1.x uses (the persistent `manager/wse-plugins/server/` or the
fallback `manager/temp/webapps/enginemanager/wse-plugins/server/`, `I/Wse.java:274-278`), or the 1.x bridge under
`manager/temp` (`I/ManagerBridge.java:32`); the
`WowzaURLOverlayCapture` service; on Linux the `wse-url-overlay` package in dpkg's **installed** state only (`ii`, not
a removed-but-not-purged `rc`). 1.x's install manifest is **not** a signal: its uninstall can leave it behind when a
template was kept or edited (`I/Installer.java:427-436`, `:575-578`, `:784-788` on 1.15.1), and it would then block 2.0
forever; the jar, listener and module entry cover a live 1.x. Message: "URL Overlay 1.x is installed (found: <path or name>). Uninstall it first
(README, Before you install). Overlay Studio does not read its overlays or templates." Naming what was found matters
because 1.x's capture uninstall reports undeletable paths and carries on (`CaptureSetup.java:1121-1137` on 1.15.1), so a
folder can outlive 1.x; README's Before you install says to delete such a leftover folder by hand.

Where each package runs it, so that a refusal really changes nothing:

| Package | Where the check runs | What the user gets |
|---|---|---|
| Zip, tarball | The installer's preflight, before any change | Exit code 9 and the message. 9 is free: the installer uses 0 to 7 and the 1.15.1 scripts 8 (`README.md:134`, Exit codes, on 1.15.1) |
| `.deb` | `Conflicts: wse-url-overlay` first: dpkg itself refuses while the 1.x package is installed, before any script runs (what apt does instead, refuse or offer to remove 1.x, is **unverified** and recorded by the Ubuntu test in 7). Then a new shell `preinst`, which runs before dpkg unpacks anything (the Java installer is not unpacked yet, and `postinst` runs it after unpacking and always exits 0, `packaging/linux/deb/postinst:6-15`, so the check cannot live there). `preinst` checks, without needing to know which WSE is meant: the `WowzaURLOverlayCapture` systemd unit, `/opt/wse-url-overlay/`, `/etc/wse-url-overlay/`, and in **every** WSE home the shell can find (the same candidates the Java finder uses, `I/Wse.java:45-96`: `WMSAPP_HOME`, `/usr/local/WowzaStreamingEngine` and the versioned `/usr/local/WowzaStreamingEngine-*` folders) `lib/wse-plugin-url-overlay-*.jar`, the `UrlOverlayServer` listener in `conf/Server.xml`, a `UrlOverlayModule` entry in any `conf/*/Application.xml`, and both Manager plugin locations (`I/Wse.java:274-278`). It runs on a fresh install (`install` argument) and on an upgrade of 2.0 itself (`upgrade`), since 1.x could have been added in between. The control text, built inline today (`packaging/build_dist.py:170-178`), moves to a tracked template `packaging/linux/deb/control` so the `Conflicts:` line lives in its own allowed file (7) | dpkg refuses, or `preinst` exits non-zero with the message; nothing is unpacked. Every 1.x install sets up its capture service (the 1.x installer has no option to skip it, `InstallSession.java:464-472` and the options at `Main.java:40-61` on 1.15.1), so the machine-wide signals (the systemd unit, `/opt/wse-url-overlay/`, `/etc/wse-url-overlay/`) catch a 1.x wherever its WSE is, including one installed with `--home` in a folder `preinst` does not scan |
| Setup program (MSI) | Before anything is installed, in silent and UI installs alike, a launch condition over what the MSI can see without knowing a WSE home: 1.x's upgrade UUID through an `<Upgrade>` entry with `<UpgradeVersion OnlyDetect="yes" Minimum="0.0.0" IncludeMinimum="yes">` (`6f0b6b8e-...`, `build_dist.py:34` on 1.15.0, `:36` on 1.15.1), a `RegistrySearch` with `Name="ImagePath" Type="raw"` on the `WowzaURLOverlayCapture` service key (`HKLM\SYSTEM\CurrentControlSet\Services\WowzaURLOverlayCapture`; a service key has no default value to read), and a `DirectorySearch` for `ProgramData\WowzaURLOverlay` and for `Program Files\Wowza URL Overlay Service` (`I/CaptureSetup.java:73`). Written `Installed OR NOT (any of them)`, so an installed 2.0 can still be repaired and removed. The setup window runs only after `InstallFinalize` and only when `UILevel >= 4` (`main.wxs:107-109`, `:117-118` on 1.15.0), so it cannot be the first check | The MSI stops with the message (msiexec's failure code 1603 for a silent install); nothing is installed. Every 1.x install, from the setup program or the zip, sets up the capture service and its ProgramData and program folders (the 1.x installer cannot skip it), so the launch condition catches every 1.x before anything is installed. The setup window's and the CLI's own preflights repeat the per-WSE checks as a second line |

Only installing refuses. The 2.0 uninstaller and the daily Chrome update do not run the 1.x check, so 2.0 can always
be removed.
- 1.x data left behind (`conf.modules/urloverlay`, `content/urloverlay`, and templates carrying `urlOverlay`
  properties) is ignored by 2.0. README says how to remove it (1.x's `uninstall --purge`) and that those templates draw
  no overlay until they are rebuilt in Overlay Studio.

### What the docs say

README, USAGE and the release notes open with: "Wowza Overlay Studio 2.0 replaces URL Overlay 1.x and is not
compatible with it. Uninstall URL Overlay first; overlays, templates and settings made with 1.x are not carried over."
README gains **Before you install**, with the 1.x uninstall steps for each package type.

## 6. Spikes before building

Each answers a question the design depends on. Results go to `docs/perf/` or the design doc.

| # | Question | How | Blocks |
|---|---|---|---|
| S1 | Does WSE draw overlay slots 16 to 19 and the 1 to 8 image slots, and in what order? | **Done 2026-11-02** (`docs/perf/2026-11-02-s1-overlay-stacking.md`): native code keeps overlays in a `std::map` by slot and blends in ascending order, so the Java `HashMap` order does not matter; higher slot on top, Encode over Decode; confirmed on video on AWS (CPU) and the local server (NVIDIA EVA). No overlays on NETINT zero-copy frames (source only) | 3.9, 4.5 |
| S2 | What does one redraw cost at 1080p, with four outputs and many streams? Does a boundary draw finish within one frame interval? | **Done 2026-11-02** (`docs/perf/2026-11-02-s2-redraw-cost.md`): a full-canvas redraw is almost all clearing and converting the frame (2.2 ms Windows, 5.3 ms AWS at 1080p, empty or not); a clock's bounding box is 35 to 40 times cheaper; 12 streams on all four outputs finish in 2.5 ms with boxes, 38 to 56 ms on the 8-core server without. Bounding boxes required, no draw-ahead scheme. Under load with the built module (`docs/perf/2026-11-02-s2-under-load.md`, part 4d, 12 streams transcoding, load average 83 on 8 cores): the 12-stream burst finishes in 15.9 ms median, 42.7 ms at worst, 12 ms with small canvases | 3.1 |
| S3 | Do bundled fonts register on headless Linux with WSE's own Java, and do logical fonts resolve there? | **Done 2026-10-31** (`docs/perf/2026-10-31-font-spike.md`): bundled static fonts register and render pixel-identical on Windows and Linux; bold needs a Bold file; variable fonts and italic do not work as hoped; missing characters draw boxes; logical fonts differ by system; on Linux, with no system fonts or no `libfontconfig`, Java draws no text at all, bundled fonts included, unless given its own `fontconfig.properties` | 3.7 |
| S4 | How close is the browser's text to Java2D's for the bundled fonts, on Windows and Linux? | **Done 2026-11-02** (`docs/perf/2026-11-02-s4-browser-vs-java-text.md`): 20 samples, Chrome on Windows and headless Chrome on Linux against Java. Pixel difference is unusable (antialiasing); box and width are. With fractional metrics on: width 0.4% median, 2.9% max; box edges 1 to 2 px median, 7 px max. Java is pixel-identical across systems, so goldens stay exact. SVG needs `white-space: pre` | 3.7, 4.4 tolerance |

## 7. Tests

### Module (JUnit, `module/src/test`)

- Golden images for every kind and option at 1.0 and 0.5 scale (rectangles, rounded, ellipse, circle, line with each
  arrow head, triangle, dash, gradient in 8 directions, shadow, rotation both ways, text in every style and alignment,
  box, wrap, auto-fit, outline, every token mode, image file with contain and stretch). Shapes and text compare
  exactly, with one set of goldens for Windows and Linux: S3 found bundled fonts render pixel-identical on both at the
  same Java build. Tests draw text only with bundled fonts, since Java's logical fonts differ by system.
- Each defect with a visible effect (D1, D3 to D6, D8 to D14; D14 as "an overlay smaller than the source keeps its
  size on the encodes after a save") gets a test that fails on PR 2 (renamed, not yet fixed) first; D11 and D12 with concurrent draw and edit
  threads, including a per-stream PUT during a draw. D2 and D7 (latent) get tests of the rewritten code only.
- Scheduler with a fake clock: one redraw per second for `HH:mm:ss`, per minute for `HH:mm`; the change published
  within one draw of the boundary; a slow draw on one scale does not delay another; cap of 10 per second; blink in
  seconds and the same on every output; display window start and stop; fades; countdown ends with `endText`; DST
  change in a zone with DST; an inline-draw overlay changes on schedule.
- Duration grammar: three-day countdown in `H:mm:ss` and `d'd' HH:mm:ss`, 90 minutes in `mm:ss`, past target shows
  `endText`, count-up before target shows zero; a countdown with no target saves and draws `endText`.
- Tokens: `{time:HH 'o}clock'}` is one token; `{{` and `}}` draw braces; a lone brace and `{time:HH}}` are refused.
- Per target: one encode's late draw still reaches it.
- Page frames per scale: one scale drawing a newer frame never hides it from another, on a page that then stops
  changing. Active scales follow where the overlay goes on each frame: moved from source to encodes, the source scale
  drops out at once and a static page stops redrawing; a web page whose frames come more than 5 s apart (reload rate
  30 s) still shows each new frame on every output.
- Scaling: four overlays with different `w`,`h` on one template each keep their own canvas size and all scale by the
  source height on every encode, before and after a save.
- Publish record: a save racing a publish never leaves a stale image current; a live write between buffer and size
  reads never pairs a buffer with another revision's box; the content revision bumps on live write, per-stream PUT
  and `imageFile` change (a replacement of another pixel size gets a new box) and on nothing else; a save starts a new
  `OverlayImage`. A live write racing a save reaches the new `OverlayImage`.
- Bounding box: a proportional-font clock never clips as its width changes.
- Copy-on-write: a page layer edited during a draw keeps one capture (reference count checked), none opened anew.
- Decoded images: 12 streams on one overlay hold one decoded copy of each image; unreferenced entries are evicted past
  512 MB and referenced ones never are.
- Two shapes with the same gradient colors at different positions each draw their own gradient.
- Several overlays per template: properties `overlayStudio1` to `4` read, slots assigned, a custom
  `OverlayStudioIndex` that would collide refused, reapply after save reaches every slot, adding `overlayStudio2` is
  not a restart, removing a slot clears it everywhere, an overlay added to a running stream gets the transparent fill,
  per-stream REST uses slot one, the same overlay twice refused, `reloadTemplateFile` applies every slot, delete
  refused while any slot names the overlay, `/templates/{t}/use` keeps every slot on a copy and adds the next slot in
  place.
- Live values: `GET .../live` returns current values; clearing one through `POST .../live {"clear": id}` and all
  through `DELETE .../live`; merged at draw, no file rewrite and no new `OverlayImage`, display windows not restarted;
  the sidecar never lists as an overlay, survives a restart and goes with its overlay on delete; two instances of one
  application both update; a renamed id carries its value and the old id answers 404; 429 above the rate.
- Validation: every refused input returns 400 naming the field; ids (`a..b`, a space, a duplicate) refused;
  `renamed` maps; live POST fields; the limits; a font missing on this server saves with a warning.
- Store round trip: every field survives save and load; overlay-level defaults kept; `renamed` never stored; an
  interrupted write leaves the old file.
- `kinds` mapping for every type and for text with a token; an `image` layer with no file saves and draws nothing; a
  `w`,`h` of 0 or absent draws an image at native size.
- Draft render: no capture opened for a web page layer, width capped, frozen `now` and `streamStart` honored, image
  layers drawn from their files.
- Thumbnail of an overlay with a web page that is not on air draws the placeholder and opens no capture.
- `/format`: samples step by at least one second, answers carry the server's default zone and locale.
- Fonts: every bundled family registers and draws in each style; `/fonts` lists them and reports one blocked by a
  system font of the same name; `/fonts/check` reports missing characters and the family Java uses; when font setup
  throws (simulated), the module reports text unavailable and still draws an overlay's other layers.
- Presets: every shape, badge, bar and date and time preset saves without a 400 and draws its expected golden.
- **Rename:** a build check, case-insensitive, fails on `url[- ]?overlay`, `\buo[-_]` or `UO_` in the files `git ls-files` lists (so untracked `build/` output is never scanned), text only (not images or fonts), of the shipped tree:
  `module/`, `capture/`, `shared/`, `installer/`, `bridge/`, `ui/`, `packaging/`, the build files, `tests/` and the
  shipped docs (`README.md`, `THIRD-PARTY-NOTICES.md`, `docs/USAGE.md`, `docs/DESIGN.md`, `docs/BUILD.md`). Two kinds of
  allowance:
  - **Whole files:** the 1.x detector, a dedicated class `LegacyInstallCheck` (not inside `Preflight.java`, so the
    rest of the preflight is still checked), the `.deb` `preinst`, every test and fixture of the 1.x check wherever it
    lives (detector, `preinst`, MSI condition, leftover-folder cases), and the rename check's own source and test
    fixtures. `packaging/linux/deb/control` is checked, with its `Conflicts:` line in a marked block; `build_dist.py`
    strips the marker lines when it reads the template, so none reach the package. (The release notes are not
    in the checked tree.)
  - **Marked blocks** inside other files, between `1x-compat:begin` and `1x-compat:end` comments (in Markdown as HTML
    comments, in XML, Java and scripts as that language's comment): in `main.wxs`, one block around the 1.x
    `<Upgrade>` entry, the `RegistrySearch` and `DirectorySearch` properties and the launch condition (the file is
    static, passed with `--resource-dir`, `build_dist.py:252` on 1.15.1); README's
    compatibility notice, Before you install and leftover-1.x paragraphs; USAGE's notice; DESIGN's description of the
    1.x check. Lines outside a block are checked as usual. Exit code 9's rows in the exit-code tables name no old
    identifier and need no block. History outside the shipped tree
  (numbered plans in `docs/`, `docs/evidence/`, `docs/mockups/`, `HANDOFF.md`, `spikes/`) is not checked.

### Installers

- On a machine with no 1.x: setup program, zip, tarball and `.deb` each install, run and uninstall cleanly on a fresh
  WSE, with every new name in place (services, folders, Server.xml and Application.xml entries, Manager menu).
- With 1.x installed, WSE's files and config stay unchanged in every case:
  - zip and tarball: exit 9 with the message; each signal alone (jar, Server.xml listener, Application.xml module,
    Manager plugin folder in either location, bridge, service, dpkg `ii`) triggers it; a dpkg `rc` entry and a leftover 1.x manifest (an
    uninstall that kept a template) do not;
  - `.deb`: refused by dpkg while 1.x's package is installed; with only the other signals (a 1.x from the tarball),
    `preinst` refuses for each signal it checks, in a WSE home found by each candidate path, and nothing is unpacked;
  - a 1.x tarball installed with `--home` in a WSE folder `preinst` does not scan: `preinst` still refuses, on the
    capture service's machine-wide unit and folders;
  - setup program, silent and with its window: stops before installing for a 1.x from the setup program (upgrade
    UUID) and for a 1.x from the zip (service key, ProgramData folder, program folder);
  - an installed 2.0 can still be repaired and uninstalled after a 1.x appears.
- On Ubuntu with 1.x's package installed, `apt install ./wse-overlay-studio_2.0.0_all.deb`, recording which of two
  outcomes apt takes with the `Conflicts`: it refuses, and WSE is unchanged; or it removes 1.x first, so 1.x's own
  `prerm` runs its uninstall (`packaging/linux/deb/prerm:10-14` on 1.15.1), which fails the removal while WSE runs
  (`uninstall.sh` returns 3 and `prerm` exits 1; then nothing else happens) and otherwise takes 1.x out of WSE, after which 2.0 installs cleanly. README's Before you install
  tells users to remove 1.x themselves with WSE stopped, so neither path surprises them.
- After uninstalling 1.x (with and without `--purge`), 2.0 installs and ignores any leftover `urloverlay` folders and
  `urlOverlay` template properties.
- Windows: the 2.0 setup program never upgrades 1.x (new upgrade UUID), detects it through `OnlyDetect` and stops;
  with no 1.x, silent install and silent uninstall work.

### Page

- **New Playwright harness** with REST mocked by `page.route` (proposed in `docs/10-test-audit.md:380`). It lands in
  its own pull request right after the rename, with smoke flows for today's screens, so the page work after it has a
  net.
- Flows: add each kind from the bar, the list menu and the empty inspector; click, drag and keyboard placement; move,
  resize, rotate, line and triangle handles; Alt+click through overlapping layers and selecting a 1 px line; in-place
  text edit; every panel field; kind change within a geometry; Hide on canvas never saved; undo and redo across kinds;
  snapping on and off; save and conflict; read-only state of every new control; the narrow layout.
- An image layer resized on the canvas draws at that size on video; lock aspect on and off.
- Templates flows: Add menu per kind, picker filters, a mixed overlay found under each of its kinds, a preset opening
  New overlay and adding the overlay only when saved, a taken name refused, per-overlay stage and pills, reorder
  within a stage, four-overlay limit, "Image (fixed)", "Image in an overlay", an overlay missing in another
  application with "Copy to other applications" (over "differs" asking first); the in-place, overlay-only, other, copy
  and VIF kinds with several overlays; a template draft surviving the preset trip through New overlay and the editor,
  and its conflict when the template changed meanwhile; "Add to template and go back", including a refused save that
  stays in the editor, an in-place template, and an unsaved new template (from New and from "New master from this
  one") that ends up with only the chosen overlay; an overlay already in the template disabled in the picker; the
  four-step task's template carrying its overlay in Decode; thumbnails loading in sequence through a 429; read-only
  (no Add menu, so no "New from a preset", and no Copy); the Corner bug saved without a file.
- Other screens: Streams (including the template-switch confirm), On air, the left list and "On air on" with several
  overlays.
- Keyboard: Tab into the canvas with nothing selected selects the front layer; Enter on a shape, image or web page
  moves focus to the panel's first field; Enter or F2 on text starts in-place editing and Escape ends it; Escape on the
  canvas deselects and the next arrow selects the front layer; read-only canvas announces layers and changes nothing;
  an id with `..` refused in the panel.
- Editing a layer that has a live value shows its saved values while selected, with the note naming the live fields.
- Text styles absent from New overlay's preset list, the editor's "From a preset..." and the Templates picker.
- Preset landing positions from New overlay, the Templates picker and the editor's "From a preset..." with and
  without a background stream; "Fit to this stream"; "N layers" counts; every reworded "page" string.
- Overlays tab extras: New overlay's "A preset" start and the prompt before replacing an unsaved New overlay draft;
  "Date and time" opening its Format tab; text styles; Page Up and Page Down selection; the Help shortcut list; live
  canvas (tick, blink, fade, hidden-now layers, Freeze); editable ids and the uniqueness check; every row in 4.2a;
  render errors and 429.
- `tests/ui/labels_test.py` extended to every new field key.
- Bridge (unit tests in `bridge/`, through `forward()`, `OverlayStudioBridge.java`, today `UrlOverlayBridge.java:89`,
  whose body branch is at `:111` and query forwarding at `:266`): `POST .../live` keeps its body; `DELETE .../live`
  (sent as a POST with `?method=DELETE`) is forwarded with no body; `GET /format` and `GET /fonts/check` arrive with
  their queries unchanged.
- Accessibility: axe scan of each screen; keyboard-only pass (place, move, nudge, delete each kind); accessible names
  for SVG layers, kind icons and handles; the render result announced in the live region; "Differs" readable without
  color.
- Light and dark: the harness checks every new panel's text and controls against the contrast rules of the axe scan in
  both themes; screenshots are taken for the docs, not as a test.

### On WSE (local Windows and the AWS Linux server)

Both servers have 1.15.1 today. Each is cleared first: uninstall 1.x with `--purge`, confirm the 1.x services,
folders and WSE config entries are gone, then install 2.0. What mocks cannot prove runs here:

- Each kind on the input and on chosen outputs, checked by the output's HLS and by `SnapshotConfig` JPEGs
  (`M/rest/SnapshotConfig.java`) compared with the draft render.
- The canvas against the draft render on both servers (the S4 tolerance), and a browser in a different zone from the
  server showing the server's time.
- Missing glyphs and a missing font show their warnings.
- On the AWS server, in a private mount namespace that hides the system fonts and `libfontconfig` (as the spike did,
  `spikes/fonts/run-nofontconfig.sh`): WSE starts, a web page and a shape still draw, a text layer draws nothing, and
  `/status` and the page say text is unavailable. The tarball installer's font check prints its install command.
- A live value fed by REST shows on the layer's badge within the poll interval, and its Clear through the Manager
  clears only that layer.
- Four overlays plus eight images on one template, CPU and on a GPU (NVIDIA) template, stacking as S1 found.
- Uninstall and `--purge` of 2.0.
- Performance: CPU and draw time from S2 measured again on the built module, one clock on 1, 4 and 12 streams.
- Soak: two hours with a clock, a blinking badge, a live value fed twice a second and a web page, on four outputs;
  memory, threads and draw time flat. Every HLS segment boundary is checked against the clock it shows, so a skipped
  or repeated second is caught.

## 8. Delivery

Spikes S1 to S4 run first, on the local WSE and the AWS server; their results land as a docs pull request. Then stacked
pull requests from `gh/main`, built in `C:\builds\.prstack`. Each one builds and passes its own tests; "needs" names
the pull requests below it that it depends on.

| # | Pull request | Needs |
|---|---|---|
| 1 | Spike results (docs only) | |
| 2 | The rename (section 5): packages, classes, REST and bridge paths, properties, folders, services, page identifiers, packaging, new Windows upgrade UUID, the 1.x check in all three places (installer preflight with exit code 9; `.deb` `Conflicts` and `preinst`; the MSI launch condition over `OnlyDetect`, the service-key `RegistrySearch` and the two `DirectorySearch` entries), the rename build check, the shipped docs renamed mechanically, and the template property as `overlayStudio1`/`overlayStudioEncodes1` (PR 6 adds 2 to 4), the `.deb` control text moved to the template `packaging/linux/deb/control` with the `Conflicts:` line, and `build_dist.py` (which names each control member, `:170-178` on 1.15.1) adding `preinst` and reading that template, and a short README Before you install section so the refusal message points somewhere real (PR 12 completes it). No behavior change otherwise | |
| 3 | Playwright harness with smoke flows for today's screens, and `tests/ui/labels_test.py` ported from the local checkout under the new names | 2 |
| 4 | Module: defects D1, D4 to D9, D11 to D14, thread safety, content revision, published records and the image lock, redraw scheduler and draw path, 3.3a timing fields with their validation (3.10), removal of `flashRate`, `displayStart`/`displayStop` and `rotate` | 2 |
| 5 | Module: D2, D3, D10 (`rotation`), text fields, tokens and their scheduling, removal of `useDate` and `base64Image` (`ScaledDrawTest.java:56` moves to `imageFile`), shape and image fields; fonts, `/fonts`, `/format`; validation; draft render and thumbnails; live values; `kinds`, `imageFiles` and `hash` on the overlay list | 4 |
| 6 | Module: several overlays per template (`overlayStudio1` to `4`). Responses add `overlays: [...]` and keep sending the single `overlay`/`encodes` (the first slot), and `/overlay`, `/spec`, `POST /templates` and `/use` keep accepting them as slot 1, until PR 11, because the page reads and writes them until then (writes: `ui/js/uo-template.js:84-88`, `:126`, `:1040`, `ui/js/uo-task.js:287`; reads) (`ui/js/uo-stream.js:50`, `:58`, `ui/js/uo-home.js:24-34`, `ui/js/uo-task.js:252`) | 4 |
| 7 | Page: SVG drawing of every kind (token layers show their pattern text until PR 8 adds `/format` samples), the page's `@font-face` font files, Add bar, placement, selection, guides, layer list with plain names, "layer" wording. "Date and time" adds its layer; its Format tab arrives in 8 | 3, 5 |
| 8 | Page: panels per kind (Format tab included), presets and New overlay starts, `/format` samples in panels, rows and the canvas, live canvas, problems (4.2a) | 7 |
| 9 | Page: the render check | 8 |
| 10 | Page: Templates Add menu, picker, thumbnails, New overlay from presets, the editor's "Adding to template" banner, Copy to other applications, the image library's delete warning across applications, overlay list model, every template kind | 6, 9 |
| 11 | Page: Streams, On air, left list, badges and the four-step task (its `already` check and `/use` body, `ui/js/uo-task.js:252`, `:287`) read and write the `overlays` list; the module stops sending and accepting the single `overlay`/`encodes` | 6, 10 |
| 12 | Docs: the 2.0 content of README (with "not compatible with 1.x" and Before you install), USAGE, DESIGN and BUILD (artifact table, exit code 9 in every exit-code list), THIRD-PARTY-NOTICES with the font licenses), screenshots, version 2.0.0, GitHub repository renamed | 11 |

Release when every list in section 7 passes on Windows and Linux. The release notes are written in PR 12 as
`docs/release-notes-2.0.0.md` and pasted into the GitHub release. They open with the compatibility
notice from section 5, then say:

- the new name, and that every identifier changed (REST paths, properties, folders, services, packages), so scripts
  and settings written for 1.x must be redone;
- how to uninstall 1.x first, for each package type, and that the installers refuse to run while it is installed;
- the release file list, now `Wowza-Overlay-Studio-2.0.0.exe` (the setup program's name without spaces, as 1.15.1 already does), `wse-overlay-studio-2.0.0-windows.zip`,
  `wse-overlay-studio-2.0.0-linux.tar.gz` and `wse-overlay-studio_2.0.0_all.deb`;
- what is new (section 2) and the limits (64 layers, 2,000 characters, 1 MB overlay file, image pixel budget,
  overlay-level `w`,`h` at most 8192);
- that the server check in the Overlays tab needs write access;
- that the GitHub repository moved (the old URL redirects).

## 9. Decisions

Decided by Alex on 2026-10-31 (PM vault: `60-decisions\2026-10-31-overlay-studio-2-0-plan-decisions.md`):

1. **Templates:** an Add menu by kind opening one filtered overlay picker, with presets opening New overlay so the
   text is edited before it goes on air (4.5).
2. **Presets** are single layers; no multi-select or sub-groups inside an overlay in 2.0. The overlay itself is the
   group of layers.
3. **Four** module overlays per template (3.9), unless spikes S1 or S2 show a reason for another limit.
4. **Four-step task** stays web pages only in 2.0.
5. **`base64Image` is removed**; images always come from uploaded files (3.5, 3.10, PR 5).
6. **Bundled fonts** (3.7): Fira Sans, Fira Sans Condensed, Fira Mono and PT Serif, chosen after spike S3 tested both
   candidate sets (`docs/perf/2026-10-31-font-spike.md`; PM vault:
   `60-decisions\2026-10-31-overlay-studio-2-0-bundled-fonts.md`).
