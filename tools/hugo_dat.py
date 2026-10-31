#!/usr/bin/env python3
"""Parse / dump the data files of Hugo for Windows v1.5 (Hugo's Horrific Adventure).

Every format below was checked against HUGOWIN1.EXE (see docs/DAT_FORMATS.md for
the function that reads each one) and against the actual files.

Usage:
    hugo_dat.py GAMEDIR info            # print a summary of every file
    hugo_dat.py GAMEDIR dump OUTDIR     # export PNG / MID / WAV / TXT / JSON

Needs Pillow only for PNG output (pip install pillow).
"""
import json, os, struct, sys, wave

# --------------------------------------------------------------------------- constants
XPIX, YPIX = 320, 200
OVL_SIZE = XPIX // 8 * YPIX            # 8000: 1 bit per pixel overlays
NUM_OBJECTS = 88                       # size of the object table in the EXE
MAX_SOUNDS = 64                        # SOUNDS.DAT header = 64 x 6 bytes (game reads 0x180)
MAX_UIFS = 32                          # UIF.DAT header   = 32 x 6 bytes (0xC0)
UIF_NAMES = {0: 'font5', 1: 'font6', 2: 'font8', 3: 'images'}
STRING_CYPHER = b'Copyright 1992, Gray Design Associates'
BOOT_CYPHER = b'Copyright 1992, David P Gray, Gray Design Associates'
PCM_RATE = 11025                       # raw sound effects are 8-bit unsigned mono


def u16(b, o): return struct.unpack_from('<H', b, o)[0]
def u32(b, o): return struct.unpack_from('<I', b, o)[0]


# --------------------------------------------------------------------------- PCX
class Pcx:
    """A 16-colour planar PCX (ver 5, RLE, 1 bit/plane, 4 planes) as used by Hugo."""
    def __init__(self, width, height, pixels, palette, bytes_per_line, size):
        self.width, self.height = width, height
        self.pixels = pixels            # bytearray, width*height, values 0..15
        self.palette = palette          # 16 (r,g,b) from the header
        self.bytes_per_line = bytes_per_line
        self.size = size                # bytes consumed from the stream (header+data)


def read_pcx(buf, off=0):
    """Decode one PCX image starting at buf[off]. Mirrors 1010:2a90 (readPCX)."""
    h = buf[off:off + 128]
    if h[0] != 0x0A:
        raise ValueError('not a PCX at %#x' % off)
    bpp, x1, y1, x2, y2 = h[3], *struct.unpack_from('<4H', h, 4)
    planes, bpl = h[65], u16(h, 66)
    if bpp != 1 or planes != 4:
        raise ValueError('unexpected PCX %d bpp x %d planes' % (bpp, planes))
    w, lines = bpl * 8, y2 - y1 + 1
    pal = [tuple(h[16 + 3 * i:19 + 3 * i]) for i in range(16)]
    line_len = bpl * planes
    raw = bytearray()
    p = off + 128
    need = line_len * lines
    while len(raw) < need:              # RLE: top two bits set => run length in low 6
        c = buf[p]; p += 1
        if c & 0xC0 == 0xC0:
            raw += bytes([buf[p]]) * (c & 0x3F); p += 1
        else:
            raw.append(c)
    pix = bytearray(w * lines)
    for y in range(lines):              # planar -> chunky, as 1010:29dc does
        row = raw[y * line_len:(y + 1) * line_len]
        for xb in range(bpl):
            b0, b1, b2, b3 = row[xb], row[bpl + xb], row[2 * bpl + xb], row[3 * bpl + xb]
            for bit in range(8):
                s = 7 - bit
                pix[y * w + xb * 8 + bit] = ((b0 >> s) & 1) | (((b1 >> s) & 1) << 1) | \
                                            (((b2 >> s) & 1) << 2) | (((b3 >> s) & 1) << 3)
    img = Pcx(w, lines, pix, pal, bpl, p - off)
    img.data_width = x2 - x1 + 1        # the EXE stores this as Seq.x2 (valid width)
    return img


