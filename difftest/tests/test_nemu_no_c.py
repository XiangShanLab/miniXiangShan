#!/usr/bin/env python3
"""Exercise the built NEMU reference's RV64 no-C behavior through its public ABI."""

import argparse
import ctypes as ct
from pathlib import Path
import struct


BASE = 0x80000000
TRAP = BASE + 0x100
MASK64 = (1 << 64) - 1
CSR_NAMES = (
    "mode mstatus sstatus mepc sepc mtval stval mtvec stvec mcause scause "
    "satp mip mie mscratch sscratch mideleg medeleg pc"
).split()


class State(ct.Structure):
    _fields_ = [("gpr", ct.c_uint64 * 32)] + [
        (name, ct.c_uint64) for name in CSR_NAMES
    ]


def jal(rd, offset):
    imm = offset & 0x1FFFFF
    return ((imm >> 20) << 31 | ((imm >> 1) & 0x3FF) << 21
            | ((imm >> 11) & 1) << 20 | ((imm >> 12) & 0xFF) << 12
            | rd << 7 | 0x6F)


def jalr(rd, rs1, imm=0):
    return (imm & 0xFFF) << 20 | rs1 << 15 | rd << 7 | 0x67


def branch(funct3, rs1, rs2, offset):
    imm = offset & 0x1FFF
    return ((imm >> 12) << 31 | ((imm >> 5) & 0x3F) << 25 | rs2 << 20
            | rs1 << 15 | funct3 << 12 | ((imm >> 1) & 0xF) << 8
            | ((imm >> 11) & 1) << 7 | 0x63)


def csrrw(rd, csr, rs1):
    return csr << 20 | rs1 << 15 | 1 << 12 | rd << 7 | 0x73


class Reference:
    def __init__(self, path):
        self.lib = ct.CDLL(str(path.resolve()))
        size = ct.c_uint.in_dll(self.lib, "DIFFTEST_REG_SIZE").value
        assert size == ct.sizeof(State), (size, ct.sizeof(State))
        signatures = {
            "difftest_set_ramsize": [ct.c_size_t],
            "difftest_init_v2": [ct.c_uint],
            "difftest_regcpy": [ct.c_void_p, ct.c_bool],
            "difftest_csrcpy": [ct.c_void_p, ct.c_bool],
            "difftest_memcpy": [ct.c_uint64, ct.c_void_p, ct.c_size_t, ct.c_bool],
            "difftest_exec": [ct.c_uint64],
        }
        for name, signature in signatures.items():
            function = getattr(self.lib, name)
            function.argtypes = signature
            function.restype = None
        self.lib.difftest_set_ramsize(16 * 1024 * 1024)
        self.lib.difftest_init_v2(size)
        self.csrs = (ct.c_uint64 * 4096)()
        self.lib.difftest_csrcpy(self.csrs, False)
        assert not self.csrs[0x301] & 4, "reference still advertises misa.C"
        self.next_pc = BASE + 0x1000
        self.checked = 0

    def start(self, words, registers=None, mode=3, delegate=False):
        self.lib.difftest_csrcpy(self.csrs, True)
        before = State()
        for reg in range(1, 32):
            before.gpr[reg] = 0x12340000 + reg
        for reg, value in (registers or {}).items():
            before.gpr[reg] = value & MASK64
        before.mode = mode
        before.mstatus = 0xA00000000 | (3 << 11)
        before.mtvec = TRAP
        before.stvec = TRAP + 0x40
        before.mcause = 0xBAD
        before.scause = 0xBAD
        before.medeleg = 1 if delegate else 0
        before.pc = self.next_pc
        self.next_pc += 0x40
        data = ct.create_string_buffer(b"".join(struct.pack("<I", w) for w in words))
        self.lib.difftest_memcpy(before.pc, data, len(words) * 4, True)
        self.lib.difftest_regcpy(ct.byref(before), True)
        return before

    def step(self):
        self.lib.difftest_exec(1)
        after = State()
        self.lib.difftest_regcpy(ct.byref(after), False)
        return after

    def check(self, label, before, after, *, target=None, cause=None,
              tval=0, writes=None, delegated=False):
        expected = list(before.gpr)
        for reg, value in (writes or {}).items():
            if reg:
                expected[reg] = value
        assert list(after.gpr) == expected, f"{label}: GPR side effects"
        if cause is None:
            assert after.pc == target, f"{label}: pc={after.pc:#x}, expected {target:#x}"
            assert after.mcause == before.mcause, f"{label}: unexpected M trap"
            assert after.scause == before.scause, f"{label}: unexpected S trap"
        else:
            prefix = "s" if delegated else "m"
            for suffix, value in (("cause", cause), ("epc", before.pc), ("tval", tval)):
                actual = getattr(after, prefix + suffix)
                assert actual == value, f"{label}: {prefix}{suffix}={actual:#x}, expected {value:#x}"
            assert after.pc == (before.stvec if delegated else before.mtvec), label
            assert after.mode == (1 if delegated else 3), label
        self.checked += 1


