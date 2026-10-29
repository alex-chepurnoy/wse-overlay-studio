# Third-party notices

URL Overlay for Wowza Streaming Engine ships the components below. Each is used unmodified.

## Shipped in every package

| Component | Version | License | Where it goes |
|---|---|---|---|
| Selenium (api, chrome-driver, chromium-driver, http, java, json, manager, os, remote-driver, support) | 4.21.0 | Apache License 2.0 | Capture service `lib/` |
| Failsafe | 3.3.2 | Apache License 2.0 | Capture service `lib/` |
| Apache Commons Exec | 1.4.0 | Apache License 2.0 | Capture service `lib/` |
| Guava | 33.2.0-jre | Apache License 2.0 | Capture service `lib/` |
| hls.js | 1.6.16 | Apache License 2.0 | `manager/wse-plugins/server/url-overlay/vendor/` |

The full Apache License 2.0 text is in `licenses/apache-2.0.txt`.

`selenium-manager-4.21.0.jar` contains the Selenium Manager programs for Linux, macOS and Windows, also under the Apache
License 2.0. The capture service hands Selenium the chromedriver path itself.

### NOTICE: Apache Commons Exec

```
Apache Commons Exec
Copyright 2005-2024 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).
```

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
| Chrome for Testing and chromedriver | `storage.googleapis.com`, version from `googlechromelabs.github.io/chrome-for-testing` | Google's terms for Chrome for Testing. Skip the download with `--no-download` and `--chrome`/`--chromedriver` to use your own |

## Used from Wowza Streaming Engine, not shipped

Jackson, Restlet, Joda-Time, Commons Lang, Log4j and OpenTelemetry come with WSE.
