package minixiangshan.mem

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.VerilatorCFlags
import minixiangshan.TestInputs.zero
import minixiangshan.config._
import minixiangshan.backend.decode.LsuOp
import minixiangshan.mem.dcache.DCache
import org.scalatest.flatspec.AnyFlatSpec

class DCacheAddressHarness(implicit p: Parameters) extends NSModule {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(UInt(64.W)))
    val resp = Decoupled(UInt(64.W))
    val read = Decoupled(new Bundle {
      val addr = UInt(64.W)
      val id = UInt(1.W)
    })
    val refill = Flipped(Decoupled(new Bundle {
      val data = UInt(64.W)
      val id = UInt(1.W)
    }))
  })
  val cache = Module(new DCache)
  cache.io.loadReq.valid := io.req.valid
  cache.io.loadReq.bits := 0.U.asTypeOf(cache.io.loadReq.bits)
  cache.io.loadReq.bits.paddr := io.req.bits
  cache.io.loadReq.bits.vaddr := io.req.bits
  cache.io.loadReq.bits.cacheable := true.B
  cache.io.loadReq.bits.lsuOp := LsuOp.ld
  io.req.ready := cache.io.loadReq.ready
  io.resp.valid := cache.io.loadResp.valid
  io.resp.bits := cache.io.loadResp.bits.data
  cache.io.loadResp.ready := io.resp.ready
  cache.io.storeReq.valid := false.B
  cache.io.storeReq.bits := 0.U.asTypeOf(cache.io.storeReq.bits)
  cache.io.storeAck.ready := true.B
  cache.io.fenceReq := false.B
  cache.io.redirectInfo := 0.U.asTypeOf(cache.io.redirectInfo)
  io.read.valid := cache.io.l2.read.req.valid
  io.read.bits.addr := cache.io.l2.read.req.bits.addr
  io.read.bits.id := cache.io.l2.read.req.bits.id
  cache.io.l2.read.req.ready := io.read.ready
  cache.io.l2.read.resp.valid := io.refill.valid
  cache.io.l2.read.resp.bits.id := io.refill.bits.id
  cache.io.l2.read.resp.bits.data := Fill(8, io.refill.bits.data)
  cache.io.l2.read.resp.bits.fullLine := true.B
  cache.io.l2.read.resp.bits.last := true.B
  io.refill.ready := cache.io.l2.read.resp.ready
  cache.io.l2.write.req.ready := true.B
  cache.io.l2.write.done.valid := false.B
  cache.io.l2.write.done.bits := 0.U.asTypeOf(cache.io.l2.write.done.bits)
}

class DCacheAddressSpec extends AnyFlatSpec with ChiselScalatestTester {
  it should "keep high physical tags through miss refill and cache hits" in {
    implicit val p: Parameters = new Parameters(Map(
      DebugConfigKeys.UseBehavioralMemories -> true, CoreConfigKeys.NSetsD -> 16))
    test(new DCacheAddressHarness).withAnnotations(Seq(
      VerilatorBackendAnnotation, VerilatorCFlags(Seq("-DWData=IData")))) { dut =>
      zero(dut.io.refill.bits)
      dut.io.refill.valid.poke(false.B)
      dut.io.req.valid.poke(false.B)
      dut.io.req.bits.poke(0.U)
      dut.io.read.ready.poke(true.B)
      dut.io.resp.ready.poke(false.B)
      dut.clock.step(2)
      def waitFor(cond: => Boolean): Unit = {
        var n = 0
        while (!cond && n < 60) { dut.clock.step(); n += 1 }
        assert(cond, "DCache transaction timed out")
      }
      def load(addr: BigInt, data: BigInt, miss: Boolean): Unit = {
        dut.io.req.bits.poke(addr.U)
        dut.io.req.valid.poke(true.B)
        waitFor(dut.io.req.ready.peek().litToBoolean)
        dut.clock.step()
        dut.io.req.valid.poke(false.B)
        if (miss) {
          waitFor(dut.io.read.valid.peek().litToBoolean)
          dut.io.read.bits.addr.expect((addr & ~BigInt(63)).U)
          val id = dut.io.read.bits.id.peek().litValue
          dut.clock.step()
          dut.io.refill.valid.poke(true.B)
          dut.io.refill.bits.id.poke(id.U)
          dut.io.refill.bits.data.poke(data.U)
          waitFor(dut.io.refill.ready.peek().litToBoolean)
          dut.clock.step()
          dut.io.refill.valid.poke(false.B)
        }
        waitFor(dut.io.resp.valid.peek().litToBoolean)
        dut.io.read.valid.expect(false.B)
        dut.io.resp.bits.expect(data.U)
        dut.io.resp.ready.poke(true.B)
        dut.clock.step()
        dut.io.resp.ready.poke(false.B)
        dut.clock.step(3)
      }
      val low = BigInt("80001000", 16)
      val high = BigInt("180001000", 16)
      val a = BigInt("fedcba9876543210", 16)
      val b = BigInt("1234567887654321", 16)
      load(low, a, miss = true)
      load(high, b, miss = true)
      load(low, a, miss = false)
      load(high, b, miss = false)
    }
  }
}
