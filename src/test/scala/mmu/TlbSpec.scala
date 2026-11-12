package minixiangshan.mmu

import chisel3._
import chiseltest._
import minixiangshan.TestInputs.zero
import minixiangshan.config._
import org.scalatest.flatspec.AnyFlatSpec

class TlbSpec extends AnyFlatSpec with ChiselScalatestTester {
  it should "retain full Sv39 VPN, PPN and ASID and preserve global entries on ASID fences" in {
    implicit val p: Parameters = new Parameters(Map.empty)
    test(new Tlb) { dut =>
      zero(dut.io.lookup.req)
      zero(dut.io.fill)
      zero(dut.io.flush)
      dut.clock.step(2)
      val va = BigInt("ffffffc123456000", 16)
      val ppn = BigInt("abcdef12345", 16)
      val asid = 0xabcd
      def fill(global: Boolean): Unit = {
        dut.io.fill.valid.poke(true.B)
        dut.io.fill.vpn.poke(((va >> 12) & ((BigInt(1) << 27) - 1)).U)
        dut.io.fill.ppn.poke(ppn.U)
        dut.io.fill.asid.poke(asid.U)
        dut.io.fill.r.poke(true.B)
        dut.io.fill.d.poke(false.B)
        dut.io.fill.g.poke(global.B)
        dut.clock.step()
        dut.io.fill.valid.poke(false.B)
      }
      def flush(noAsid: Boolean): Unit = {
        dut.io.flush.valid.poke(true.B)
        dut.io.flush.all.poke(true.B)
        dut.io.flush.noAsid.poke(noAsid.B)
        dut.io.flush.asid.poke(asid.U)
        dut.clock.step()
        dut.io.flush.valid.poke(false.B)
      }
      fill(global = false)
      dut.io.lookup.req.vaddr.poke(va.U)
      dut.io.lookup.req.asid.poke(asid.U)
      dut.io.lookup.resp.hit.expect(true.B)
      dut.io.lookup.resp.ppn.expect(ppn.U)
      dut.io.lookup.resp.d.expect(false.B)
      dut.io.lookup.req.vaddr.poke((va ^ (BigInt(1) << 32)).U)
      dut.io.lookup.resp.hit.expect(false.B)
      dut.io.lookup.req.vaddr.poke(va.U)
      dut.io.lookup.req.asid.poke((asid ^ 0x8000).U)
      dut.io.lookup.resp.hit.expect(false.B)
      dut.io.lookup.req.asid.poke(asid.U)
      flush(noAsid = false)
      dut.io.lookup.resp.hit.expect(false.B)
      fill(global = true)
      flush(noAsid = false)
      dut.io.lookup.req.asid.poke(0.U)
      dut.io.lookup.resp.hit.expect(true.B)
      flush(noAsid = true)
      dut.io.lookup.resp.hit.expect(false.B)
      // Address-targeted fences may overinvalidate: sibling 4 KiB entries
      // derived from one superpage must not survive the fence.
      fill(global = false)
      dut.io.fill.vpn.poke((((va + 4096) >> 12) & ((BigInt(1) << 27) - 1)).U)
      dut.io.fill.valid.poke(true.B)
      dut.clock.step()
      dut.io.fill.valid.poke(false.B)
      dut.io.flush.all.poke(false.B)
      dut.io.flush.noAsid.poke(false.B)
      dut.io.flush.vaddr.poke(va.U)
      dut.io.flush.asid.poke(asid.U)
      dut.io.flush.valid.poke(true.B)
      dut.clock.step()
      dut.io.flush.valid.poke(false.B)
      dut.io.lookup.req.asid.poke(asid.U)
      dut.io.lookup.resp.hit.expect(false.B)
      dut.io.lookup.req.vaddr.poke((va + 4096).U)
      dut.io.lookup.resp.hit.expect(false.B)
    }
  }
}