def exercise(ref):
    # Include the upstream optimized JAL x0/JAL ra, JR, RET and rd==rs1 paths.
    for rd in (0, 1, 5):
        for offset in (2, -2, 8):
            before = ref.start([jal(rd, offset)])
            target = before.pc + offset
            ref.check(f"jal x{rd},{offset}", before, ref.step(), target=target,
                      cause=0 if offset & 3 else None, tval=target,
                      writes={} if offset & 3 else {rd: before.pc + 4})

    for rd, rs1 in ((0, 1), (0, 6), (1, 6), (5, 6), (1, 1), (5, 5)):
        for target in (BASE + 0x200, BASE + 0x201, BASE + 0x202, BASE + 0x203, 0x1002):
            for imm in (0, 7, -7):
                before = ref.start([jalr(rd, rs1, imm)], {rs1: target - imm})
                final = target & ~1
                ref.check(f"jalr x{rd},x{rs1},{imm} to {target:#x}", before, ref.step(),
                          target=final, cause=0 if final & 3 else None, tval=final,
                          writes={} if final & 3 else {rd: before.pc + 4})

    conditions = {
        0: lambda a, b: a == b,
        1: lambda a, b: a != b,
        4: lambda a, b: ct.c_int64(a).value < ct.c_int64(b).value,
        5: lambda a, b: ct.c_int64(a).value >= ct.c_int64(b).value,
        6: lambda a, b: a < b,
        7: lambda a, b: a >= b,
    }
    for funct3, condition in conditions.items():
        for rs1, rs2 in ((5, 6), (5, 0), (0, 6), (0, 0)):
            for a, b in ((0, 0), (1, 0), (0, 1), (MASK64, 1)):
                for offset in (2, 8):
                    before = ref.start([branch(funct3, rs1, rs2, offset)], {5: a, 6: b})
                    taken = condition(before.gpr[rs1], before.gpr[rs2])
                    target = before.pc + offset
                    ref.check(f"branch {funct3}/{rs1}/{rs2}/{a}/{b}/{offset}",
                              before, ref.step(), target=target if taken else before.pc + 4,
                              cause=0 if taken and offset & 3 else None, tval=target)

    for inst in (0xDEAD0001, 0xDEAD0085, 0xDEAD0040, 0xDEAD8082, 0):
        before = ref.start([inst])
        ref.check(f"compressed/illegal {inst:#x}", before, ref.step(), cause=2, tval=inst)

    # Trap delegation must preserve the target in stval and original PC in sepc.
    before = ref.start([jal(1, 2)], mode=1, delegate=True)
    ref.check("delegated misalignment", before, ref.step(), cause=0,
              tval=before.pc + 2, delegated=True)

    # Fixed IALIGN=32 makes EPC bits [1:0] read-only zero; xRET uses that value.
    for csr, epc, xret in ((0x341, "mepc", 0x30200073), (0x141, "sepc", 0x10200073)):
        for low in range(4):
            value = BASE + 0x200 + low
            before = ref.start([csrrw(0, csr, 5), xret], {5: value})
            after = ref.step()
            ref.check(f"write {epc} low={low}", before, after, target=before.pc + 4)
            assert getattr(after, epc) == value & ~3, epc
            returned = ref.step()
            ref.check(f"xret {epc} low={low}", after, returned, target=value & ~3)

    # A guest CSR write cannot re-enable C in this fixed build mode.
    misa = ref.csrs[0x301]
    before = ref.start([csrrw(5, 0x301, 6), 0x301023F3], {6: misa | 4})
    after = ref.step()
    ref.check("misa write ignored", before, after, target=before.pc + 4, writes={5: misa})
    ref.check("misa.C stays zero", after, ref.step(), target=before.pc + 8, writes={7: misa})
    print(f"PASS: {ref.checked} NEMU no-C checks", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", type=Path, default=(
        Path(__file__).resolve().parents[2] / "build/ref/riscv64-nemu-interpreter-so"
    ))
    exercise(Reference(parser.parse_args().ref))
