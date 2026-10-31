# HUGOWIN1.EXE: program map

HUGOWIN1.EXE is a 16-bit Windows 3.1 program (NE format). It was built with **Microsoft Visual C++ 1.5x, large memory model, and MFC 2.5** (`_AFX_VERSION`; uses `CVBControl` for VBX controls). The entry point is `1008:5f2e`, and DGROUP is segment 15 (`1070`).

Ghidra 11.4.2 auto-analysis found 866 functions. A second pass found 71 more in Code3: dialog handlers, `InitInstance`, the config and boot-file readers and others, which are only reached through MFC message maps and vtables. That makes **937 functions** in total. Classification:

| Segment | Size | Contents | Functions |
|---|---|---|---|
| `1000` Code1 | 58 KB | MFC 2.5 library (CWnd, CDialog, CFrameWnd, CView, CString, CStatusBar, CToolBar, CFileDialog, ...) | 416 |
| `1008:0000-5f2d` Code2 | 24 KB | More MFC: VBX support (`CVBControl`, LoadLibrary/GetProcAddress of .VBX), GDI objects | ~102 |
| `1008:5f2e-af5b` Code2 | 21 KB | MSVC C runtime: startup `__astart`, file I/O, string, malloc, printf, math helpers | ~150 |
| `1010` Code3 | 45 KB | **All game code**: 268 functions, every one named in `symbols.csv`. About 5.6 KB of the segment is message maps and vtables. | 268 |
| `1018`…`1068` | — | Uninitialised far data: UIF header, overlays, 320×200 DIB buffers, route map | — |
| `1020` | 7.9 KB | Initialised object table (88 × 0x54) | — |
| `1070` DGROUP | 31 KB | Strings, vocabulary, command/action tables, game state | — |

The game imports only standard Windows modules: KERNEL, USER, GDI, MMSYSTEM, COMMDLG, SHELL, KEYBOARD, WIN87EM. Apart from the 7 VBX controls used in dialogs, the program has no other dependencies.

Library code is ~70% of the binary. For a port, **only segment 1010 needs real work**. MFC and the CRT can be replaced with SDL (or Kotlin/JVM equivalents) plus your own small UI.

## Files

- `symbols.csv`: 342 named functions with area, ScummVM equivalent and notes. That covers all 268 game functions plus the CRT/MFC routines the game calls.
- `globals.csv`: ~80 named global variables and buffers.
- `functions.csv`: all 937 functions with address, area, name, size, caller count, Win16 APIs called and referenced strings.
- `../analysis/decompiled.c`: Ghidra decompilation of every function, with the names and comments applied.
- `../ghidra/ApplySymbols.py`: applies both CSVs to your own Ghidra project (Script Manager, or `-postScript ApplySymbols.py <docs dir>` headless).

## Game code by subsystem (segment 1010)

Names follow the original engine, which ScummVM preserved: the error strings in the EXE are literally `Get_queue`, `Do_action`, `Fetch_string`. So `engines/hugo/*.cpp` in ScummVM works as an annotated reference for nearly every function.

| Subsystem | Range | Key functions |
|---|---|---|
| App/MFC glue | 0006–1cd2, 63de–6c30, 9764–a390 | `CHugowinApp::InitInstance` / `OnIdle` (drives the game), `ParseCommandLine`, `CHugowinView::OnCreate/OnDraw/OnSize/OnKeyDown/OnL/RButtonDown`, `CMainFrame::OnClose/OnActivateApp`, the save/restore, text-box, sound and music dialogs, `InitVBXs` |
| Display | 1f0e–29dc | `MoveImage`, `RedrawView`/`DisplayRect` (StretchDIBits), `DisplayFrame` (sprite + overlay priority), `DisplayList` (dirty-rect list), text: `WriteChr/WriteStr/ShadowStr/LoadFont` |
| File I/O | 2a90–3c8e | `ReadPCX`, `ReadImage`, `ReadBackground`, `ReadOverlay`, `GetSound`, `FetchString`, `GetUIFHeader/ReadUIFItem`, `SaveGame/RestoreGame`, `InitSaveState`, `OpenDatabaseFiles`, `ReadBootFile`, `ShowLicense`, `ReadConfig/WriteConfig/InitConfig` |
| Engine | 3c24–3e70, 46f0, 5184, 5342, 6cee–6d6e | `InitMachine` (timer, sound, events, display, `srand(42)`), `InitGame`, `MainLoopTick` (state machine), `ShutdownGame`, `ProcessMaze`, `ScreenActions`, `ReadScreenFiles`, `CalcMaxScore` |
| Objects | 3ecc–5318, 60c0 | `MoveObjects`, `DeltaX/DeltaY`, `Store/ClearBoundary`, `UpdateImages`, `FindObject`, `UseObject`, `LookObject`, `BoundaryCollision`, `SwapImages` |
| Scheduler | 5404–60a2 | `GetQueue/DelQueue`, `InsertAction(List)`, **`DoAction`** (49 action types), `RunScheduler`, `NewScreen`, `Save/RestoreEvents` |
| Intro | 53aa–53bc | stubs; the Windows intro is just "restore the initial state" |
| Inventory | 6eda–70e4 | `DrawInventoryBar`, `ProcessInventory`, `RunInventory` (sliding icon bar) |
| Input/parser | 7308–8100 | `KeyHandler`, `CharHandler`, `MoveHeroByKey`, **`LineHandler`** (text parser + debug commands `goto`, `fetch`, `fetch all`), `FindNoun/FindVerb`, `IsNear`, `IsGenericVerb`, `IsObjectVerb`, `Take/DropObject` |
| Mouse | 8224–85ca | `MouseHandler`, `ProcessLeftClick/RightClick`, `FindExit`, `CursorText` |
| Route finding | 87b6–8ffe | `Segment` (recursive flood fill), `FindRoute`, `ProcessRoute`, `StartRoute` |
| Sound | 9066–9702 | `InitSound`, `PlayMusic` (MIDI via MCI sequencer + temp file), `PlaySound` (waveOut 11 kHz), toggles and volume |
| Utils | 556c, 619c–6462 | `DecodeString`, `First/LastBit`, `ReverseByte`, `Warn`, `Error`, `Box` |

### Call flow of one game tick

```
CHugowinApp::OnIdle (1010:02a6)
 └─ MainLoopTick (1010:6d6e)           every 1000/9 ms (1000/16 in Turbo)
     state 0: IntroInit -> RestoreGame(-1)    (loads HHH00.SAV = new game)
     state 2: IntroPlay -> NewScreen(0)
     state 3 (play):
        CharHandler          keyboard queue / command line
        MoveObjects          paths, chase, wander, collisions
        RunScheduler         due events -> DoAction
        DisplayList(RESTORE) erase dirty rects
        UpdateImages         sort by y, cycle frames, DisplayFrame
        MouseHandler         hover text, clicks -> routes / commands
        DisplayList(DISPLAY) StretchDIBits the dirty rects
     state 4: RunInventory   icon bar slide / selection
     state 5: exit
```

## Not mapped yet

- **MFC/CRT names:** apart from the ~75 routines the game calls, MFC and CRT functions are left as `FUN_xxxx_xxxx`. Naming them isn't needed for a port, since they get replaced rather than ported.
- **Dialog layouts:** the dialog resources (100, 103, 104, 133, 135, 137) and the VBX property numbers set in their handlers are only named, not documented field by field. A port redraws these dialogs anyway.
