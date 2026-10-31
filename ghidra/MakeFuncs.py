# Disassemble and create functions at the given addresses
# @category Hugo
from ghidra.app.cmd.disassemble import DisassembleCommand
for a in getScriptArgs():
    ad = toAddr(a)
    DisassembleCommand(ad, None, True).applyTo(currentProgram)
    f = getFunctionAt(ad) or createFunction(ad, None)
    print("MK %s %s" % (a, f.getBody().getNumAddresses() if f else "FAIL"))
