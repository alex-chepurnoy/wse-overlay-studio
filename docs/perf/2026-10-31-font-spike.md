# Font spike (2.0 plan, spike S3): bundled fonts in WSE's own Java

Run 2026-10-31 on both test servers with WSE's own Java, Temurin 21.0.3, `-Djava.awt.headless=true`:

- Windows 11, local WSE 4.12.0 (`jre\bin\java.exe`)
- Ubuntu on AWS, WSE at `/usr/local/WowzaStreamingEngine` (`java/bin/java`); headless is Java's default there anyway

Harness: `spikes/fonts/FontSpike.java`. Raw output: `spikes/fonts/result-windows.txt`, `spikes/fonts/linux/result-linux.txt`;
rendered samples in `spikes/fonts/out-win/` and `spikes/fonts/linux/out/`.

Fonts tested, all SIL Open Font License, from `github.com/google/fonts` (`ofl/`), in two runs:

- Barlow and Barlow Condensed (Regular, Bold), PT Serif Web (Regular, Bold), IBM Plex Mono (Regular, Bold), and Inter
  as a variable font (`Inter[opsz,wght].ttf`), 2.3 MB.
- Fira Sans and Fira Sans Condensed (Regular, Bold, Italic), Fira Mono (Regular, Bold), 3.2 MB. Raw output
  `result-windows-fira.txt` and `linux-fira/result-linux-fira.txt`, samples in `out-win-fira/` and `linux-fira/out/`.

## Results

| Question | Answer |
|---|---|
| Do bundled TrueType files load and register under WSE's Java, headless, on both systems? | Yes. `Font.createFont` takes 0.1 to 0.4 ms a file; `registerFont` returns true for every file |
| Does `new Font(family, Font.BOLD, size)` pick the bundled Bold file? | Yes, when Regular and Bold are registered as separate static files (`Barlow` bold resolves to "Barlow Bold") |
| Italic? | With no italic file registered, `ITALIC` silently draws the Regular face, upright. Nothing is synthesized |
| A variable font (Inter)? | Java uses only its default instance. `BOLD` keeps "Inter Regular" and only widens the advance (a faked bold that does not look bold). Variable fonts are unusable for weights |
| Does `registerFont` refuse a duplicate? | No: registering the same font twice returns true again. A clash with an installed family cannot be detected from the return value |
| Missing characters? | They draw as empty boxes; Java does not fall back to another font for a bundled family. `canDisplayUpTo` predicts it exactly |
| Script coverage | Barlow, Barlow Condensed: Latin and accents only. IBM Plex Mono, PT Serif: plus Cyrillic, no Greek. Inter: plus Cyrillic and Greek. None: CJK, emoji |
| Digits the same width (a clock that does not shift each second)? | IBM Plex Mono and PT Serif yes ("00:00:00", "11:11:11", "12:34:56" all equal). Barlow, Barlow Condensed and Inter no: "11:11:11" is about two thirds the width of "00:00:00". Java2D does not apply the OpenType `tnum` feature, so a font's optional tabular digits cannot be switched on |
| Same pixels on both systems? | **Yes.** Every bundled font, plain and bold, renders pixel-identical on Windows and Linux (zero differing pixels in the text area). One set of golden images serves both |
| Java's logical fonts (`Serif`, `SansSerif`, ...)? | Differ by system. Windows maps them to installed Windows fonts (270 families available) and covers every sample script; the Ubuntu server has 12 families (DejaVu, Liberation through fontconfig), different widths (`Serif` 189 against 158 on Windows for the same text) and no CJK. An unknown family falls back to `Dialog` on both |
| System font packages on the Ubuntu server | `fontconfig`, `fonts-dejavu-core`, `fonts-dejavu-mono`, `fonts-liberation`, `fonts-liberation-sans-narrow` |
| A Linux server with no system fonts, or without the fontconfig library? | **Java cannot draw any text, bundled fonts included.** Every font call, even `Font.createFont` on a bundled file, throws `RuntimeException: Fontconfig head is null, check your fonts or fonts configuration`. Same with fonts present but `libfontconfig` missing, and with both missing |
| Can that be avoided without installing system fonts? | **Yes, with a JVM option.** Given its own `fontconfig.properties` through `-Dsun.awt.fontconfig=<file>`, mapping the logical fonts to the bundled Fira files, Java draws normally with no system fonts and no `libfontconfig`, and the output is pixel-identical to the normal server. Side effects: the option is JVM-wide, so the logical fonts (`Serif`, `SansSerif`, ...) become Fira for everything in WSE, and `registerFont` returns false for a file the properties already name |

The no-system-fonts cases ran on the AWS server in a private mount namespace (`unshare -m`) that hid
`/usr/share/fonts`, `/usr/local/share/fonts`, `/var/cache/fontconfig` and/or `libfontconfig.so.1` for the test
process only; nothing on the server changed (no leftover mounts afterwards). Scripts: `spikes/fonts/run-nofontconfig.sh`,
`spikes/fonts/run-fcprops.sh`; output in `spikes/fonts/linux-nofc/`.

## By family

| Family | Weights and styles tested | Scripts beyond Latin | Same-width digits | Size of the files tested |
|---|---|---|---|---|
| Fira Sans | Regular, Bold, Italic (real Italic face) | Cyrillic, Greek | No | 1.42 MB |
| Fira Sans Condensed | Regular, Bold, Italic (real Italic face) | Cyrillic, Greek | No | 1.42 MB |
| Fira Mono | Regular, Bold | Cyrillic, Greek | Yes | 0.38 MB |
| Barlow | Regular, Bold | none | No | 0.21 MB |
| Barlow Condensed | Regular, Bold | none | No | 0.21 MB |
| IBM Plex Mono | Regular, Bold | Cyrillic | Yes | 0.27 MB |
| PT Serif | Regular, Bold | Cyrillic | Yes | 0.70 MB |
| Inter (variable) | one weight only | Cyrillic, Greek | No | 0.88 MB |

Every family renders pixel-identical on Windows and Linux. With no Bold Italic file, `BOLD | ITALIC` draws the Bold
face upright (Fira Sans, Fira Sans Condensed).

## What it means for 2.0

- Bundle **static** files only, one file per weight. No variable fonts.
- Italic needs its own files, or a synthesized slant (a shear transform) when a family has none. Decide per family.
- Clocks and countdowns need a family with same-width digits. Default the date and time presets to one.
- Detect a family-name clash with an installed font by checking which face `new Font(family, ...)` resolves to after
  registering, not by `registerFont`'s return value.
- The missing-character warning (`/fonts/check`) is necessary: a bundled font shows boxes, not a fallback.
- On Linux, text needs the fontconfig library and at least one system font, or the JVM option above. A server
  without them would draw web pages and images but no text layer.
- Golden images for bundled fonts can be shared between Windows and Linux at the same Java build. Logical fonts
  cannot, so tests should draw text with bundled fonts only.
