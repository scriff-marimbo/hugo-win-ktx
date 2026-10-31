# Hugo for Windows v1.5: data file formats

These notes cover *Hugo's Horrific Adventure* (`HWIN_1/`, HUGOWIN1.EXE, 250,832 bytes). All values are little-endian.

I checked every format in two ways:

1. **Code:** I traced the routine in HUGOWIN1.EXE that reads the file. Addresses are Ghidra `segment:offset`, and the names are listed in `symbols.csv`.
2. **Data:** I decoded the shipped files with `tools/hugo_dat.py`, which parses every file without errors and exports clean images, sounds and text.

ScummVM's `hugo` engine is a port of the original source, and it agrees with everything below. Where the Windows v1 build differs from ScummVM's generic code, that's noted.

Screen geometry is 320×200 with 16 colours (EGA palette from the PCX headers). The playfield view is 320×184 (y = 8..191). The window is scaled with `StretchDIBits`.

---

## 1. Where the game lives

| Content | Location |
|---|---|
| Backgrounds and walk/priority masks | `SCENERY.DAT` |
| Sprite frames | `OBJECTS.DAT` |
| Music (MIDI) and sound effects (PCM) | `SOUNDS.DAT` |
| Descriptions and responses (the long texts) | `STRINGS.DAT` |
| Fonts and the inventory icon sheet | `UIF.DAT` |
| Registration/branding, plus the licence shown on exit | `HUGO.BSF` |
| Initial game state ("new game" = restore slot −1) | `HHH00.SAV` |
| Options (sound, music, volumes, window position) | `CONFIG.DAT`, created at runtime (§11) |
| **Game logic**: vocabulary, objects, commands, action scripts, hotspots | **Inside HUGOWIN1.EXE** (static C tables in DGROUP and segment 5). See §9; fully decoded to `analysis/exe_tables.json`. |

---

## 2. PCX images (shared by SCENERY, OBJECTS, UIF)

These are standard ZSoft PCX v5 files with RLE encoding and **1 bit/plane × 4 planes** (16 colours). The reader is `ReadPCX` @1010:2a90, which uses a 2 KB buffered `_lread`.

```
header (128 bytes)
  0  u8  manufacturer = 0x0A       (else "Bad data file format")
  1  u8  version = 5
  2  u8  encoding = 1 (RLE)
  3  u8  bits/plane = 1
  4  u16 xmin, ymin, xmax, ymax
 12  u16 hres, vres
 16  u8  palette[16][3]            8-bit RGB, used for the EGA colours
 65  u8  planes = 4
 66  u16 bytes per line (per plane)  -> stored width = bpl*8
data
  RLE: byte b; if (b & 0xC0) == 0xC0: repeat next byte (b & 0x3F) times, else literal b
  each scan line = 4 planes × bpl bytes (plane 0 = bit 0 of the colour index)
```

`ConvertPlanarLine` @1010:29dc turns each line into 8 bpp. The engine keeps two widths:

- `bpl*8` is the buffer stride.
- `xmax-xmin+1` is the valid width (`Seq.x2` before the bounding box is computed).

PCX images are stored back to back with no length field. The next image starts right after the last RLE byte of the previous one.

---

## 3. SCENERY.DAT: 17 screens

```
header: SceneBlock[N], N = first u32 / 32  (= 17)
struct SceneBlock {                    // 32 bytes
    u32 scene_off,    scene_len;       // PCX 320x200
    u32 boundary_off, boundary_len;    // 8000 bytes, 1 bpp, 40 bytes/row
    u32 overlay_off,  overlay_len;
    u32 base_off,     base_len;
};
```

- **Readers:**
  - `ReadBackground` @1010:2eb2 reads the scene PCX.
  - `ReadOverlay` @1010:308e reads the masks: type 0 = boundary, 1 = overlay, 2 = base.
