# Hugo's Horrific Adventure: Kotlin / Swing port v1.0.0

This port runs **Hugo for Windows v1.5** (HUGOWIN1.EXE, 1995–97, David P. Gray) on modern
Linux and Windows. It reads the original `HUGOWIN1.EXE` and `.DAT` files at run time and
copies none of them. The engine is a transcription of the EXE's own code, and the function
addresses are noted in the sources.

## Requirements

* **Java 17 or newer** (a JRE is enough; no JDK needed).
* Linux (x64 or arm64) or Windows (x64 or arm64).
* The original **Hugo for Windows v1.5** game files. The port reads them at run time and
  copies none of them.

## Installing and running

Unzip `hugo-port-1.0.0.zip`. It contains the jar, this README, `CHANGELOG.md`, `LICENSE`,
`THIRD-PARTY-NOTICES.md`, `licenses/` and the two launchers.

## Game files

Put the Hugo game files in the same folder as the port's jar file.
You need these files together in one folder: `HUGOWIN1.EXE`, `HUGO.BSF`, `HHH00.SAV`,
`OBJECTS.DAT`, `SCENERY.DAT`, `SOUNDS.DAT`, `STRINGS.DAT` and `UIF.DAT`.

### Linux

```
cd hugo-port-1.0.0
chmod +x hugo.sh          # only needed if the unzip did not keep the executable bit
./hugo.sh /path/to/HWIN_1
```

### Windows

Install Java 17 or newer (for example Eclipse Temurin from <https://adoptium.net/> or
Microsoft OpenJDK), then either put the game files in the release folder and double-click
`Hugo.bat`, or open Command Prompt and run:

```
Hugo.bat C:\path\to\HWIN_1
```

### Running the jar directly

```
java -jar hugo-port-1.0.0-all.jar                 # run from the game folder, or…
java -jar hugo-port-1.0.0-all.jar /path/to/HWIN_1  # …pass the folder (also -a/path/to/HWIN_1)
```

If the game folder isn't found, the port asks for it with a folder picker and remembers your
choice. `--version` / `-v` prints the port version, and `--help` / `-h` the command line.

The original stores its saves (`HHHn.SAV`) and `CONFIG.DAT` in the game folder, and the port
does the same. If that folder is read-only, they go to `~/.local/share/hugowin/saves`
(`%APPDATA%\HugoWin\saves` on Windows). Save files are **byte-compatible** with the Windows
original, in both directions.

Command-line switches match the original: `-d` is demo mode, and `-i` regenerates
`HHH00.SAV` from the EXE tables.

The window, menus, status bar and dialogs are Swing, in the platform's look and feel
(GTK on GNOME, Windows on Windows, Metal elsewhere). Pick another with the standard switch, e.g.
`java -Dswing.defaultlaf=javax.swing.plaf.metal.MetalLookAndFeel -jar hugo-port-1.0.0-all.jar`.

## Controls

These are the original's, from its menu and accelerator tables:

