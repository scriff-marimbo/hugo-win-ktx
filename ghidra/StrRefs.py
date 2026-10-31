# For each function, find 16-bit immediates that point at a C string in DGROUP (seg 1070)
# @category Hugo
import json, re
from ghidra.program.model.scalar import Scalar
outdir = getScriptArgs()[0]
prog = currentProgram
mem = prog.getMemory()
blk = mem.getBlock("Data15")
base = blk.getStart()
n = blk.getSize()
buf = bytearray(n)
for i in range(n):
    buf[i] = mem.getByte(base.add(i)) & 0xff
strs = {}
def ok(c):
    return c >= 0x20 or c in (9, 10, 13)
i = 0
while i < n:
    j = i
    while j < n and buf[j] != 0 and ok(buf[j]):
        j += 1
    if j < n and buf[j] == 0 and j - i >= 3:
        strs[i] = u''.join(unichr(c) for c in buf[i:j])
    i = j + 1
res = {}
listing = prog.getListing()
for f in prog.getFunctionManager().getFunctions(True):
    if f.isThunk(): continue
    hits = []
    for ins in listing.getInstructions(f.getBody(), True):
        for op in range(ins.getNumOperands()):
            for o in ins.getOpObjects(op):
                if isinstance(o, Scalar):
                    v = o.getUnsignedValue() & 0xffff
                    if v in strs and v > 0x10 and strs[v] not in hits:
                        hits.append(strs[v])
    if hits:
        res[str(f.getEntryPoint())] = hits
json.dump(dict(strings={("%04x" % k): v for k, v in strs.items()}, refs=res), open(outdir + "/strrefs.json", "w"), indent=1)
print("STRREFS %d funcs, %d strings" % (len(res), len(strs)))