- **v1w difference:** the DOS and v2 versions PackBits-compress the three masks. In this build they are **raw 8000-byte bitmaps**. If a length is 0, the mask is all zeros.
- **Bits:** MSB = leftmost pixel.
  - `boundary` holds the walls hero and objects can't cross (OR'd with the dynamic object boundary at 1028:1c10).
  - `overlay` marks foreground pixels that hide sprites.
  - `base` holds the baselines that decide whether a sprite is in front of the overlay (`FindOvl` @1010:232c).
- **Screen names**, from the EXE (index = screen): House, Hall, Bed1, Diningrm, Bathroom, Kitchen, Garden, Storerm, Basement, Batcave, Mummyrm, Lakeroom, Deadend, Jail, The_end, Lab, House.

## 4. OBJECTS.DAT: sprite frames

```
header: { u32 offset; u32 length; } [88]       // one per object; first offset = 0x2C0 = 88*8
object data: consecutive PCX images (length 0 = object has no images)
```

- **Reader:** `ReadImage` @1010:2caa.
- **Splitting frames into sequences:** that information is not in this file. Each object record in the EXE has `seqList[4].imageNbr`. For example, the hero (object 0) has 4,4,2,2 frames, for walking right, left, down and up. Those counts total exactly 180, the number of frames in the file (checked).
- **Bounding box:** after loading, the engine computes x1/x2/y1/y2 from the non-zero pixels. Colour 0 is transparent.

## 5. SOUNDS.DAT: 12 MIDI tunes and 12 sound effects

```
header: { u16 size; u32 offset; } [64]   // the game reads 0x180 bytes; data starts at 0x300
size == 0  -> unused slot ("Sound missing from sound file")
```

| Slots | Content | Played by |
|---|---|---|
| 0–11 | Standard MIDI File (`MThd`), format 1 | Music. `PlayMidiMCI` @1010:91a0 writes the tune to a temp file and opens it with the MCI `sequencer`. |
| 14–25 | Raw PCM, **8-bit unsigned mono, 11025 Hz**, no header | Sound effects, played with `waveOutWrite` in `PlaySound` @1010:93f8 (with a priority byte). |

Index < 12 means music, ≥ 12 means a sound effect (`GetSound` @1010:2f2a keeps one buffer of each kind).

## 6. STRINGS.DAT: 511 text strings

```
u32 offset[512]           // offset[0] = 0x800 = header size
string i = bytes [offset[i], offset[i+1])    (no terminator in the file)
```

- **Encoding:** each byte has the key byte subtracted (`DecodeString` @1010:556c):
  `plain[i] = (cipher[i] - key[i % 38]) & 0xFF`, where `key = "Copyright 1992, Gray Design Associates"`.
- **Character set:** text uses Windows-1252, and `\n` is used inside strings for line breaks.
- **Limit:** a string can be at most 0x3B5 bytes (`FetchString` @1010:37fe).
- **Callers:** background commands, object descriptions and actions refer to strings by index. Examples: #0 "Ok.", #24 is the opening description.

## 7. UIF.DAT: fonts and icons

```
header: { u16 size; u32 offset; } [32]   // 0xC0 bytes, cached at 1018:0000 by GetUIFHeader @1010:3ada
item 0 = 5-px font, 1 = 6-px font (7 high), 2 = 8-px font, 3 = icon sheet (PCX 320x200)
```

**Font blob** (decoded by `LoadFont` @1010:28ee):

```
u8 height, u8 width                       // nominal
for ch in 1..127:
    u8 h, u8 w, u8 bits[h * ((w+7)/8)]    // MSB = leftmost pixel in the file;
                                          // the EXE bit-reverses each byte (ReverseByte) after loading
```

**Icon sheet:** a grid of 32×32 inventory icons. The first two cells are the scroll arrows (left and right). The icon for inventory slot *n* sits at x = ((n+2)·32) mod 320, y = ((n+2)·32 div 320)·32 (`DrawInventoryBar` @1010:6eda).

## 8. HUGO.BSF: boot/branding file

