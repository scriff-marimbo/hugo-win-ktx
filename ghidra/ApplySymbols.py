# Apply docs/symbols.csv (function names + comments) and docs/globals.csv (labels) to the program.
# Usage (headless): -postScript ApplySymbols.py /path/to/docs
# @category Hugo
import csv
from ghidra.program.model.symbol import SourceType
from ghidra.program.model.listing import CodeUnit
from ghidra.app.cmd.disassemble import DisassembleCommand
d = getScriptArgs()[0] if getScriptArgs() else askDirectory("docs folder", "OK").getPath()
fm = currentProgram.getFunctionManager()
n = 0
for row in csv.DictReader(open(d + "/symbols.csv")):
    a = toAddr(row["address"])
    f = fm.getFunctionAt(a)
    if f is None:  # functions only reached via message maps / vtables
        DisassembleCommand(a, None, True).applyTo(currentProgram)
        f = createFunction(a, None)
    if f is None:
        continue
    name = row["name"].replace("::", "__").replace(" ", "_").replace("(", "_").replace(")", "").replace("&", "ref").replace(",", "_")
    f.setName(name, SourceType.USER_DEFINED)
    c = "; ".join(x for x in (row["area"], ("ScummVM: " + row["scummvm_equivalent"]) if row["scummvm_equivalent"] else "", row["notes"]) if x)
    f.setComment(c)
    n += 1
for row in csv.DictReader(open(d + "/globals.csv")):
    try:
        a = toAddr(row["address"])
        createLabel(a, row["name"], True, SourceType.USER_DEFINED)
        if row["notes"]:
            setEOLComment(a, row["notes"])
    except Exception as e:
        print("label fail %s %s" % (row["address"], e))
print("APPLIED %d function names" % n)
