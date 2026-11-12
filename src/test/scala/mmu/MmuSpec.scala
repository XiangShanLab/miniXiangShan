package minixiangshan.mmu

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.VerilatorCFlags
import firrtl.options.TargetDirAnnotation
import minixiangshan.config._
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable
import scala.util.Random

// PTW consumes one PTE, so the test driver only needs an XLEN-wide data
// port. Keep the production 512-bit L2 interface inside the harness.
class MmuTestHarness(implicit p: Parameters) extends NSModule {
  val io = IO(new Bundle {
    val fromCsr = Input(new CsrToMmu)
    val fromIcache = Flipped(Decoupled(new IcacheToMmu))
    val toIcache = Decoupled(new MmuToIcache)
    val fromIcacheFlush = Input(Bool())
    val fromMem = Flipped(Decoupled(new SqToMmuReq))
    val toMem = Decoupled(new MmuToSqResp)
    val fromMemFlush = Input(Bool())
    val tlbFlush = Input(new TlbFlush)
    val ptwRead = new Bundle {
      val req = Decoupled(new minixiangshan.mem.L2cache.L2ReadReq(1))
      val resp = Flipped(Decoupled(new Bundle {
        val id = UInt(1.W)
        val data = UInt(XLEN.W)
        val fullLine = Bool()
        val last = Bool()
      }))
    }
  })
  val mmu = Module(new Mmu)
  mmu.io.fromCsr := io.fromCsr
  mmu.io.fromIcache <> io.fromIcache
  io.toIcache <> mmu.io.toIcache
  mmu.io.fromIcacheFlush := io.fromIcacheFlush
  mmu.io.fromMem <> io.fromMem
  io.toMem <> mmu.io.toMem
  mmu.io.fromMemFlush := io.fromMemFlush
  mmu.io.tlbFlush := io.tlbFlush
  io.ptwRead.req <> mmu.io.ptwRead.req
  mmu.io.ptwRead.resp.valid := io.ptwRead.resp.valid
  io.ptwRead.resp.ready := mmu.io.ptwRead.resp.ready
  mmu.io.ptwRead.resp.bits.id := io.ptwRead.resp.bits.id
  mmu.io.ptwRead.resp.bits.data := io.ptwRead.resp.bits.data
  mmu.io.ptwRead.resp.bits.fullLine := io.ptwRead.resp.bits.fullLine
  mmu.io.ptwRead.resp.bits.last := io.ptwRead.resp.bits.last
}