```
struct hugoBoot {          // 44 bytes, XOR-encrypted
    char checksum;         // XOR of all 44 *encrypted* bytes must be 0
    char registered;       // 0 = shareware
    char pbswitch[8];      // "-p"  (playback switch)
    char distrib[32];      // "Shareware"
    u16  exitLen;          // 1084
};
then exitLen bytes of exit text
```

- **Encryption:** both parts are XORed with `"Copyright 1992, David P Gray, Gray Design Associates"`, and the key index restarts at 0 for the exit text.
- **Exit text:** decrypts to exactly LICENSE.TXT (checked).

## 9. Data compiled into HUGOWIN1.EXE

These tables hold the game logic. `tools/exe_tables.py` decodes all of them into `analysis/exe_tables.json`, and `tools/print_scripts.py` turns that JSON into the readable `analysis/scripts.txt`. All pointers are 16-bit near pointers into DGROUP (segment 1070) unless noted.

| Data | Address | Layout |
|---|---|---|
| Object table (initial state) | seg 5 (1020:0000, file 0x25D00), 88 × 0x54 | `struct Object` below |
| Use-with table | seg 5 (1020:1CE0), 52-byte entries, ends at obj == 88 | `{i16 obj; char *noTarget; {char **noun; char **verb} target[12]}`. 9 entries. Read by `UseObject` |
| Nouns | DGROUP:03B4 | `char **list[]`. Each list is `char*[]` ending in `""`: [0] = id, [1] = article form ("a pumpkin") or `~`, [2…] = synonyms. 74 lists. |
| Verbs | DGROUP:057C | Same layout, 54 lists. Special lists: take 047A, drop 0486, look 04C4. |
| Screen names | DGROUP:2CA2 | `char*[17]` |
| Hotspots (exits) | DGROUP:34D2 | `{i16 screen,x1,y1,x2,y2; u16 actList; i16 viewx,viewy,direction}`, 18 bytes, ends at screen < 0. 27 entries. |
| Background commands | DGROUP:34B2 (per screen, 16 ptrs), 2D28 (every screen) | `{char **verb, **noun; u16 string; u16 matchFl; i8 roomState; u8 bonus}`, 10 bytes, ends at verb == 0 |
| Per-object verb commands | `Object.cmdIndex` → 0-terminated `cmd*[]` | `struct cmd` below. 29 distinct lists, 71 commands. |
| Screen entry actions | DGROUP:2D08 | `u16 *acts[16]`, each a 0-terminated list of action-list ptrs |
| Bonus points | DGROUP:36CA, count at 36FE (= 13) | `{u8 score; u8 pad; u16 scored}` |
| Action lists | DGROUP | 0-terminated `u16[]` of action ptrs. 173 lists, 485 actions. |

Only 16 screens (0–15) are playable. Screen 16 is a second copy of the House picture, and the per-screen tables have 16 entries. Reading a 17th entry runs into the next table.

**struct Object (0x54 bytes)**, as accessed by the code:

```
00 u16 noun (ptr to word list)   02 u16 description (char*, or char*[] by state if genericCmd & 8)
04 i16 pathType  06 i16 vxPath  08 i16 vyPath  0A u16 actIndex (collision list)  0C u8 seqNumb
0E far Seq* currImage            12 {u16 imageNbr; far Seq* seq}[4]
2A i16 cycling  2C u8 cycleNumb  2D u8 frameInterval  2E u8 frameTimer  2F i8 radius
30 u8 screen    32 i16 x  34 i16 y  36 i16 oldx  38 i16 oldy  3A i8 vx  3B i8 vy
3C u8 value     3E u16 genericCmd   40 u16 cmdIndex   42 i16 carried   44 u8 state
46 i16 verbOnly 48 i16 priority     4A i16 viewx  4C i16 viewy  4E u8 direction
4F u8 curSeqNum 50 u8 curImageNum   51 i8 oldvx   52 i8 oldvy   53 pad
```

**cycling values in this EXE:** 0 INVISIBLE, 1 NOT_CYCLING, 2 CYCLE_FORWARD, 3 CYCLE_BACKWARD. This differs from ScummVM, which inserts ALMOST_INVISIBLE at 1; a port that copies ScummVM's enum will mis-read these tables.

