package minixiangshan.mmu

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.VerilatorCFlags
import firrtl.options.TargetDirAnnotation
import minixiangshan.backend.execute.ExeResult
import minixiangshan.config._
import minixiangshan.mem.MemAddrTrans
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable
import scala.util.Random

class MemAddrTransMmuHarness(implicit p: Parameters) extends NSModule {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new Bundle {
      val addr = UInt(XLEN.W)
      val tag = UInt(32.W)
      val op = UInt(4.W)
    }))
    val out = Decoupled(new Bundle {
      val addr = UInt(XLEN.W)
      val tag = UInt(32.W)
      val op = UInt(4.W)
      val paddr = UInt(XLEN.W)
      val cacheable = Bool()
      val misalign = Bool()
      val hasError = Bool()
      val scSuccess = Bool()
    })
    val flush = Input(Bool())
    val fetch = Input(Bool())
    val lrValid = Input(Bool())
  })
  val trans = Module(new MemAddrTrans)
  val mmu = Module(new Mmu)
  trans.io.in.valid := io.in.valid
  io.in.ready := trans.io.in.ready
  trans.io.in.bits := 0.U.asTypeOf(new ExeResult)
  trans.io.in.bits.data := io.in.bits.addr
  trans.io.in.bits.uop.pc := io.in.bits.tag
  trans.io.in.bits.uop.ctrl.lsuOp := io.in.bits.op
  trans.io.mmuReq <> mmu.io.fromMem
  trans.io.mmuResp <> mmu.io.toMem
  trans.io.flush := io.flush
  trans.io.lrValid := io.lrValid
  trans.io.out.ready := io.out.ready
  io.out.valid := trans.io.out.valid
  io.out.bits.addr := trans.io.out.bits.exeRes.data
  io.out.bits.tag := trans.io.out.bits.exeRes.uop.pc
  io.out.bits.op := trans.io.out.bits.exeRes.uop.ctrl.lsuOp
  io.out.bits.paddr := trans.io.out.bits.mmuRes.paddr
  io.out.bits.cacheable := trans.io.out.bits.mmuRes.cacheable
  io.out.bits.misalign := trans.io.out.bits.mmuRes.error.misalign
  io.out.bits.hasError := trans.io.out.bits.mmuRes.hasError
  io.out.bits.scSuccess := trans.io.out.bits.scSuccess
  mmu.io.fromCsr := 0.U.asTypeOf(new CsrToMmu)
  mmu.io.fromMemFlush := io.flush
  mmu.io.fromIcacheFlush := io.flush
  mmu.io.fromIcache.valid := io.fetch
  mmu.io.fromIcache.bits.vaddr := "h80000000".U
  mmu.io.toIcache.ready := true.B
  mmu.io.tlbFlush := 0.U.asTypeOf(new TlbFlush)
  mmu.io.ptwRead.req.ready := true.B
  mmu.io.ptwRead.resp.valid := false.B
  mmu.io.ptwRead.resp.bits := 0.U.asTypeOf(mmu.io.ptwRead.resp.bits)
}

class MemAddrTransSpec extends AnyFlatSpec with ChiselScalatestTester {
  for (xlen <- Seq(32, 64)) {
    it should s"keep Bare translations paired with execution metadata at XLEN=$xlen" in {
      implicit val p: Parameters = new Parameters(Map(CoreConfigKeys.XLEN -> xlen))
      test(new MemAddrTransMmuHarness).withAnnotations(
        Seq(VerilatorBackendAnnotation, VerilatorCFlags(Seq("-DWData=IData")),
          TargetDirAnnotation(s"target/memaddr-test-$xlen"))) { dut =>
        dut.clock.setTimeout(0)
        case class Request(addr: BigInt, tag: Int, op: Int, lrValid: Boolean) {
          val scFail: Boolean = op == 10 && !lrValid
          val size: Int = op match {
            case 1 | 4 | 6 => 1
            case 2 | 5 | 7 => 2
            case 11 | 13 => 8
            case _ => 4
          }
          val misalign: Boolean = !scFail && addr % size != 0
          val low: BigInt = addr & BigInt("ffffffff", 16)
          val cacheable: Boolean = !scFail &&
            !(low >= BigInt("30000000", 16) && low < BigInt("80000000", 16))
        }
        val expected = mutable.Queue.empty[Request]
        var accepted = 0
        var completed = 0
        def tick(req: Option[Request], ready: Boolean, flush: Boolean,
                 fetch: Boolean): Boolean = {
          dut.io.in.valid.poke(req.nonEmpty.B)
          req.foreach { r =>
            dut.io.in.bits.addr.poke(r.addr.U)
            dut.io.in.bits.tag.poke(r.tag.U)
            dut.io.in.bits.op.poke(r.op.U)
            dut.io.lrValid.poke(r.lrValid.B)
          }
          dut.io.out.ready.poke(ready.B)
          dut.io.flush.poke(flush.B)
          dut.io.fetch.poke(fetch.B)
          dut.io.out.valid.expect(expected.nonEmpty.B)
          expected.headOption.foreach { r =>
            dut.io.out.bits.addr.expect(r.addr.U)
            dut.io.out.bits.tag.expect(r.tag.U)
            dut.io.out.bits.op.expect(r.op.U)
            dut.io.out.bits.paddr.expect((if (r.scFail) BigInt(0) else r.addr).U)
            dut.io.out.bits.cacheable.expect(r.cacheable.B)
            dut.io.out.bits.misalign.expect(r.misalign.B)
            dut.io.out.bits.hasError.expect(r.misalign.B)
            dut.io.out.bits.scSuccess.expect((r.op == 10 && r.lrValid).B)
          }
          if (ready && expected.nonEmpty) { expected.dequeue(); completed += 1 }
          val fire = req.nonEmpty && dut.io.in.ready.peek().litToBoolean
          if (fire) { expected.enqueue(req.get); accepted += 1 }
          if (flush) expected.clear()
          dut.clock.step()
          fire
        }
        dut.io.in.valid.poke(false.B)
        dut.io.in.bits.addr.poke(0.U)
        dut.io.in.bits.tag.poke(0.U)
        dut.io.in.bits.op.poke(0.U)
        dut.io.lrValid.poke(false.B)
        dut.io.out.ready.poke(true.B)
        dut.io.flush.poke(false.B)
        dut.io.fetch.poke(false.B)
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)
        for (n <- 0 until 256) {
          val r = Request(BigInt("80000000", 16) + n * 8, n, 1 + n % 13, n % 2 == 0)
          assert(tick(Some(r), ready = true, flush = false, fetch = true))
        }
        for (_ <- 0 until 4) tick(None, ready = true, flush = false, fetch = false)
        val rng = new Random(0x534b4944L + xlen)
        var pending = Option.empty[Request]
        for (n <- 0 until 4000) {
          if (pending.isEmpty && rng.nextInt(4) != 0)
            pending = Some(Request(BigInt(xlen, rng), n + 256,
              1 + rng.nextInt(13), rng.nextBoolean()))
          val flush = rng.nextInt(31) == 0
          val fire = tick(pending, rng.nextBoolean(), flush, rng.nextBoolean())
          if (fire || flush) pending = None
        }
        for (_ <- 0 until 4) tick(None, ready = true, flush = false, fetch = false)
        assert(expected.isEmpty)
        println(s"MemAddrTrans XLEN=$xlen: $accepted accepted, $completed completed; metadata/SC/skid/flush checks passed")
      }
    }
  }
}