# --------------------------------------------------------------------------- SCENERY.DAT
def read_scenery(path):
    """Header: N x 8 dwords {scene off,len, boundary off,len, overlay off,len, base off,len}.
    N is implied by the first offset (header size / 32). Scene = PCX; the three overlays
    are raw (uncompressed in the Windows v1 build) 8000-byte 1bpp bitmaps."""
    d = open(path, 'rb').read()
    n = u32(d, 0) // 32
    scenes = []
    for i in range(n):
        so, sl, bo, bl, oo, ol, ao, al = struct.unpack_from('<8I', d, i * 32)
        s = dict(index=i, scene=(so, sl), boundary=(bo, bl), overlay=(oo, ol), base=(ao, al))
        s['pcx'] = read_pcx(d, so)
        for k in ('boundary', 'overlay', 'base'):
            o, l = s[k]
            s[k + '_bits'] = d[o:o + OVL_SIZE] if l else bytes(OVL_SIZE)
        scenes.append(s)
    return scenes


# --------------------------------------------------------------------------- OBJECTS.DAT
def read_objects(path):
    """Header: 88 x {u32 offset, u32 length}; each object = consecutive PCX frames.
    How frames split into sequences (walk right/left/down/up ...) is NOT in the file:
    it comes from seqList[].imageNbr in the EXE's object table."""
    d = open(path, 'rb').read()
    n = u32(d, 0) // 8
    objs = []
    for i in range(n):
        off, ln = struct.unpack_from('<II', d, i * 8)
        frames, p = [], off
        while ln and p < off + ln:
            img = read_pcx(d, p); frames.append(img); p += img.size
        objs.append(dict(index=i, offset=off, length=ln, frames=frames))
    return objs


# --------------------------------------------------------------------------- SOUNDS.DAT
def read_sounds(path):
    """Header: 64 x {u16 size, u32 offset}. 0..11 are Standard MIDI files (music),
    the rest raw 8-bit unsigned PCM at 11025 Hz. Size 0 = unused slot."""
    d = open(path, 'rb').read()
    out = []
    for i in range(MAX_SOUNDS):
        size, off = struct.unpack_from('<HI', d, i * 6)
        if size:
            data = d[off:off + size]
            out.append(dict(index=i, offset=off, size=size, data=data,
                            kind='midi' if data[:4] == b'MThd' else 'pcm'))
    return out


# --------------------------------------------------------------------------- STRINGS.DAT
def decode_string(raw):
    """1010:556c: each byte minus the cypher byte (mod 256)."""
    return bytes((c - STRING_CYPHER[i % len(STRING_CYPHER)]) & 0xFF for i, c in enumerate(raw))


def read_strings(path):
    """Header: u32 offsets; string i = bytes [off[i], off[i+1]). Count = off[0]/4 - 1."""
    d = open(path, 'rb').read()
    n = u32(d, 0) // 4
    offs = [u32(d, 4 * i) for i in range(n)]
    return [decode_string(d[offs[i]:offs[i + 1]]).decode('cp1252', 'replace') for i in range(n - 1)]


# --------------------------------------------------------------------------- UIF.DAT
def read_uif(path):
    """Header: 32 x {u16 size, u32 offset} (0xC0 bytes, loaded into seg 1018).
    0..2 = bitmap fonts (5/6/8 px), 3 = PCX sheet of 32x32 inventory icons."""
    d = open(path, 'rb').read()
    items = {}
    for i in range(MAX_UIFS):
        size, off = struct.unpack_from('<HI', d, i * 6)
        if size or off:
            items[i] = dict(index=i, size=size, offset=off, data=d[off:off + size])
    if 3 in items:
        items[3]['pcx'] = read_pcx(d, items[3]['offset'])
    for i in (0, 1, 2):
        if i in items:
            items[i]['font'] = parse_font(items[i]['data'])
    return items


def parse_font(data):
    """Font blob (see 1010:28ee): [0]=height [1]=width, then for chars 1..127:
    u8 height, u8 width, height*ceil(width/8) bytes of bitmap, MSB = leftmost pixel
    (the EXE bit-reverses every byte after loading, 1010:61ea, then draws LSB-first)."""
    glyphs, p = {}, 2
    for ch in range(1, 128):
        h, w = data[p], data[p + 1]
        n = h * ((w + 7) >> 3)
        glyphs[ch] = (w, h, data[p + 2:p + 2 + n]); p += 2 + n
    return dict(height=data[0], width=data[1], glyphs=glyphs)