**Seq (0x14 bytes, heap):** `far u8* image; u16 bytesPerLine8; u16 lines; u16 x1,x2,y1,y2; far Seq* next`.

**struct cmd (14 bytes)**, checked in `IsObjectVerb` @1010:8100:

```
00 char **verb          02 char ***reqObjs (0-terminated noun lists that must be carried)
04 char *textNotCarried 06 u8 reqState (0xFF = any)   07 u8 newState
08 char *textWrongState 0A char *textDone             0C u16 actList (0 = none)
```

Command and use-with texts are C strings in the EXE. Background commands and actions use STRINGS.DAT indexes instead.

**Actions.** Every action starts with `u16 type; i16 timer`. The timer is the delay in ticks (9 per second) after the list is queued. Fields are word-aligned, except where two `i8`s share a word at offsets 8/9. Layouts are taken from `DoAction` @1010:55a6.

| Type | Name | Fields after `type`@0 and `timer`@2 | Branch rule |
|---|---|---|---|
| 0 | ASCHEDULE | `list`@4 action-list ptr |  |
| 1 | START_OBJ | `obj`@4 object index, `cycle_count`@6 u8, `cycle`@8 i16 |  |
| 2 | INIT_OBJXY | `obj`@4 object index, `x`@6 i16, `y`@8 i16 |  |
| 3 | PROMPT | `prompt`@4 STRINGS.DAT index, `responses`@6 ptr to i16[] of STRINGS.DAT indexes, −1 terminated, `pass`@8 action-list ptr, `fail`@10 action-list ptr | pass if the typed answer contains one of the response strings |
| 4 | BKGD_COLOR | `color`@4 u32 |  |
| 5 | INIT_OBJVXY | `obj`@4 object index, `vx`@6 i8, `vy`@8 i8 |  |
| 6 | INIT_CARRY | `obj`@4 object index, `carried`@6 i16 |  |
| 7 | INIT_HF_COORD | `obj`@4 object index |  |
| 8 | NEW_SCREEN | `screen`@4 i16 |  |
| 9 | INIT_OBJSTATE | `obj`@4 object index, `state`@6 u8 |  |
| 10 | INIT_PATH | `obj`@4 object index, `path`@6 i16, `vx_path`@8 i8, `vy_path`@9 i8 |  |
| 11 | COND_R | `obj`@4 object index, `state`@6 u8, `pass`@8 action-list ptr, `fail`@10 action-list ptr | pass if objects[obj].state == state |
| 12 | TEXT | `text`@4 STRINGS.DAT index |  |
| 13 | SWAP_IMAGES | `obj1`@4 object index, `obj2`@6 object index |  |
| 14 | COND_SCR | `obj`@4 object index, `screen`@6 i16, `pass`@8 action-list ptr, `fail`@10 action-list ptr | pass if objects[obj].screen == screen |
| 15 | AUTOPILOT | `obj1`@4 object index, `obj2`@6 object index, `dx`@8 i8, `dy`@9 i8 |  |
| 16 | INIT_OBJ_SEQ | `obj`@4 object index, `seq`@6 i16 |  |
| 17 | SET_STATE_BITS | `obj`@4 object index, `mask`@6 u8 |  |
| 18 | CLEAR_STATE_BITS | `obj`@4 object index, `mask`@6 u8 |  |
| 19 | TEST_STATE_BITS | `obj`@4 object index, `mask`@6 i16, `pass`@8 action-list ptr, `fail`@10 action-list ptr | pass if (objects[obj].state & mask) == mask |
| 20 | DEL_EVENTS | `action_type`@4 action type |  |
| 21 | GAMEOVER | — |  |
| 22 | INIT_HH_COORD | `obj`@4 object index |  |
| 23 | EXIT | — |  |
| 24 | BONUS | `point`@4 i16 |  |
| 25 | COND_BOX | `obj`@4 object index, `x1`@6 i16, `y1`@8 i16, `x2`@10 i16, `y2`@12 i16, `pass`@14 action-list ptr, `fail`@16 action-list ptr | pass if hero-relative foot point of obj is inside (x1,y1)-(x2,y2) |
| 26 | SOUND | `sound`@4 i16 |  |
| 27 | ADD_SCORE | `obj`@4 object index |  |
| 28 | SUB_SCORE | `obj`@4 object index |  |
| 29 | COND_CARRY | `obj`@4 object index, `pass`@6 action-list ptr, `fail`@8 action-list ptr | pass if objects[obj].carried |
| 30 | INIT_MAZE | `size`@4 u8, `x1`@6 i16, `y1`@8 i16, `x2`@10 i16, `y2`@12 i16, `x3`@14 i16, `x4`@16 i16, `first_screen`@18 u8 |  |
| 31 | EXIT_MAZE | — |  |
| 32 | INIT_PRIORITY | `obj`@4 object index, `priority`@6 i16 |  |
| 33 | INIT_SCREEN | `obj`@4 object index, `screen`@6 u8 |  |
| 34 | AGSCHEDULE | `list`@4 action-list ptr |  |
| 35 | REMAPPAL | `old_color`@4 i16, `new_color`@6 i16 |  |
| 36 | COND_NOUN | `noun`@4 noun word-list ptr, `pass`@6 action-list ptr, `fail`@8 action-list ptr | pass if a word of the noun list is in the typed line |
| 37 | SCREEN_STATE | `screen`@4 i16, `state`@6 u8 |  |
| 38 | INIT_LIPS | `lips_obj`@4 object index, `obj`@6 object index, `dx`@8 u8, `dy`@9 u8 |  |
| 39 | INIT_STORY_MODE | `story_mode`@4 i16 |  |
| 40 | WARN | `text`@4 STRINGS.DAT index |  |
| 41 | COND_BONUS | `point`@4 i16, `pass`@6 action-list ptr, `fail`@8 action-list ptr | pass if points[point] already scored |
| 42 | TEXT_TAKE | `obj`@4 object index |  |
| 43 | YESNO | `prompt`@4 STRINGS.DAT index, `yes`@6 action-list ptr, `no`@8 action-list ptr | yes/no box |
| 44 | STOP_ROUTE | — |  |
| 45 | COND_ROUTE | `route_index`@4 i16, `pass`@6 action-list ptr, `fail`@8 action-list ptr | pass if current route index >= route_index |
| 46 | INIT_JUMPEXIT | `jump_exit`@4 i16 |  |
| 47 | INIT_VIEW | `obj`@4 object index, `viewx`@6 i16, `viewy`@8 i16, `direction`@10 u8 |  |
| 48 | INIT_OBJ_FRAME | `obj`@4 object index, `seq`@6 i16, `frame`@8 i16 |  |
| 255 | ANULL | — |  |