class MmuSpec extends AnyFlatSpec with ChiselScalatestTester {
  for (xlen <- Seq(64)) {
    it should s"preserve MMU responses and sustain independent Bare data traffic at XLEN=$xlen" in {
      implicit val p: Parameters = new Parameters(Map(CoreConfigKeys.XLEN -> xlen))
      // chiseltest 0.6 uses WData for wide ports; recent Verilator calls it IData.
      test(new MmuTestHarness).withAnnotations(Seq(VerilatorBackendAnnotation,
        VerilatorCFlags(Seq("-DWData=IData")),
        TargetDirAnnotation(s"target/mmu-test-$xlen"))) { dut =>
        dut.clock.setTimeout(0)
        case class Request(addr: BigInt, op: Int = 3)
        val mem = mutable.Queue.empty[Request]
        val fetch = mutable.Queue.empty[BigInt]
        var accepted = 0
        var cycles = 0

        def flag(value: Bool): Boolean = value.peek().litToBoolean
        def cacheable(addr: BigInt): Boolean = {
          !(addr >= BigInt("30000000", 16) && addr < BigInt("80000000", 16))
        }
        def misaligned(req: Request): Boolean = {
          val size = req.op match {
            case 1 | 4 | 6 => 1
            case 2 | 5 | 7 => 2
            case 11 | 13 => 8
            case _ => 4
          }
          req.addr % size != 0
        }
        def checkMem(req: Request, pageFault: Boolean = false): Unit = {
          dut.io.toMem.bits.paddr.expect(req.addr.U)
          dut.io.toMem.bits.cacheable.expect(cacheable(req.addr).B)
          dut.io.toMem.bits.error.misalign.expect(misaligned(req).B)
          dut.io.toMem.bits.error.pageFault.expect(pageFault.B)
          dut.io.toMem.bits.error.accessFault.expect(false.B)
          dut.io.toMem.bits.hasError.expect((misaligned(req) || pageFault).B)
        }
        def inputs(): Unit = {
          dut.io.fromCsr.satp.poke(0.U)
          dut.io.fromCsr.priv.poke(1.U)
          dut.io.fromCsr.dataPriv.poke(1.U)
          dut.io.fromCsr.sum.poke(false.B)
          dut.io.fromCsr.mxr.poke(false.B)
          dut.io.fromIcache.valid.poke(false.B)
          dut.io.fromIcache.bits.vaddr.poke(0.U)
          dut.io.fromMem.valid.poke(false.B)
          dut.io.fromMem.bits.vaddr.poke(0.U)
          dut.io.fromMem.bits.lsuOp.poke(3.U)
          dut.io.toIcache.ready.poke(true.B)
          dut.io.toMem.ready.poke(true.B)
          dut.io.fromIcacheFlush.poke(false.B)
          dut.io.fromMemFlush.poke(false.B)
          dut.io.tlbFlush.valid.poke(false.B)
          dut.io.tlbFlush.all.poke(true.B)
          dut.io.tlbFlush.noAsid.poke(true.B)
          dut.io.tlbFlush.vaddr.poke(0.U)
          dut.io.tlbFlush.asid.poke(0.U)
          dut.io.ptwRead.req.ready.poke(true.B)
          dut.io.ptwRead.resp.valid.poke(false.B)
          dut.io.ptwRead.resp.bits.id.poke(0.U)
          dut.io.ptwRead.resp.bits.data.poke(0.U)
          dut.io.ptwRead.resp.bits.fullLine.poke(false.B)
          dut.io.ptwRead.resp.bits.last.poke(true.B)
        }
        def reset(): Unit = {
          inputs()
          dut.reset.poke(true.B)
          dut.clock.step(2)
          dut.reset.poke(false.B)
          mem.clear()
          fetch.clear()
        }
        def tick(m: Option[Request] = None, i: Option[BigInt] = None,
                 mr: Boolean = true, ir: Boolean = true,
                 mf: Boolean = false, inf: Boolean = false): (Boolean, Boolean) = {
          dut.io.fromMem.valid.poke(m.nonEmpty.B)
          m.foreach { r =>
            dut.io.fromMem.bits.vaddr.poke(r.addr.U)
            dut.io.fromMem.bits.lsuOp.poke(r.op.U)
          }
          dut.io.fromIcache.valid.poke(i.nonEmpty.B)
          i.foreach(a => dut.io.fromIcache.bits.vaddr.poke(a.U))
          dut.io.toMem.ready.poke(mr.B)
          dut.io.toIcache.ready.poke(ir.B)
          dut.io.fromMemFlush.poke(mf.B)
          dut.io.fromIcacheFlush.poke(inf.B)
          dut.io.toMem.valid.expect(mem.nonEmpty.B)
          dut.io.toIcache.valid.expect(fetch.nonEmpty.B)
          mem.headOption.foreach(checkMem(_))
          fetch.headOption.foreach { a =>
            dut.io.toIcache.bits.paddr.expect(a.U)
            dut.io.toIcache.bits.cacheable.expect(cacheable(a).B)
            dut.io.toIcache.bits.error.misalign.expect((a % 4 != 0).B)
            dut.io.toIcache.bits.error.pageFault.expect(false.B)
            dut.io.toIcache.bits.error.accessFault.expect(false.B)
            dut.io.toIcache.bits.hasError.expect((a % 4 != 0).B)
          }
          dut.io.ptwRead.req.valid.expect(false.B)
          if (mr && mem.nonEmpty) mem.dequeue()
          if (ir && fetch.nonEmpty) fetch.dequeue()
          val ma = m.nonEmpty && flag(dut.io.fromMem.ready)
          val ia = i.nonEmpty && flag(dut.io.fromIcache.ready)
          if (ma) { mem.enqueue(m.get); accepted += 1 }
          if (ia) fetch.enqueue(i.get)
          if (mf) mem.clear()
          if (inf) fetch.clear()
          dut.clock.step()
          cycles += 1
          (ma, ia)
        }
        def drain(): Unit = {
          for (_ <- 0 until 4) tick()
          assert(mem.isEmpty && fetch.isEmpty)
        }

        reset()
        // Both Bare ports must accept a request on every cycle.
        for (n <- 0 until 128) {
          val (ma, ia) = tick(Some(Request(BigInt("80000000", 16) + n * 8)),
            Some(BigInt("80001000", 16) + n * 4))
          assert(ma && ia)
        }
        drain()
        val bases = Seq("00000000", "2ffffff8", "30000000", "3ffffff8",
          "40000000", "7ffffff8", "80000000", "fffffff8").map(BigInt(_, 16)) ++
          (if (xlen == 64) Seq(BigInt("1234567880000000", 16),
            BigInt("ffffffff40000000", 16)) else Seq.empty)
        for (base <- bases; op <- 1 to 13; offset <- 0 until 8) {
          val (ma, ia) = tick(Some(Request(base + offset, op)), Some(base + offset))
          assert(ma && ia)
        }
        drain()

        for (seed <- 0 until 4) {
          val rng = new Random(0x4d4d5500L + seed)
          var sourceM = Option.empty[Request]
          var sourceI = Option.empty[BigInt]
          for (_ <- 0 until 2000) {
            if (sourceM.isEmpty && rng.nextInt(4) != 0)
              sourceM = Some(Request(BigInt(xlen, rng), 1 + rng.nextInt(13)))
            if (sourceI.isEmpty && rng.nextBoolean())
              sourceI = Some(BigInt(xlen, rng))
            val mf = rng.nextInt(31) == 0
            val inf = rng.nextInt(17) == 0
            val (ma, ia) = tick(sourceM, sourceI, rng.nextBoolean(),
              rng.nextBoolean(), mf, inf)
            if (ma || mf) sourceM = None
            if (ia || inf) sourceI = None
          }
          drain()
        }

        // Hold a response, overwrite request wires, then reset with both ports
        // occupied. No old response may escape after reset.
        tick(Some(Request(BigInt("4000f000", 16), 8)), Some(BigInt("80000000", 16)))
        for (_ <- 0 until 16)
          tick(Some(Request(BigInt("80000200", 16))), mr = false, ir = false)
        reset()
        tick()

        def waitFor(cond: => Boolean): Unit = {
          var n = 0
          while (!cond && n < 50) { dut.clock.step(); n += 1 }
          assert(cond, "MMU transaction timed out")
        }
        def mode(paging: Boolean): Unit =
          dut.io.fromCsr.satp.poke((if (paging) BigInt("8000000000000000", 16) else BigInt(0)).U)
        def request(addr: BigInt, op: Int = 3, instruction: Boolean = false): Unit = {
          if (instruction) {
            dut.io.fromIcache.bits.vaddr.poke(addr.U)
            dut.io.fromIcache.valid.poke(true.B)
            waitFor(flag(dut.io.fromIcache.ready))
            dut.clock.step()
            dut.io.fromIcache.valid.poke(false.B)
          } else {
            dut.io.fromMem.bits.vaddr.poke(addr.U)
            dut.io.fromMem.bits.lsuOp.poke(op.U)
            dut.io.fromMem.valid.poke(true.B)
            waitFor(flag(dut.io.fromMem.ready))
            dut.clock.step()
            dut.io.fromMem.valid.poke(false.B)
          }
        }
        def pteRead(addr: BigInt, pte: BigInt): Unit = {
          waitFor(flag(dut.io.ptwRead.req.valid))
          dut.io.ptwRead.req.bits.addr.expect(addr.U)
          dut.io.ptwRead.req.bits.size.expect(3.U)
          dut.io.ptwRead.req.bits.uncache.expect(true.B)
          dut.io.ptwRead.req.ready.poke(false.B)
          dut.clock.step(3)
          dut.io.ptwRead.req.valid.expect(true.B)
          dut.io.ptwRead.req.bits.addr.expect(addr.U)
          dut.io.ptwRead.req.ready.poke(true.B)
          dut.clock.step()
          dut.io.ptwRead.resp.bits.data.poke(pte.U)
          dut.io.ptwRead.resp.valid.poke(true.B)
          waitFor(flag(dut.io.ptwRead.resp.ready))
          dut.clock.step()
          dut.io.ptwRead.resp.valid.poke(false.B)
        }
        def consume(addr: BigInt, op: Int = 3, fault: Boolean = false): Unit = {
          waitFor(flag(dut.io.toMem.valid))
          if (fault) {
            dut.io.toMem.bits.error.pageFault.expect(true.B)
            dut.io.toMem.bits.hasError.expect(true.B)
          } else checkMem(Request(addr, op))
          dut.io.toMem.ready.poke(true.B)
          dut.clock.step()
        }
        def flushTlb(): Unit = {
          dut.io.tlbFlush.valid.poke(true.B)
          dut.clock.step()
          dut.io.tlbFlush.valid.poke(false.B)
        }

        // A Bare response must survive a switch to paging and block a new
        // translated data request until the old response has been consumed.
        dut.io.toMem.ready.poke(false.B)
        request(BigInt("4000f000", 16), 8)
        mode(true)
        dut.io.fromMem.valid.poke(true.B)
        dut.io.fromMem.bits.vaddr.poke("h1234".U)
        for (_ <- 0 until 4) {
          dut.io.fromMem.ready.expect(false.B)
          dut.io.toMem.valid.expect(true.B)
          checkMem(Request(BigInt("4000f000", 16), 8))
          dut.clock.step()
        }
        dut.io.fromMem.valid.poke(false.B)
        consume(BigInt("4000f000", 16), 8)
        request(0x1234)
        // Root -> PA 0x1000 -> PA 0x2000; leaf maps to PA 0x9000.
        pteRead(0, 0x401)
        pteRead(0x1000, 0x801)
        pteRead(0x2008, 0x24cf)
        consume(0x9234)
        // TLB hit, store permission, instruction permission, SUM and MXR.
        request(0x1234, 8)
        dut.io.ptwRead.req.valid.expect(false.B)
        consume(0x9234, 8)
        request(0x1234, instruction = true)
        waitFor(flag(dut.io.toIcache.valid))
        dut.io.toIcache.bits.paddr.expect("h9234".U)
        dut.io.toIcache.bits.hasError.expect(false.B)
        dut.clock.step()

        // Switching back to Bare must not replace a stalled translated response.
        dut.io.toMem.ready.poke(false.B)
        request(0x1234)
        waitFor(flag(dut.io.toMem.valid))
        mode(false)
        dut.io.fromMem.valid.poke(true.B)
        dut.io.fromMem.bits.vaddr.poke("h80000300".U)
        for (_ <- 0 until 4) {
          dut.io.fromMem.ready.expect(false.B)
          checkMem(Request(0x9234))
          dut.clock.step()
        }
        dut.io.fromMem.valid.poke(false.B)
        consume(0x9234)
        request(BigInt("80000300", 16))
        consume(BigInt("80000300", 16))

        mode(true)
        flushTlb()
        request(0x1234)
        pteRead(0, 0) // Invalid PTE.
        consume(0, fault = true)
        flushTlb()
        request(0x1234)
        pteRead(0, 0x401)
        pteRead(0x1000, 0x801)
        pteRead(0x2008, 0x24d9) // Execute-only U page.
        consume(0, fault = true) // S-mode load: SUM=0, MXR=0.
        dut.io.fromCsr.sum.poke(true.B)
        dut.io.fromCsr.mxr.poke(true.B)
        request(0x1234)
        consume(0x9234)
        request(0x1234, 8)
        consume(0, 8, fault = true)
        request(0x1234, instruction = true)
        waitFor(flag(dut.io.toIcache.valid))
        dut.io.toIcache.bits.error.pageFault.expect(true.B)
        dut.clock.step()
        dut.io.fromCsr.priv.poke(0.U)
        request(0x1234, instruction = true)
        waitFor(flag(dut.io.toIcache.valid))
        dut.io.toIcache.bits.paddr.expect("h9234".U)
        dut.io.toIcache.bits.hasError.expect(false.B)
        dut.clock.step()

        // A repeated redirect must keep draining the old walk. A new data
        // request remains blocked, then succeeds after the old read returns.
        reset()
        mode(true)
        request(0x2234)
        waitFor(flag(dut.io.ptwRead.req.valid))
        dut.clock.step()
        dut.io.fromMemFlush.poke(true.B)
        dut.clock.step(3)
        dut.io.fromMemFlush.poke(false.B)
        dut.io.fromMem.valid.poke(true.B)
        for (_ <- 0 until 3) {
          dut.io.fromMem.ready.expect(false.B)
          dut.io.toMem.valid.expect(false.B)
          dut.clock.step()
        }
        dut.io.fromMem.valid.poke(false.B)
        dut.io.ptwRead.resp.valid.poke(true.B)
        dut.io.ptwRead.resp.bits.data.poke(0.U)
        waitFor(flag(dut.io.ptwRead.resp.ready))
        dut.clock.step()
        dut.io.ptwRead.resp.valid.poke(false.B)
        dut.clock.step(3)
        dut.io.toMem.valid.expect(false.B)
        mode(false)
        request(BigInt("80000400", 16))
        consume(BigInt("80000400", 16))

        // A Bare data request can also complete while an instruction walk waits.
        reset()
        mode(true)
        request(0x3234, instruction = true)
        waitFor(flag(dut.io.ptwRead.req.valid))
        dut.clock.step()
        mode(false)
        request(BigInt("80000500", 16))
        consume(BigInt("80000500", 16))
        dut.io.fromIcacheFlush.poke(true.B)
        dut.clock.step(3)
        dut.io.fromIcacheFlush.poke(false.B)
        dut.io.ptwRead.resp.valid.poke(true.B)
        dut.io.ptwRead.resp.bits.data.poke(0.U)
        waitFor(flag(dut.io.ptwRead.resp.ready))
        dut.clock.step()
        dut.io.ptwRead.resp.valid.poke(false.B)
        dut.clock.step(3)
        dut.io.toIcache.valid.expect(false.B)

        // Fetch responses obey the same cross-mode ordering as data responses.
        reset()
        dut.io.toIcache.ready.poke(false.B)
        request(BigInt("4000f002", 16), instruction = true)
        mode(true)
        dut.io.fromIcache.valid.poke(true.B)
        dut.io.fromIcache.bits.vaddr.poke("h1234".U)
        for (_ <- 0 until 4) {
          dut.io.fromIcache.ready.expect(false.B)
          dut.io.toIcache.valid.expect(true.B)
          dut.io.toIcache.bits.paddr.expect("h4000f002".U)
          dut.io.toIcache.bits.hasError.expect(true.B)
          dut.clock.step()
        }
        // Even pending fetch demand behind a held Bare response must not
        // prevent the other port from entering the shared paged path.
        request(0x1234)
        pteRead(0, 0x401)
        pteRead(0x1000, 0x801)
        pteRead(0x2008, 0x24cf)
        consume(0x9234)
        dut.io.fromIcache.valid.poke(false.B)
        dut.io.toIcache.bits.paddr.expect("h4000f002".U)
        dut.io.toIcache.ready.poke(true.B)
        dut.clock.step()
        dut.io.toIcache.ready.poke(false.B)
        request(0x1234, instruction = true)
        waitFor(flag(dut.io.toIcache.valid))
        mode(false)
        dut.io.fromIcache.valid.poke(true.B)
        dut.io.fromIcache.bits.vaddr.poke("h80000600".U)
        for (_ <- 0 until 4) {
          dut.io.fromIcache.ready.expect(false.B)
          dut.io.toIcache.bits.paddr.expect("h9234".U)
          dut.io.toIcache.bits.hasError.expect(false.B)
          dut.clock.step()
        }
        dut.io.fromIcache.valid.poke(false.B)
        dut.io.toIcache.ready.poke(true.B)
        dut.clock.step()
        request(BigInt("80000600", 16), instruction = true)
        dut.io.toIcache.valid.expect(true.B)
        dut.io.toIcache.bits.paddr.expect("h80000600".U)
        dut.clock.step()

        // Repeated fetch flushes drain a paged walk before admitting Bare fetch.
        reset()
        mode(true)
        request(0x2234, instruction = true)
        waitFor(flag(dut.io.ptwRead.req.valid))
        dut.clock.step()
        mode(false)
        dut.io.fromIcacheFlush.poke(true.B)
        dut.io.fromIcache.valid.poke(true.B)
        dut.io.fromIcache.bits.vaddr.poke("h80000700".U)
        for (_ <- 0 until 4) {
          dut.io.fromIcache.ready.expect(false.B)
          dut.io.toIcache.valid.expect(false.B)
          dut.clock.step()
        }
        dut.io.fromIcacheFlush.poke(false.B)
        dut.io.fromIcache.valid.poke(false.B)
        dut.io.ptwRead.resp.valid.poke(true.B)
        dut.io.ptwRead.resp.bits.data.poke(0.U)
        waitFor(flag(dut.io.ptwRead.resp.ready))
        dut.clock.step()
        dut.io.ptwRead.resp.valid.poke(false.B)
        dut.clock.step(3)
        dut.io.toIcache.valid.expect(false.B)
        request(BigInt("80000700", 16), instruction = true)
        dut.io.toIcache.bits.paddr.expect("h80000700".U)
        dut.clock.step()
        // Sv39 high addresses, all leaf levels, A/D faults and M-mode bypass.
        reset()
        mode(true)
        def leaf(pa: BigInt, flags: Int = 0xcf): BigInt = (pa >> 12 << 10) | flags
        val va = BigInt("ffffffc123456234", 16)
        val pa = BigInt("123459000", 16)
        val rootIndex = ((va >> 30) & 511) * 8
        val midIndex = ((va >> 21) & 511) * 8
        val lowIndex = ((va >> 12) & 511) * 8
        request(va)
        pteRead(rootIndex, 0x401)
        pteRead(0x1000 + midIndex, 0x801)
        pteRead(0x2000 + lowIndex, leaf(pa))
        consume(pa + (va & 4095))
        request(va)
        consume(pa + (va & 4095)) // Full-width TLB hit.

        flushTlb()
        request(va)
        pteRead(rootIndex, leaf(BigInt("180000000", 16)))
        consume(BigInt("180000000", 16) + (va & ((BigInt(1) << 30) - 1)))
        flushTlb()
        request(va)
        pteRead(rootIndex, 0x401)
        pteRead(0x1000 + midIndex, leaf(BigInt("120000000", 16)))
        consume(BigInt("120000000", 16) + (va & ((BigInt(1) << 21) - 1)))

        flushTlb()
        request(va)
        pteRead(rootIndex, leaf(0x9000)) // Misaligned 1 GiB leaf.
        consume(0, fault = true)
        for (bad <- Seq(leaf(0, 0x0f), leaf(0) | (BigInt(1) << 63),
          BigInt(0x411))) {
          flushTlb()
          request(va)
          pteRead(rootIndex, bad)
          consume(0, fault = true)
        }
        flushTlb()
        request(va)
        pteRead(rootIndex, leaf(0, 0x4f)) // A set, D clear: read succeeds.
        consume(va & ((BigInt(1) << 30) - 1))
        request(va, 8) // TLB must retain D=0 and fault a later store.
        consume(0, 8, fault = true)
        flushTlb()
        request(va, 8)
        pteRead(rootIndex, leaf(0, 0x4f))
        consume(0, 8, fault = true)
        request(BigInt("0000008000001234", 16)) // Noncanonical VA.
        consume(0, fault = true)
        dut.io.ptwRead.req.valid.expect(false.B)

        // satp does not translate M-mode fetch or effective M-mode data.
        dut.io.fromCsr.priv.poke(3.U)
        dut.io.fromCsr.dataPriv.poke(3.U)
        request(va)
        consume(va)
        request(va, instruction = true)
        waitFor(flag(dut.io.toIcache.valid))
        dut.io.toIcache.bits.paddr.expect(va.U)
        dut.clock.step()
        dut.io.fromCsr.dataPriv.poke(1.U)
        flushTlb()
        request(va)
        pteRead(rootIndex, leaf(0))
        consume(va & ((BigInt(1) << 30) - 1))
        println(s"MMU XLEN=$xlen: $cycles Bare scoreboard cycles, $accepted accepted data requests; Sv39/flush/mode checks passed")
      }
    }
  }
}
