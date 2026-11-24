package minixiangshan.mem

import chisel3._
import chiseltest._
import minixiangshan.TestInputs.zero
import minixiangshan.config._
import minixiangshan.mem.L2cache.L2Bridge
import org.scalatest.flatspec.AnyFlatSpec

class L2BridgeSpec extends AnyFlatSpec with ChiselScalatestTester {
  it should "preserve AXI4 bursts under independent address and data backpressure" in {
    implicit val p: Parameters = new Parameters(Map.empty)
    test(new L2Bridge) { d =>
      zero(d.io.client.write.req.bits)
      zero(d.io.client.read.req.bits)
      zero(d.io.axi.r.data)
      zero(d.io.axi.b.data)
      d.io.client.write.req.valid.poke(false.B)
      d.io.client.read.req.valid.poke(false.B)
      d.io.client.read.beat.ready.poke(false.B)
      d.io.client.write.done.ready.poke(false.B)
      d.io.axi.ar.arready.poke(false.B)
      d.io.axi.aw.awready.poke(false.B)
      d.io.axi.w.wready.poke(false.B)
      val address = BigInt("180000040", 16)
      val words = (0 until 8).map(i => BigInt(0x100 + i))
      for (dataFirst <- Seq(true, false)) {
        d.io.client.write.req.bits.addr.poke(address.U)
        d.io.client.write.req.bits.isLine.poke(true.B)
        d.io.client.write.req.bits.data.poke(words.zipWithIndex.map { case (w, i) => w << (64 * i) }.reduce(_ | _).U)
        d.io.client.write.req.valid.poke(true.B)
        d.clock.step()
        d.io.client.write.req.valid.poke(false.B)
        d.io.axi.aw.data.awaddr.expect(address.U)
        d.io.axi.aw.data.awlen.expect(7.U)
        d.io.axi.aw.data.awsize.expect(3.U)
        d.io.axi.aw.data.awburst.expect(1.U)
        if (!dataFirst) {
          d.io.axi.aw.awready.poke(true.B)
          d.clock.step()
          d.io.axi.aw.awready.poke(false.B)
        }
        for (i <- 0 until 8) {
          d.io.axi.w.data.wvalid.expect(true.B)
          d.io.axi.w.data.wdata.expect(words(i).U)
          d.io.axi.w.data.wstrb.expect(255.U)
          d.io.axi.w.data.wlast.expect((i == 7).B)
          d.clock.step(2)
          d.io.axi.w.data.wdata.expect(words(i).U)
          d.io.axi.w.wready.poke(true.B)
          d.clock.step()
          d.io.axi.w.wready.poke(false.B)
        }
        if (dataFirst) {
          d.io.axi.aw.data.awvalid.expect(true.B)
          d.io.axi.aw.data.awaddr.expect(address.U)
          d.io.axi.aw.awready.poke(true.B)
          d.clock.step()
          d.io.axi.aw.awready.poke(false.B)
        }
        d.io.axi.b.data.bvalid.poke(true.B)
        d.io.client.write.done.valid.expect(true.B)
        d.io.axi.b.bready.expect(false.B)
        d.io.client.read.req.ready.expect(false.B)
        d.clock.step(2)
        d.io.client.write.done.ready.poke(true.B)
        d.clock.step()
        d.io.axi.b.data.bvalid.poke(false.B)
        d.io.client.write.done.ready.poke(false.B)
      }
      d.io.client.read.req.bits.addr.poke(address.U)
      d.io.client.read.req.bits.isLine.poke(true.B)
      d.io.client.read.req.valid.poke(true.B)
      d.clock.step()
      d.io.client.read.req.valid.poke(false.B)
      d.io.axi.ar.data.araddr.expect(address.U)
      d.io.axi.ar.data.arlen.expect(7.U)
      d.io.axi.ar.data.arsize.expect(3.U)
      d.clock.step(2)
      d.io.axi.ar.data.arvalid.expect(true.B)
      d.io.axi.ar.arready.poke(true.B)
      d.clock.step()
      for (i <- 0 until 8) {
        d.io.axi.r.data.rvalid.poke(true.B)
        d.io.axi.r.data.rdata.poke(words(i).U)
        d.io.axi.r.data.rlast.poke((i == 7).B)
        d.io.axi.r.rready.expect(false.B)
        d.clock.step()
        d.io.client.read.beat.bits.data.expect(words(i).U)
        d.io.client.read.beat.ready.poke(true.B)
        d.clock.step()
        d.io.client.read.beat.ready.poke(false.B)
      }
      d.io.axi.r.data.rvalid.poke(false.B)
      d.io.client.read.req.ready.expect(true.B)
    }
  }
}