`BKGD_COLOR` is a no-op in this build (`SetBackgroundColor` is empty), so the lightning flashes in the House script do nothing on Windows.

**How the decode was checked:**

- **Reachability:** walking from every root (object collision lists, cmd lists, hotspots, screen entry lists, and every pass/fail/schedule pointer) reaches 173 lists. Every list in the listing is referenced; no unknown action type appears.
- **ScummVM cross-check:** ScummVM's `create_hugo` has a hand transcription of the same tables (`actListArr_1w`, 173 lists). Our lists, sorted by address, match it one-to-one in type and timer. That is how the symbolic names (`ALopendoor1`, `achkdoor` …) in the JSON were attached.
- **Field values:** of 975 scalar fields with a literal value in ScummVM, 966 agree. The other 9 are `BKGD_COLOR` (Windows RGB in the EXE, symbolic colours in ScummVM) and one enum-name formatting difference.
- **Other tables:** the cmd lists (29) and the use-with table (9 entries, same targets) also match ScummVM.

## 10. HHHn.SAV: saved games (n = 0..7; HHH00.SAV ships as the new-game state)

Written by `SaveGame` @1010:335a and read by `RestoreGame` @1010:34b2. HHH00.SAV is 8,653 bytes and parses exactly with no bytes left over.

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | file length (patched in at the end; checked against the expected value on restore) |
| 4 | 5 | `"v1.5\0"` version tag (memcmp) |
| 9 | 32 | description |
| 41 | 88×84 | raw `Object` records. Before writing, `SaveSeq` stores curSeqNum and curImageNum so pointers can be rebuilt. |
| 7433 | 1 | heroImage (swapped hero object, 0 = normal) |
| 7434 | 2+2+2+2 | score, storyModeFl, jumpExitFl, gameOverFl |
| 7442 | 17 | screenStates[17] |
| 7459 | 4×13 | bonus points `{u8 score; u8 pad; u16 scored}` |
| 7511 | 4 | scheduler tick at save time |
| 7515 | 2 | action-table base address. On restore, action pointers are relocated if it differs ("Obsolete saved game format will be converted"). |
| 7517 | 2+2+2 | free-list / head / tail as event indexes (−1 = null) |
| 7523 | 600 | event[50] `{u16 action; u16 localFl; u32 time; u16 prev; u16 next}`. Pointers are stored as-is and rebuilt from the indexes; times are rebased to the new tick. |
| 8123 | 512 | palette remap table (`SavePal`) |
| 8635 | 18 | maze `{u16 enabled; u8 size; pad; i16 x1,y1,x2,y2,x3,x4; u8 firstScreen; pad}` |

