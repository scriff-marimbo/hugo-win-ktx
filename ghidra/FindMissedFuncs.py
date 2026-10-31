# Create functions in gaps of Code3 (1010) where a typical MSVC prologue appears.
# @category Hugo
from ghidra.app.cmd.disassemble import DisassembleCommand
mem = currentProgram.getMemory(); fm = currentProgram.getFunctionManager(); listing = currentProgram.getListing()
blk = mem.getBlock("Code3"); start = blk.getStart(); n = blk.getSize()
data = [mem.getByte(start.add(i)) & 0xff for i in range(n)]
pats = [[0x45, 0x55, 0x8b, 0xec, 0x1e], [0xc8], [0x55, 0x8b, 0xec], [0x8c, 0xd8, 0x90, 0x45, 0x55, 0x8b, 0xec, 0x1e]]
made = []
i = 0
while i < n - 8:
    a = start.add(i)
    if fm.getFunctionContaining(a) is None and listing.getInstructionContaining(a) is None:
        for p in pats:
            if data[i:i + len(p)] == p and (p != [0xc8] or data[i + 3] == 0):
                DisassembleCommand(a, None, True).applyTo(currentProgram)
                f = createFunction(a, None)
                if f:
                    made.append("%s %d" % (a, f.getBody().getNumAddresses()))
                    i += max(1, f.getBody().getNumAddresses()) - 1
                break
    i += 1
print("MADE %d: %s" % (len(made), ", ".join(made)))
