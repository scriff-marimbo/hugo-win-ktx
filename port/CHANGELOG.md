# Changelog

## 1.0.0 - 2026-10-31

First release.

- Kotlin/Swing port of *Hugo's Horrific Adventure* (Hugo for Windows v1.5), running the
  original `HUGOWIN1.EXE` and `.DAT` files unchanged.
- Faithful engine: 9 ticks per second (16 in turbo), MSVC `rand()` and `qsort`, all 49
  action types, and save files that are byte-compatible with the original.
- The original look: toolbar, icons, menus, dialogs and status bar from the EXE's resources.
- Port extras: scalable window and full screen, optional smooth scaling, optional SoundFont.
- One universal jar for Linux (x64/arm64) and Windows (x64/arm64), with `hugo.sh` and
  `Hugo.bat` launchers, third-party notices and licence texts.