## 11. CONFIG.DAT: options (50 bytes)

`ReadConfig` @1010:3a1e reads it at start-up; a short read keeps the defaults. `WriteConfig` @1010:3a8e writes it on exit. Defaults are set in `InitConfig(0)` @1010:3c8e.

| Offset | DGROUP | Type | Field | Default |
|---|---|---|---|---|
| 0 | 724E | u16 | music on | 1 |
| 2 | 7250 | u16 | sound effects on | 1 |
| 4 | 7252 | u16 | turbo (16 instead of 9 ticks/s) | 0 |
| 6 | 7254 | i16 | window x | centred |
| 8 | 7256 | i16 | window y | centred |
| 10 | 7258 | u16 | client width | 640 |
| 12 | 725A | u16 | client height | 368 (2 × 184) |
| 14 | 725C | u16 | sound-dialog check box; inferred from `PauseSound`: keep sound while the window is inactive | 0 |
| 16 | 725E | u8 | music volume % | 85 |
| 17 | 725F | u8 | sound volume % | 100 |
| 18 | 7260 | u16[16] | playlist: tune *n* enabled | default list at DGROUP:3700 |

## 12. Runtime facts a port needs

- **Main loop:** the game runs inside `CWinApp::OnIdle` → `MainLoopTick` @1010:6d6e, which is frame-limited to **9 ticks/s** (16 ticks/s in Turbo mode, F8). Each tick runs, in order: keyboard → move objects → scheduler → restore dirty rects → update/draw objects → mouse → flush the display list.
- **Randomness:** `rand()` is the MSVC LCG (`seed = seed*214013 + 2531011; return (seed>>16) & 0x7FFF`). `InitMachine` calls `srand(42)` once, so a run is deterministic for a given input sequence. It's used for wandering objects (`rand() % 27 == 0`).
- **Command line** (`ParseCommandLine` @1010:01c8): `-a<path>` sets the data directory, `-d` is demo mode (the game ends when story mode is switched off), `-i` regenerates HHH00.SAV from the EXE tables, and `-r` / `-p` record and play back input to `playback.dat`.
- **New game:** HHH00.SAV is just a save of the initial tables. `InitSaveState` @1010:368c writes it if it's missing, and starting a game restores it.
- **Path finding:** this is ScummVM's `Route`. It uses a recursive flood fill on the 320×200 byte map at 1060:0000 and is bounded by `_stackavail()`.
