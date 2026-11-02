# Third-party notices

Overlay Studio for Wowza Streaming Engine ships the components below. Each is used unmodified.

## Shipped in every package

| Component | Version | License | Where it goes |
|---|---|---|---|
| hls.js | 1.6.16 | Apache License 2.0 | `manager/wse-plugins/server/overlay-studio/vendor/` |
| Fira Sans (Regular, Bold, Italic), Fira Sans Condensed (Regular, Bold, Italic), Fira Mono (Regular, Bold) | From `github.com/google/fonts`, `ofl/`, taken 2026-10-31 | SIL Open Font License 1.1. Copyright (c) 2012-2015, The Mozilla Foundation and Telefonica S.A. (Fira Mono: 2012-2013, The Mozilla Corporation and Telefonica S.A.) | Inside the module jar, `fonts/`, with each license |
| PT Serif (Regular, Bold) | From `github.com/google/fonts`, `ofl/`, taken 2026-10-31 | SIL Open Font License 1.1. Copyright (c) 2010, ParaType Ltd. | Inside the module jar, `fonts/`, with its license |

The fonts are used unmodified and are not sold on their own; each license's full text is in the jar beside the font
files (`fonts/firasans-OFL.txt`, `fonts/firasanscondensed-OFL.txt`, `fonts/firamono-OFL.txt`, `fonts/ptserif-OFL.txt`).

The full Apache License 2.0 text is in `licenses/apache-2.0.txt`, and beside hls.js, with its NOTICE, in
`vendor/hls.js-LICENSE.txt`.

### NOTICE: hls.js

```
Copyright (c) 2017 Dailymotion (http://www.dailymotion.com)

src/remux/mp4-generator.js and src/demux/exp-golomb.ts implementation in this project
are derived from the HLS library for video.js (https://github.com/videojs/videojs-contrib-hls)
That work is also covered by the Apache 2 License, following copyright:
Copyright (c) 2013-2015 Brightcove
```

## Shipped in the Windows setup program only

| Component | Version | License |
|---|---|---|
| Microsoft Build of OpenJDK, the modules the installer and the capture service use, linked by jpackage | 21.0.11 | GNU General Public License v2 with the Classpath Exception |

Its license and notices are installed with it, in `runtime\legal\` under the program folder. The setup program's
custom action library (`wixhelper.dll`) is part of the same JDK. WiX Toolset 3.14.1 builds the setup program and
none of it is shipped.

## Downloaded at install time, not shipped

| Component | Source | Terms |
|---|---|---|
| Chrome for Testing (Windows) and chrome-headless-shell (Linux) | `storage.googleapis.com`, version from `googlechromelabs.github.io/chrome-for-testing` | Google's terms for Chrome for Testing. Skip the download with `--no-download` and `--chrome` to use your own |

## Used from Wowza Streaming Engine, not shipped

Jackson, Restlet, Joda-Time, Commons Lang and Log4j come with WSE. On Windows the installer copies WSE's own
`bin\nssm_x64.exe` into the capture service's program folder, as `nssm.exe`, to run the service.