| | |
|---|---|
| Type + Enter | parser commands (`look`, `open door`, `take key`, …) |
| Arrow keys, Home/End/PgUp/PgDn | walk (the same key again stops Hugo) |
| Left click | walk there / look at / use an exit |
| Right click | take / use; in the icon bar, pick up an item, then right-click a target |
| F6 | inventory (icon bar) |
| F7 | look around |
| Ctrl+R, Shift+F3 | recall the last command |
| Ctrl+N / Ctrl+L, F5 / Ctrl+S, F4 | new / load / save |
| F2 / F3 / F8 | music / sound effects / turbo (16 instead of 9 ticks per second) |
| Alt+F4 | exit (it warns if you haven't saved in the last 30 s) |
| F11, Alt+Enter | full screen (port extra) |

The **Options → Display…** dialog (a port extra) sets the scaling and an optional SoundFont:

* The window keeps the game view at 320:184 while you resize it. Scaling (which matters in
  full screen) can stretch like the original, keep the aspect ratio, or use whole
  multiples only, with optional smooth filtering.
* A General MIDI SoundFont (`.sf2`) replaces Java's built-in instruments. This setting is
  stored in `hugo-port.properties`.

## What's faithful

* **Look:** the toolbar (help, music, sound, save, load, recall, turbo, look, inventory) is
  painted by hand in the Windows 3.x style, whatever the Swing look and feel, with the bitmaps
  from HUGOWIN1.EXE's own resources. The window icon and the check / X picture buttons on the
  game's message boxes come from the EXE's icons too. The menus use the original's wording, and
  the status bar has the sunken prompt and score panes.

* **Timing:** 9 ticks per second (16 in turbo), with the original's order of work in each
  tick.
* **Randomness:** the MSVC `rand()`, seeded with `srand(42)`.
* **Sorting:** the MSVC `qsort` is used for sprite ordering, so sprites tie-break the same way.
* **Scripts:** the action interpreter covers all 49 action types and reads the scripts
  directly from the EXE's data segment. It even patches the maze actions in place, as the
  original does.
* **Path finding:** the recursive flood-fill route finder runs on a 256 MB stack. It
  approximates the original's `_stackavail()` limit with a depth limit.
* **Quirks are kept**, for example:
  * "There aren't any!" when a later object with the same noun overrides "not close enough".
  * The hero's y offset applied again when a save made with a swapped hero image is restored.
* **Dialogs:** the Box, Save, Load, Volume and Tunes dialogs follow the original's
  behaviour. The tune names come from the EXE's dialog resource.

## Not ported

* WinHelp (`HUGOWIN1.HLP`). F1 shows a short summary instead.
* Input record/playback (`-r`, `-p`).
* The left/right speaker panning of a few effects. Effects play in mono.

## Troubleshooting

* **"Java 17 or newer was not found", or `Hugo.bat` closes at once** — install a Java 17+
  runtime and check that `java -version` works in a new terminal. On Windows, open Command
  Prompt in the release folder and run `java -jar hugo-port-1.0.0-all.jar` to see the error.
* **"HUGOWIN1.EXE and its .DAT files were not found"** — point the folder picker at the folder
  that contains both `HUGOWIN1.EXE` and the `.DAT` files. They must be from the v1.5 Windows
  release; the DOS games use different data.
* **No sound effects** — OpenAL found no audio device, so the game runs silently and prints
  `OpenAL: no audio device...` on stderr. Check the system audio; on Linux, PipeWire or
  PulseAudio must be running.
* **No music** — music needs a working Java MIDI synthesiser. Java's built-in Gervill synth is
  used by default; choose a General MIDI `.sf2` in **Options → Display…** if the instruments
  sound wrong.
* **Can't save next to the game** — if the game folder is read-only, saves and
  `hugo-port.properties` go to the user data folder listed under "Installing and running".
* **The window is small or stretched** — resize it, use **Window → Size x1/x2/x3**, press F11
  for full screen, or change the scaling mode in **Options → Display…**.

## Building

With Gradle (any JDK 17 or newer; the jar targets Java 17). Your wrapper works:

```
./gradlew run --args="/path/to/HWIN_1"
./gradlew fatJar          # build/libs/hugo-port-1.0.0-all.jar
./gradlew releaseZip      # build/release/hugo-port-1.0.0.zip (+ .sha256)
```

`releaseZip` puts the fat jar, this README, `CHANGELOG.md`, `LICENSE`,
`THIRD-PARTY-NOTICES.md`, `licenses/` and the `hugo.sh` / `Hugo.bat` launchers in a versioned
folder and zips it. It contains no game data.

`build-local.sh` is how the jar was built in the development sandbox, where Maven Central is
unreachable. It calls `kotlinc` directly against the LWJGL 3.3.6 release zip (unpacked; only
`lwjgl` and `lwjgl-openal` are needed); `FAT=1` also builds `hugo-all.jar`:

```
KOTLINC=~/kotlinc/bin/kotlinc LWJGL=~/lwjgl FAT=1 sh build-local.sh
```

There is also a headless test runner that drives the engine from a script and writes PNG
screenshots:

```
java -cp "$(cat build-local/classpath.txt)" hugo.HeadlessKt /path/to/HWIN_1 script.txt outdir
# script lines: tick N | type TEXT | key VK(hex) | click X Y | rclick X Y | move X Y |
#               answer TEXT | inv | save SLOT DESC | load SLOT | new | status | shot NAME
```

## Layout

```
src/main/kotlin/hugo/
  Main.kt            entry point: finds the game and save folders
  PortInfo.kt        the port's version number
  Headless.kt        scripted test runner
  data/              EXE (NE segments, DGROUP tables) and .DAT readers
  engine/            the game: Engine (main loop, state), Scheduler (actions), Parser,
                     Objects (movement, collisions, sprites), Interaction (mouse,
                     inventory, routes), Display, SaveGame (+ CONFIG.DAT), Sound, Crt
  platform/          Swing: App (JFrame, menus, status bar, input, the port's dialogs),
                     GameView (Java2D scaling), ToolBar (the original's toolbar),
                     Dialog (modal boxes), Audio (LWJGL OpenAL effects,
                     javax.sound.midi music), Settings
dist/                hugo.sh and Hugo.bat launchers for the release zip
LICENSE              MIT licence for the port
THIRD-PARTY-NOTICES.md   licences of the bundled components
licenses/            full texts for LWJGL, OpenAL Soft, Kotlin and Liberation Sans
CHANGELOG.md
```

macOS isn't a target for 1.0.0, but with Swing it should only need the macOS OpenAL natives
added to the build.

## Credits

* *Hugo for Windows* © 1995–97 David P. Gray / Gray Design Associates. The game data is not
  included; see "Getting the game files".
* ScummVM's `hugo` engine, used as a reference for the original game logic.
* LWJGL (OpenAL sound effects), Kotlin, and Liberation Sans.
* Kotlin/Swing port by scriff-marimbo.

## Licence

The port's own code is released under the MIT licence, © 2026 scriff-marimbo (see `LICENSE`).
The original game, its executable and its data files remain © 1995–97 David P. Gray; they are
not included and the port reads them at run time. Third-party components bundled in the jar
(LWJGL, OpenAL Soft, Kotlin and Liberation Sans) keep their own licences; see
`THIRD-PARTY-NOTICES.md` and `licenses/`.
