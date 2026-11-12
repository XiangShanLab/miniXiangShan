package minixiangshan.frontend

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorCFlags
import firrtl.options.TargetDirAnnotation
import minixiangshan.TestInputs.zero
import minixiangshan.config._
import org.scalatest.flatspec.AnyFlatSpec

import scala.util.Random

class IFUSpec extends AnyFlatSpec with ChiselScalatestTester {
  it should "pair each cache request with prediction metadata under stalls and redirects" in {
    implicit val p: Parameters = new Parameters(Map.empty)
    test(new IFU).withAnnotations(Seq(VerilatorBackendAnnotation,
      VerilatorCFlags(Seq("-DWData=IData")), TargetDirAnnotation("target/ifu-handshake"))) { dut =>
      zero(dut.io.frontendRedirect)
      zero(dut.io.redirectInfo)
      zero(dut.io.predictResp)
      dut.io.idle.poke(false.B)
      dut.io.icache_req.ready.poke(false.B)
      dut.io.bpuInfoQueuEnq.ready.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.clock.step(3)
      val rng = new Random(0x1f00)
      def flag(b: Bool): Boolean = b.peek().litToBoolean
      for (n <- 0 until 1000) {
        val cacheReady = rng.nextBoolean()
        val metaReady = rng.nextBoolean()
        val idle = rng.nextInt(11) == 0
        val br = rng.nextInt(17) == 0
        val fr = rng.nextInt(19) == 0
        val bt = BigInt("ffffffff80002000", 16) + (n % 64) * 4
        val ft = BigInt("1234567880003000", 16) + (n % 64) * 4
        dut.io.icache_req.ready.poke(cacheReady.B)
        dut.io.bpuInfoQueuEnq.ready.poke(metaReady.B)
        dut.io.idle.poke(idle.B)
        dut.io.redirectInfo.valid.poke(br.B)
        dut.io.redirectInfo.bits.doRedirect.poke(br.B)
        dut.io.redirectInfo.bits.target.poke(bt.U)
        dut.io.frontendRedirect.valid.poke(fr.B)
        dut.io.frontendRedirect.target.poke(ft.U)
        dut.io.predictResp.taken.poke((n % 3 == 0).B)
        dut.io.predictResp.target.poke("hffffffff80004000".U)
        val pc = dut.io.icache_req.addr.peek().litValue
        val fire = flag(dut.io.icache_req.valid) && cacheReady
        dut.io.bpuInfoQueuEnq.valid.expect(fire.B)
        dut.io.predictFire.expect(fire.B)
        dut.io.bpuInfoQueuEnq.bits.pc.expect(pc.U)
        if (!metaReady || idle || br || fr) dut.io.icache_req.valid.expect(false.B)
        val sequential = if ((pc & 63) > 48) (pc & ~BigInt(63)) + 64 else pc + 16
        val expected = if (br) bt else if (fr) ft else if (!fire) pc
          else if (n % 3 == 0) BigInt("ffffffff80004000", 16) else sequential
        dut.clock.step()
        dut.io.icache_req.addr.expect(expected.U)
      }
    }
  }
}