# --------------------------------------------------------------------------- HUGO.BSF
def read_boot(path):
    """struct {char checksum, registered, pbswitch[8], distrib[32]; u16 exitLen} (44 bytes)
    XORed with BOOT_CYPHER; the XOR of all 44 *encrypted* bytes must be 0.
    Followed by exitLen bytes of exit text, XORed with the same cypher restarting at 0
    (it is the shareware licence, identical to LICENSE.TXT)."""
    d = open(path, 'rb').read()
    enc = d[:44]
    chk = 0
    for c in enc: chk ^= c
    xor = lambda b: bytes(c ^ BOOT_CYPHER[i % len(BOOT_CYPHER)] for i, c in enumerate(b))
    p = xor(enc)
    exit_len = u16(p, 42)
    return dict(checksum_ok=(chk == 0), registered=p[1],
                pbswitch=p[2:10].split(b'\0')[0].decode('latin1'),
                distrib=p[10:42].split(b'\0')[0].decode('latin1'),
                exit_len=exit_len, exit_text=xor(d[44:44 + exit_len]).decode('cp1252'))


# --------------------------------------------------------------------------- HHHn.SAV
def read_save(path, n_points=None):
    """Save game layout (writer 1010:335a, reader 1010:34b2)."""
    d = open(path, 'rb').read()
    r = dict(file_length_field=u32(d, 0), actual_length=len(d),
             version=d[4:9].split(b'\0')[0].decode('latin1'),
             description=d[9:41].split(b'\0')[0].decode('latin1', 'replace'))
    p = 41 + NUM_OBJECTS * 0x54
    r['objects_bytes'] = NUM_OBJECTS * 0x54
    r['hero_image'], = struct.unpack_from('<B', d, p); p += 1
    r['score'], r['story_mode'], r['jump_exit'], r['game_over'] = struct.unpack_from('<4h', d, p); p += 8
    r['screen_states'] = list(d[p:p + 17]); p += 17
    n_points = n_points or 13                       # DGROUP:36fe in this build
    r['points'] = [struct.unpack_from('<BxH', d, p + 4 * i) for i in range(n_points)]; p += 4 * n_points
    tick, ev_base, free_i, head_i, tail_i = struct.unpack_from('<IHhhh', d, p); p += 12
    events = []
    for i in range(50):                             # 50 x 12-byte event records
        act, local, t, prev, nxt = struct.unpack_from('<HHIhh', d, p + 12 * i)
        events.append(dict(action_ptr=act, local=local, time=t, prev=prev, next=nxt))
    p += 600
    r['scheduler'] = dict(tick=tick, event_base=ev_base, free=free_i, head=head_i, tail=tail_i,
                          queued=[i for i, e in enumerate(events) if e['action_ptr']])
    r['palette_remap'] = list(struct.unpack_from('<256H', d, p)); p += 512
    r['maze'] = dict(zip(('enabled', 'size', 'x1', 'y1', 'x2', 'y2', 'x3', 'x4', 'first_screen'),
                         struct.unpack_from('<hB5hhB', d, p) if False else
                         (u16(d, p), d[p + 2], *struct.unpack_from('<6h', d, p + 4), d[p + 16])))
    p += 18
    r['trailing_bytes'] = len(d) - p
    return r


# --------------------------------------------------------------------------- output helpers
def ega_rgb(palette):
    # PCX header palettes in these files hold 8-bit RGB values; fall back to EGA defaults.
    if any(palette):
        return [c for rgb in palette for c in rgb]
    std = [(0,0,0),(0,0,170),(0,170,0),(0,170,170),(170,0,0),(170,0,170),(170,85,0),(170,170,170),
           (85,85,85),(85,85,255),(85,255,85),(85,255,255),(255,85,85),(255,85,255),(255,255,85),(255,255,255)]
    return [c for rgb in std for c in rgb]


def save_png(path, img, palette=None, crop_width=None):
    from PIL import Image
    im = Image.frombytes('P', (img.width, img.height), bytes(img.pixels))
    im.putpalette(ega_rgb(palette or img.palette) + [0] * (768 - 48))
    if crop_width:
        im = im.crop((0, 0, crop_width, img.height))
    im.save(path)


def save_bits_png(path, bits, w=XPIX, h=YPIX):
    from PIL import Image
    Image.frombytes('1', (w, h), bytes(bits)).save(path)


