#!/usr/bin/env python3
"""Extract files from an old (16-bit) Wise installer EXE.

Wise stores each file as a PKZIP-style local header (PK\\x03\\x04) followed by
raw-deflate data.  We scan for those headers, inflate, and check CRC32.
The first, unnamed entry is the Wise install script (saved as _wise_script.bin).
Files are placed in the layout the installer would create (paths taken from
%MAINDIR%\\... strings in the script); installer-only files go to _installer/.
Usage: wise_extract.py INSTALLER.EXE OUTDIR
"""
import os, re, struct, sys, zlib

def install_layout(script):
    """basename -> relative install path, from %MAINDIR%\\... strings in the Wise script."""
    m = {}
    for rel in re.findall(rb'%MAINDIR%\\([A-Za-z0-9_\\.]+)', script):
        rel = rel.decode().replace('\\', '/')
        m.setdefault(os.path.basename(rel).upper(), rel)
    if 'UNINSTAL.EXE' in m:  # Wise ships its uninstaller as UNWISE.EXE
        m['UNWISE.EXE'] = m['UNINSTAL.EXE']
    return m

def extract(path, outdir):
    d = open(path, 'rb').read()
    os.makedirs(outdir, exist_ok=True)
    i, n, layout = 0, 0, None
    while (i := d.find(b'PK\x03\x04', i)) >= 0:
        _ver, _flag, meth, _t, _dt, crc, csz, usz, nl, el = struct.unpack_from('<HHHHHIIIHH', d, i + 4)
        name = d[i + 30:i + 30 + nl].decode('latin1') or '_wise_script.bin'
        start = i + 30 + nl + el
        if meth != 8:
            i += 4; continue
        z = zlib.decompressobj(-15)
        data = z.decompress(d[start:start + csz] if csz else d[start:])
        ok = (zlib.crc32(data) & 0xffffffff) == crc
        if len(data) != usz or not ok:
            print(f'warn {name}: size {len(data)}/{usz} crc {"ok" if ok else "BAD"}')
        if layout is None:  # first entry is the script
            layout = install_layout(data)
        base = os.path.basename(name.replace('\\', '/'))
        rel = layout.get(base.upper(), '_installer/' + base)
        dst = os.path.join(outdir, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        open(dst, 'wb').write(data)
        name = rel
        print(f'{i:#08x} {usz:8d} {"crc ok " if ok else "crc BAD"} {name}')
        n += 1
        i = start + csz if csz else i + 4
    print(f'{n} files extracted to {outdir}')

if __name__ == '__main__':
    extract(sys.argv[1], sys.argv[2])
