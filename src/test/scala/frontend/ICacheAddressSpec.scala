package minixiangshan.frontend

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.VerilatorCFlags
import minixiangshan.config._
import minixiangshan.frontend.icache._
import minixiangshan.mmu._
import org.scalatest.flatspec.AnyFlatSpec

class ICacheAddressHarness(implicit p: Parameters) extends NSModule {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(UInt(64.W)))
    val resp = Decoupled(new Bundle {
      val addr = UInt(64.W)
      val inst = UInt(32.W)
    })
    val read = Decoupled(UInt(64.W))
    val refill = Flipped(Decoupled(UInt(32.W)))
  })
  val cache = Module(new ICache)
  val mmu = Module(new Mmu)
  cache.io.redirect := false.B
  cache.io.invalidate := false.B
  cache.io.cpu_req.valid := io.req.valid
  cache.io.cpu_req.bits.addr := io.req.bits
  io.req.ready := cache.io.cpu_req.ready
  io.resp.valid := cache.io.icache_resp.valid
  io.resp.bits.addr := cache.io.icache_resp.bits.addr
  io.resp.bits.inst := cache.io.icache_resp.bits.instrs(0)
  cache.io.icache_resp.ready := io.resp.ready
  cache.io.mmu.toMmu <> mmu.io.fromIcache
  cache.io.mmu.fromMmu <> mmu.io.toIcache
  mmu.io.fromCsr := 0.U.asTypeOf(new CsrToMmu)
  mmu.io.fromIcacheFlush := false.B
  mmu.io.fromMemFlush := false.B
  mmu.io.fromMem.valid := false.B
  mmu.io.fromMem.bits := 0.U.asTypeOf(mmu.io.fromMem.bits)
  mmu.io.toMem.ready := true.B
  mmu.io.tlbFlush := 0.U.asTypeOf(new TlbFlush)
  mmu.io.ptwRead.req.ready := false.B
  mmu.io.ptwRead.resp.valid := false.B
  mmu.io.ptwRead.resp.bits := 0.U.asTypeOf(mmu.io.ptwRead.resp.bits)
  io.read.valid := cache.io.l2_read.req.valid
  io.read.bits := cache.io.l2_read.req.bits.addr
  cache.io.l2_read.req.ready := io.read.ready
  cache.io.l2_read.resp.valid := io.refill.valid
  cache.io.l2_read.resp.bits.id := 0.U
  cache.io.l2_read.resp.bits.data := Fill(16, io.refill.bits)
  cache.io.l2_read.resp.bits.fullLine := true.B
  cache.io.l2_read.resp.bits.last := true.B
  io.refill.ready := cache.io.l2_read.resp.ready
}

class ICacheAddressSpec extends AnyFlatSpec with ChiselScalatestTester {
  for (pipt <- Seq(0, 1)) {
    it should s"distinguish cache lines with identical low 32 bits at PIPT=$pipt" in {
      implicit val p: Parameters = new Parameters(Map(
        DebugConfigKeys.UseBehavioralMemories -> true,
        CoreConfigKeys.NSetsI -> 16, CoreConfigKeys.usePIPT -> pipt))
      test(new ICacheAddressHarness).withAnnotations(Seq(
        VerilatorBackendAnnotation, VerilatorCFlags(Seq("-DWData=IData")))) { dut =>
        dut.io.req.valid.poke(false.B)
        dut.io.req.bits.poke(0.U)
        dut.io.resp.ready.poke(false.B)
        dut.io.read.ready.poke(true.B)
        dut.io.refill.valid.poke(false.B)
        dut.io.refill.bits.poke(0.U)
        dut.clock.step(2)
        def waitFor(cond: => Boolean): Unit = {
          var n = 0
          while (!cond && n < 60) { dut.clock.step(); n += 1 }
          assert(cond, "cache request timed out")
        }
        def fetch(addr: BigInt, inst: BigInt, miss: Boolean): Unit = {
          dut.io.req.bits.poke(addr.U)
          dut.io.req.valid.poke(true.B)
          waitFor(dut.io.req.ready.peek().litToBoolean)
          dut.clock.step()
          dut.io.req.valid.poke(false.B)
          if (miss) {
            waitFor(dut.io.read.valid.peek().litToBoolean)
            dut.io.read.bits.expect((addr & ~BigInt(63)).U)
            dut.clock.step()
            dut.io.refill.bits.poke(inst.U)
            dut.io.refill.valid.poke(true.B)
            waitFor(dut.io.refill.ready.peek().litToBoolean)
            dut.clock.step()
            dut.io.refill.valid.poke(false.B)
          }
          waitFor(dut.io.resp.valid.peek().litToBoolean)
          dut.io.read.valid.expect(false.B)
          dut.io.resp.bits.addr.expect(addr.U)
          dut.io.resp.bits.inst.expect(inst.U)
          dut.io.resp.ready.poke(true.B)
          dut.clock.step()
          dut.io.resp.ready.poke(false.B)
          dut.clock.step(2)
        }
        val low = BigInt("80001000", 16)
        val high = BigInt("180001000", 16)
        fetch(low, 0x13, miss = true)
        fetch(high, 0x100093, miss = true)
        fetch(low, 0x13, miss = false)
        fetch(high, 0x100093, miss = false)
      }
    }
  }
}
