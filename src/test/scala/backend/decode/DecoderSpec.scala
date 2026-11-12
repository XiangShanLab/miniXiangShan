package minixiangshan.backend.decode

import chisel3._
import chiseltest._
import minixiangshan.TestInputs.zero
import minixiangshan.config._
import org.scalatest.flatspec.AnyFlatSpec

class DecoderSpec extends AnyFlatSpec with ChiselScalatestTester {
  it should "read both SFENCE operands and reject RV32-only CSR addresses" in {
    implicit val p: Parameters = new Parameters(Map.empty)
    test(new Decoder) { dut =>
      zero(dut.io.inData)
      dut.io.extInt.poke(false.B)
      dut.io.inData.instr.poke((0x12000073 | (5 << 15) | (6 << 20)).U)
      dut.io.out.rs1Valid.expect(true.B)
      dut.io.out.rs2Valid.expect(true.B)
      dut.io.out.rs1.expect(5.U)
      dut.io.out.rs2.expect(6.U)
      dut.io.out.ctrl.tlbOp.expect(TlbOp.sfence)
      dut.io.out.excp.excpVec.expect(0.U)
      for (addr <- Seq(0x310, 0x312, 0x313, 0xb80, 0xb82, 0xc80, 0xc81, 0xc82, 0x3a1, 0x3a3)) {
        dut.io.inData.instr.poke(((BigInt(addr) << 20) | 0x20f3).U) // csrrs x1, csr, x0
        dut.io.out.excp.excpVec.expect((BigInt(1) << ExcType.ILLEGAL.id).U)
      }
      for (addr <- Seq(0x180, 0x300, 0x301, 0xb00, 0xb02, 0xc00, 0xc02, 0x3a0, 0x3a2)) {
        dut.io.inData.instr.poke(((BigInt(addr) << 20) | 0x20f3).U)
        dut.io.out.excp.excpVec.expect(0.U)
      }
    }
  }
}