def save_font_png(path, font):
    from PIL import Image
    cw, ch = 10, font['height'] + 2
    im = Image.new('L', (16 * cw, 8 * ch), 0)
    for c, (w, h, bm) in font['glyphs'].items():
        bpr = (w + 7) >> 3
        ox, oy = (c % 16) * cw, (c // 16) * ch
        for y in range(h):
            for x in range(w):
                if bm[y * bpr + (x >> 3)] & (0x80 >> (x & 7)):
                    im.putpixel((ox + x, oy + y), 255)
    im.save(path)


# --------------------------------------------------------------------------- commands
def info(gd):
    j = lambda f: os.path.join(gd, f)
    sc = read_scenery(j('SCENERY.DAT'))
    print('SCENERY.DAT: %d screens, scene PCX %dx%d' % (len(sc), sc[0]['pcx'].width, sc[0]['pcx'].height))
    ob = read_objects(j('OBJECTS.DAT'))
    print('OBJECTS.DAT: %d object slots, %d with images, %d frames total' %
          (len(ob), sum(1 for o in ob if o['frames']), sum(len(o['frames']) for o in ob)))
    so = read_sounds(j('SOUNDS.DAT'))
    print('SOUNDS.DAT: %d MIDI, %d PCM (slots used: %s)' %
          (sum(s['kind'] == 'midi' for s in so), sum(s['kind'] == 'pcm' for s in so), [s['index'] for s in so]))
    st = read_strings(j('STRINGS.DAT'))
    print('STRINGS.DAT: %d strings; #0 = %r' % (len(st), st[0][:60]))
    ui = read_uif(j('UIF.DAT'))
    print('UIF.DAT: items %s; icon sheet %dx%d' % (sorted(ui), ui[3]['pcx'].width, ui[3]['pcx'].height))
    print('HUGO.BSF:', read_boot(j('HUGO.BSF')))
    if os.path.exists(j('HHH00.SAV')):
        print('HHH00.SAV:', read_save(j('HHH00.SAV')))


def dump(gd, out):
    j = lambda f: os.path.join(gd, f)
    os.makedirs(out, exist_ok=True)
    sub = lambda s: (os.makedirs(os.path.join(out, s), exist_ok=True), os.path.join(out, s))[1]
    d = sub('scenery')
    for s in read_scenery(j('SCENERY.DAT')):
        save_png(os.path.join(d, 'screen%02d.png' % s['index']), s['pcx'])
        for k in ('boundary', 'overlay', 'base'):
            save_bits_png(os.path.join(d, 'screen%02d_%s.png' % (s['index'], k)), s[k + '_bits'])
    d = sub('objects')
    for o in read_objects(j('OBJECTS.DAT')):
        for f, img in enumerate(o['frames']):
            save_png(os.path.join(d, 'obj%02d_f%02d.png' % (o['index'], f)), img, crop_width=img.data_width)
    d = sub('sounds')
    for s in read_sounds(j('SOUNDS.DAT')):
        if s['kind'] == 'midi':
            open(os.path.join(d, 'sound%02d.mid' % s['index']), 'wb').write(s['data'])
        else:
            w = wave.open(os.path.join(d, 'sound%02d.wav' % s['index']), 'wb')
            w.setnchannels(1); w.setsampwidth(1); w.setframerate(PCM_RATE); w.writeframes(s['data']); w.close()
    with open(os.path.join(out, 'strings.txt'), 'w', encoding='utf-8') as f:
        for i, s in enumerate(read_strings(j('STRINGS.DAT'))):
            f.write('%3d: %s\n' % (i, s.replace('\r', '').replace('\n', '\\n')))
    d = sub('uif')
    ui = read_uif(j('UIF.DAT'))
    save_png(os.path.join(d, 'icons.png'), ui[3]['pcx'])
    for i in (0, 1, 2):
        save_font_png(os.path.join(d, UIF_NAMES[i] + '.png'), ui[i]['font'])
    meta = dict(boot=read_boot(j('HUGO.BSF')))
    if os.path.exists(j('HHH00.SAV')):
        meta['HHH00.SAV'] = read_save(j('HHH00.SAV'))
    json.dump(meta, open(os.path.join(out, 'meta.json'), 'w'), indent=1)
    print('dumped to', out)


if __name__ == '__main__':
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    gd, cmd = sys.argv[1], sys.argv[2]
    {'info': lambda: info(gd), 'dump': lambda: dump(gd, sys.argv[3])}[cmd]()
