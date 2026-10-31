# Hugo for Windows, Kotlin port:

*Hugo's Horrific Adventure*, specifically the Windows 3.1/95 version of HUGO, a DOS adventure by David P. Gray (1995–97). The port is currently for Linux and Windows.

To run it, extract the release zip, and then put the required "Hugo for Windows v1.5" game files in the same directory. Then run either hugo.sh (Linux) or hugo.bat (Windows).

It is written in Kotlin with LWJGL and Swing and runs the original game files unchanged. See `port/README.md`.

## Layout

| Path | What |
|---|---|
| `port/` | Kotlin/LWJGL port (sources, Gradle build, Linux/Windows launchers, MIT licence; see `port/README.md`) |
| `HWINUNR.EXE` | Original Wise installer (untouched) |
| `extracted/` | Installer payload in its installed layout. The game is in `extracted/HWIN_1/`. Installer-only files are in `extracted/_installer/`. |
| `docs/DAT_FORMATS.md` | Every data file format, the save format, and the game tables compiled into the EXE |
| `docs/FUNCTIONS.md` | Program map: segments, subsystems, main loop |
| `docs/symbols.csv` | Names and notes for all 268 game functions, plus the CRT/MFC routines they call |
| `docs/globals.csv` | Named globals and buffers |
| `docs/functions.csv` | All 937 functions (area, size, APIs, strings) |
| `analysis/decompiled.c` | Ghidra decompilation of the whole EXE, with names applied |
| `analysis/exe_tables.json` | All game logic pulled from the EXE: objects, verb commands, use-with table, vocabulary, hotspots, background commands, screen entry lists, and every action list and action (173 lists, 485 actions, with names and texts) |
| `analysis/scripts.txt` | The same scripts as a readable listing: each action list, what triggers it, and its actions with names and texts resolved |
| `analysis/strings.txt` | All 511 decoded strings from STRINGS.DAT |
| `assets_dump.zip` | Every screen, mask, sprite frame, icon, font, MIDI and WAV, exported |
| `tools/` | Python 3 tools (Pillow needed only for PNG output) |
| `ghidra/` | Ghidra scripts (Jython), including `ApplySymbols.py` |

## Tools

```sh
python3 tools/wise_extract.py HWINUNR.EXE extracted          # unpack installer (CRC-checked)
python3 tools/hugo_dat.py extracted/HWIN_1 info              # summary of every data file
python3 tools/hugo_dat.py extracted/HWIN_1 dump assets       # export PNG/MID/WAV/TXT/JSON
python3 tools/exe_tables.py extracted/HWIN_1/HUGOWIN1.EXE analysis/exe_tables.json extracted/HWIN_1/STRINGS.DAT
python3 tools/print_scripts.py analysis/exe_tables.json > analysis/scripts.txt
```

`tools/hugo1w_names.json` holds the symbolic names ScummVM uses for these tables (from its `create_hugo` transcription). `exe_tables.py` attaches a name only after checking that the list's action types and timers match exactly.

To get the same Ghidra view: import `extracted/HWIN_1/HUGOWIN1.EXE` (NE format is detected automatically), run auto-analysis, then run `ghidra/ApplySymbols.py` and choose the `docs/` folder.

## Key facts

- **What's in the EXE:** game code is a single 45 KB segment (1010). The other ~70% of the EXE is MFC 2.5 and the MSVC runtime.
- **What's in the DAT files:** all art, sound and text is in simple, now fully documented files. Game *logic* (objects, vocabulary, commands, action scripts) is compiled into the EXE as static tables.
- **ScummVM reference:** ScummVM's `hugo` engine is derived from the original source and matches this build closely. Its source code is the best reference for each subsystem.
