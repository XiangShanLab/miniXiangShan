package minixiangshan.frontend

import chisel3._
import chiseltest._
import minixiangshan.TestInputs.zero
import minixiangshan.config._
import org.scalatest.flatspec.AnyFlatSpec

class PredecoderSpec extends AnyFlatSpec with ChiselScalatestTester {
  it should "preserve high PCs and sign extend backward JAL targets" in {
    implicit val p: Parameters = new Parameters(Map.empty)
    test(new Predecoder) { dut =>
      zero(dut.io.icacheResp.bits)
      zero(dut.io.bpuInfo)
      dut.io.flush.poke(false.B)
      dut.io.bpuInfoValid.poke(true.B)
      dut.io.icacheResp.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.clock.step(2)
      val pc = BigInt("ffffffc180001000", 16)
      dut.io.icacheResp.bits.addr.poke(pc.U)
      dut.io.icacheResp.bits.instvalids(0).poke(true.B)
      dut.io.icacheResp.bits.instrs(0).poke("hffdff06f".U) // jal x0, -4
      dut.io.icacheResp.valid.poke(true.B)
      dut.clock.step()
      dut.io.icacheResp.valid.poke(false.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.pcs(0).expect(pc.U)
      dut.io.out.bits.pdInfo(0).jumpTarget.expect((pc - 4).U)
      dut.io.out.bits.bpuInfo(0).fallThrough.expect((pc + 4).U)
      dut.clock.step()
      dut.io.out.bits.frontendRedirect.valid.expect(true.B)
      dut.io.out.bits.frontendRedirect.target.expect((pc - 4).U)
    }
  }
}
