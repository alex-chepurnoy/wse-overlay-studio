# Building URL Overlay

## Prerequisites

| Tool | Why |
|---|---|
| JDK 21 with `jpackage` and `jdeps` on the PATH | Gradle runs on it; the capture service and the bridge are compiled for Java 21, the module and installer for Java 17. `jpackage` and `jdeps` build the Windows setup program |
| A WSE install, or a copy of its `lib/` folder | The module compiles and tests against WSE's own jars (never packaged). `module/build.gradle` looks for them only when a task needs the module's classpath (its compile, tests and jar, and so the installer), so the capture service and the bridge build without WSE. Found in this order: `-PwseLibDir=<path>`, the `WSE_HOME` environment variable (`$WSE_HOME/lib`), `/usr/local/WowzaStreamingEngine/lib`, then the single install under `C:\Program Files\Wowza Media Systems\`. Several Windows installs and no override stop the build |
| WiX 3.14.1, Windows only | For the setup program. Unpack `wix314-binaries.zip` (WiX 3.14.1 release) into `tools/wix/3.14.1/` so that `tools/wix/3.14.1/candle.exe` exists. `tools/` is not in the repository |
| Python 3.12 or later | Runs `packaging/build_dist.py` (standard library only) |

Gradle itself comes from the wrapper (`gradlew`, `gradlew.bat`, Gradle 8.10). Every other dependency comes from Maven
Central: JUnit 5, and the Spring and Jakarta APIs the bridge compiles against. The capture service has no third-party
libraries: it drives Chrome over DevTools with the JDK's own WebSocket client.

## Build the packages

```
python packaging/build_dist.py [--skip-tests] [--skip-exe] [--maintainer NAME]
```

| Flag | Effect |
|---|---|
| (none) | Builds the installer jar, runs `:module:test`, `:installer:test`, `:capture:test` and `:bridge:test`, then builds every package |
| `--skip-tests` | Builds without running those tests |
| `--skip-exe` | No Windows setup program, so no `jpackage` or WiX needed (the way to build on Linux) |
| `--maintainer NAME` | The `Maintainer` field of the `.deb` (default `Wowza Media Systems`) |

The version comes from `version` in `gradle.properties`. Output goes to `dist/wse-url-overlay-<version>/`, which is
deleted and rebuilt on every run:

| File | What it is |
|---|---|
| `Wowza URL Overlay-<version>.exe` | Windows setup program: the installer with its own Java 21 runtime, Start menu entries **Wowza URL Overlay** and **Uninstall URL Overlay**, and the console launcher `urloverlay-cli.exe` |
| `wse-url-overlay-<version>-windows.zip` | Installer jar, `install.cmd`, `uninstall.cmd`, `update-chrome.cmd`, `find-java.cmd`, docs |
| `wse-url-overlay-<version>-linux.tar.gz` | Installer jar, `install.sh`, `uninstall.sh`, `update-chrome.sh`, `find-java.sh`, docs |
| `wse-url-overlay_<version>_all.deb` | Installs to `/opt/wse-url-overlay/`; `postinst` runs `install.sh --yes`, `prerm` runs `uninstall.sh --yes` on removal |
| `README.md`, `THIRD-PARTY-NOTICES.md` | Copies of the repository files |
| `SHA256SUMS` | SHA-256 of every file above |

Every package carries `README.md`, `THIRD-PARTY-NOTICES.md`, `LICENSE.txt` and `licenses/apache-2.0.txt`. The zip,
tarball and `.deb` use fixed entry times, so the same installer jar gives byte-identical archives; the setup program
does not. The `.deb` is assembled by the script (ar and tar), so `dpkg-deb` is not needed.

The installer jar, `installer/build/libs/wse-plugin-url-overlay-installer-<version>.jar`, carries everything it
installs: the module jar, the plugin page, the starter templates, the bridge's class files and the capture service with
its libraries. The setup program's runtime is linked with the modules `jdeps` finds in the capture service, plus `java.base`, `java.desktop`, `java.net.http` and `jdk.crypto.ec`; the build fails if one is missing.

## Run the tests

```
./gradlew :module:test
./gradlew :capture:test
./gradlew :bridge:test
./gradlew :installer:test
./gradlew build                 # all of the above, plus the jars
```

On Windows use `gradlew.bat`. `build_dist.py` runs all four unless `--skip-tests` is given. What the tests cover, and
what they do not, is in [DESIGN.md](DESIGN.md#testing).

`./gradlew :installer:setupScreenshots` paints the setup window's screens with sample data into `docs/images/`.

## Project layout

| Folder | Holds |
|---|---|
| `module/` | The WSE module: server listener, application module, REST resources, template builder, starter templates |
| `bridge/` | The Manager bridge: one Spring controller, compiled against the Manager's Spring 6 and Jakarta Servlet 6 |
| `capture/` | The capture service: Chrome over DevTools, shared between pages, the egress proxy, the socket server |
| `shared/` | Source compiled into more than one jar: the capture protocol, egress rules, browser pool, byte-preserving config edits |
| `installer/` | The installer jar and the Windows setup window; install, uninstall, verify and Chrome update |
| `ui/` | The plugin page served by WSE Manager: HTML, CSS, JavaScript, and its own copy of hls.js |
| `packaging/` | `build_dist.py`, the Windows and Linux wrapper scripts, `.deb` scripts, the WiX source |
| `tests/` | Fixtures the installer tests use (copies of stock WSE configuration) |
| `docs/` | Usage, design, this file, and the images they use |
| `gradle/` | The Gradle wrapper |

## Installing on Windows

The setup program installs itself to `%ProgramFiles%\Wowza URL Overlay`; the capture service and its Chrome go to
`%ProgramFiles%\Wowza URL Overlay Service` (`capture\`, `chrome-for-testing\<version>\`). When it finishes, the setup program opens the setup window,
which asks Windows for administrator rights itself. Stop the Wowza Streaming Engine service first.

**1. Choose the WSE install.** Every install found is listed; **Browse** finds one that is not.

![Choose your Wowza Streaming Engine](images/setup-1-install-folder.png)

**2. Choose the applications.** Live applications can be ticked; VOD applications are listed but cannot show an
overlay. None is required: URL Overlay can be added to an application later from the plugin page.

![Applications](images/setup-2-applications.png)

**3. Chrome.** Download Chrome for Testing from Google, use a Chrome already on the computer, or skip (no page is
drawn until Chrome is set up).

![Google Chrome](images/setup-3-chrome.png)

**4. Review.** A summary, then every change file by file. **Restart WSE Manager now** loads the bridge at once; people
signed in to the Manager are signed out.

![Ready to install](images/setup-4-review.png)

**5. Progress.** Each step as it runs. A failure rolls back what the run changed.

![Installing](images/setup-5-progress.png)

**6. Done.** What to do next, whether the capture service is running, and where the run's log is.

![Done](images/setup-6-done.png)

**Removing.** **Uninstall URL Overlay** in the Start menu lists every file it puts back or deletes, with options to
keep the starter templates or also delete the saved overlays. Windows Settings, Apps, **Wowza URL Overlay**,
Uninstall removes the setup program and runs the same removal without prompts. Stop WSE before removing.

![Remove URL Overlay](images/setup-remove.png)
