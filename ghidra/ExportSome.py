# Decompile the functions at the given addresses to <outfile>
# @category Hugo
from ghidra.app.decompiler import DecompInterface
from ghidra.util.task import ConsoleTaskMonitor
args = getScriptArgs(); out = open(args[0], "w")
di = DecompInterface(); di.openProgram(currentProgram)
for a in args[1:]:
    f = getFunctionAt(toAddr(a))
    if f is None: out.write("// none at %s\n" % a); continue
    r = di.decompileFunction(f, 60, ConsoleTaskMonitor())
    out.write("\n// ===== %s @ %s =====\n%s" % (f.getName(), a, r.getDecompiledFunction().getC() if r.decompileCompleted() else "fail"))
out.close()
