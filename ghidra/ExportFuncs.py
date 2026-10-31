# Ghidra Jython: export functions, callers/callees, imported-API calls, string refs, and decompiled C
# @category Hugo
import json
from ghidra.app.decompiler import DecompInterface
from ghidra.util.task import ConsoleTaskMonitor
from ghidra.program.model.symbol import RefType

args = getScriptArgs()
outdir = args[0]
prog = currentProgram
fm = prog.getFunctionManager()
listing = prog.getListing()
refmgr = prog.getReferenceManager()
mon = ConsoleTaskMonitor()

def str_at(addr):
    d = listing.getDataAt(addr)
    if d is not None and d.hasStringValue():
        return d.getValue()
    return None

funcs = []
for f in fm.getFunctions(True):
    body = f.getBody()
    callees = sorted(set(c.getName() + "@" + str(c.getEntryPoint()) for c in f.getCalledFunctions(mon)))
    callers = sorted(set(str(c.getEntryPoint()) for c in f.getCallingFunctions(mon)))
    strings = []
    for rng in body:
        a = rng.getMinAddress()
        while a is not None and a.compareTo(rng.getMaxAddress()) <= 0:
            for r in refmgr.getReferencesFrom(a):
                s = str_at(r.getToAddress())
                if s and s not in strings:
                    strings.append(s)
            ins = listing.getInstructionAt(a)
            a = a.add(ins.getLength()) if ins else a.add(1)
    funcs.append(dict(addr=str(f.getEntryPoint()), name=f.getName(), size=body.getNumAddresses(),
                      thunk=f.isThunk(), external=f.isExternal(), callees=callees, callers=callers,
                      strings=strings[:40], sig=str(f.getSignature())))
json.dump(funcs, open(outdir + "/functions.json", "w"), indent=1)

# memory blocks + imports
blocks = [dict(name=b.getName(), start=str(b.getStart()), end=str(b.getEnd()), size=b.getSize(),
               x=b.isExecute(), w=b.isWrite(), init=b.isInitialized()) for b in prog.getMemory().getBlocks()]
ext = []
for s in prog.getSymbolTable().getExternalSymbols():
    ext.append(dict(name=s.getName(), ns=str(s.getParentNamespace())))
json.dump(dict(blocks=blocks, externals=ext), open(outdir + "/program.json", "w"), indent=1)

# decompile everything
di = DecompInterface(); di.openProgram(prog)
out = open(outdir + "/decompiled.c", "w")
for f in fm.getFunctions(True):
    if f.isExternal() or f.isThunk():
        continue
    res = di.decompileFunction(f, 60, mon)
    out.write("\n// ===== %s @ %s =====\n" % (f.getName(), f.getEntryPoint()))
    if res.decompileCompleted():
        out.write(res.getDecompiledFunction().getC())
    else:
        out.write("// decompile failed: %s\n" % res.getErrorMessage())
out.close()
print("EXPORTED %d functions" % len(funcs))
