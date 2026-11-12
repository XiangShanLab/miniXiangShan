package minixiangshan.backend

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.VerilatorCFlags
import firrtl.options.TargetDirAnnotation
import minixiangshan.TestInputs.zero
import minixiangshan.backend.dispatch.DispatchedInst
import minixiangshan.backend.execute._
import minixiangshan.backend.issue._
import minixiangshan.backend.regfile.RegFile
import minixiangshan.backend.writeback.Writeback
import minixiangshan.config._
import org.scalatest.flatspec.AnyFlatSpec

import scala.util.Random

class LoadWakeupHarness(implicit p: Parameters) extends NSModule {
  val io = IO(new Bundle {
    val enq = Flipped(Valid(new DispatchedInst))
    val result = Flipped(Decoupled(new ExeResult))
    val issue = Decoupled(new RegReadIssue)
    val redirect = Flipped(Valid(new redirectInfoToModule))
    val flush = Input(Bool())
    val early = Output(Bool())
    val wb = Output(Bool())
    val data1 = Output(UInt(XLEN.W))
    val data2 = Output(UInt(XLEN.W))
  })
  val iq = Module(new IssueQueue(IQParams(4, 5, 0)))
  val wb = Module(new Writeback(5))
  val rf = Module(new RegFile)
  iq.io.enq <> io.enq
  io.issue <> iq.io.issue
  iq.io.redirectInfo := io.redirect
  iq.io.flushPipeline := io.flush
  iq.io.fastWakeup := 0.U.asTypeOf(iq.io.fastWakeup)
  iq.io.execWakeup := 0.U.asTypeOf(iq.io.execWakeup)
  iq.io.wakeupPorts := wb.io.wakeupPorts
  iq.io.loadWakeup := wb.io.loadWakeup
  wb.io.redirectInfo := io.redirect
  wb.io.flush := io.flush
  for (i <- 0 until 5) {
    if (i == 3) wb.io.InExeResults(i) <> io.result
    else {
      wb.io.InExeResults(i).valid := false.B
      wb.io.InExeResults(i).bits := 0.U.asTypeOf(new ExeResult)
    }
  }
  rf.io.writePorts <> wb.io.rfWritePorts
  for (i <- 0 until intRegFileReadPorts) rf.io.readPorts(i).addr := 0.U
  rf.io.readPorts(0).addr := io.issue.bits.uop.prs1
  rf.io.readPorts(1).addr := io.issue.bits.uop.prs2
  io.data1 := rf.io.readPorts(0).data
  io.data2 := rf.io.readPorts(1).data
  io.early := wb.io.loadWakeup.valid
  io.wb := wb.io.wakeupPorts(3).valid
}

class LoadWakeupSpec extends AnyFlatSpec with ChiselScalatestTester {
  for (xlen <- Seq(64)) {
    it should s"wake load consumers with correct PRF data and cancellation at XLEN=$xlen" in {
      implicit val p: Parameters = new Parameters(Map(CoreConfigKeys.XLEN -> xlen))
      test(new LoadWakeupHarness).withAnnotations(Seq(VerilatorBackendAnnotation,
        VerilatorCFlags(Seq("-DWData=IData")),
        TargetDirAnnotation(s"target/load-wakeup-$xlen"))) { dut =>
        dut.clock.setTimeout(0)
        def inputs(): Unit = {
          zero(dut.io.enq)
          zero(dut.io.result.bits)
          zero(dut.io.redirect)
          dut.io.result.valid.poke(false.B)
          dut.io.flush.poke(false.B)
          dut.io.issue.ready.poke(true.B)
        }
        def reset(): Unit = {
          inputs()
          dut.reset.poke(true.B)
          dut.clock.step(2)
          dut.reset.poke(false.B)
        }
        def consumer(tag: Int, rob: Int = 5, both: Boolean = false,
                     secondTag: Int = -1): Unit = {
          zero(dut.io.enq.bits)
          dut.io.enq.valid.poke(true.B)
          dut.io.enq.bits.rs1Valid.poke(true.B)
          dut.io.enq.bits.prs1.poke(tag.U)
          dut.io.enq.bits.prs1Busy.poke(true.B)
          dut.io.enq.bits.rs2Valid.poke(both.B)
          dut.io.enq.bits.prs2.poke((if (secondTag < 0) tag else secondTag).U)
          dut.io.enq.bits.prs2Busy.poke(both.B)
          dut.io.enq.bits.robIdxFull.value.poke(rob.U)
          dut.io.enq.bits.ctrl.fuType.poke(0.U)
          dut.clock.step()
          dut.io.enq.valid.poke(false.B)
        }
        def result(tag: Int, data: BigInt, rob: Int = 4): Unit = {
          zero(dut.io.result.bits)
          dut.io.result.valid.poke(true.B)
          dut.io.result.bits.uop.ctrl.rfWen.poke(true.B)
          dut.io.result.bits.uop.rdValid.poke(true.B)
          dut.io.result.bits.uop.pdst.poke(tag.U)
          dut.io.result.bits.uop.robIdxFull.value.poke(rob.U)
          dut.io.result.bits.data.poke(data.U)
        }
        val rng = new Random(0x10ad + xlen)
        reset()
        for (n <- 0 until 128) {
          val tag = 1 + rng.nextInt(60)
          val data = BigInt(xlen, rng)
          val stall = n % 3
          consumer(tag, both = true)
          dut.io.issue.valid.expect(false.B)
          dut.io.issue.ready.poke((stall == 0).B)
          result(tag, data)
          dut.io.early.expect(true.B)
          dut.io.issue.valid.expect(true.B)
          dut.io.issue.bits.src1DataSource.expect(DataSource.regFile)
          dut.io.issue.bits.src2DataSource.expect(DataSource.regFile)
          dut.clock.step()
          dut.io.result.valid.poke(false.B)
          dut.io.data1.expect(data.U)
          dut.io.data2.expect(data.U)
          if (stall != 0) {
            dut.clock.step(stall)
            dut.io.issue.valid.expect(true.B)
            dut.io.issue.ready.poke(true.B)
            dut.clock.step()
            dut.io.data1.expect(data.U)
          }
          dut.clock.step(2)
          dut.io.issue.valid.expect(false.B)
        }

        // Distinct operands arrive on consecutive cycles. The first must be
        // read from stored PRF state while the second uses the write bypass.
        for (n <- 0 until 64) {
          val first = BigInt(xlen, rng)
          val second = BigInt(xlen, rng)
          consumer(20, both = true, secondTag = 21)
          result(22, 0xdead) // Unrelated destination cannot wake either operand.
          dut.io.issue.valid.expect(false.B)
          dut.clock.step()
          result(20, first)
          dut.io.issue.valid.expect(false.B)
          dut.clock.step()
          result(21, second)
          dut.io.issue.valid.expect(true.B)
          dut.clock.step()
          dut.io.result.valid.poke(false.B)
          dut.io.data1.expect(first.U)
          dut.io.data2.expect(second.U)
          dut.clock.step(2)
        }

        // A matching result in the enqueue cycle must not lose its wakeup.
        result(9, 0x12345678)
        consumer(9)
        dut.io.result.valid.poke(false.B)
        dut.io.issue.valid.expect(true.B)
        dut.clock.step()
        dut.io.data1.expect("h12345678".U)
        dut.clock.step(2)

        for (kind <- Seq("x0", "no-write", "no-rd", "exception", "killed", "flush")) {
          reset()
          consumer(if (kind == "x0") 0 else 11)
          result(if (kind == "x0") 0 else 11, 0x76543210, rob = 10)
          if (kind == "no-write") dut.io.result.bits.uop.ctrl.rfWen.poke(false.B)
          if (kind == "no-rd") dut.io.result.bits.uop.rdValid.poke(false.B)
          if (kind == "exception")
            dut.io.result.bits.uop.excp.excpVec.poke((BigInt(1) << ExcType.LPAGE.id).U)
          if (kind == "killed") {
            dut.io.redirect.valid.poke(true.B)
            dut.io.redirect.bits.doRedirect.poke(true.B)
            dut.io.redirect.bits.robIdx.value.poke(8.U)
          }
          if (kind == "flush") dut.io.flush.poke(true.B)
          dut.io.early.expect(false.B)
          dut.io.issue.valid.expect(false.B)
          dut.clock.step()
          dut.io.result.valid.poke(false.B)
          if (kind == "killed" || kind == "flush") {
            zero(dut.io.redirect)
            dut.io.flush.poke(false.B)
            dut.io.wb.expect(false.B)
            dut.io.issue.valid.expect(false.B)
          }
        }

        // Redirect also masks a result already held in the WB stage.
        reset()
        consumer(12)
        result(12, 0xabcdef, rob = 10)
        dut.io.issue.ready.poke(false.B)
        dut.clock.step()
        dut.io.result.valid.poke(false.B)
        dut.io.redirect.valid.poke(true.B)
        dut.io.redirect.bits.doRedirect.poke(true.B)
        dut.io.redirect.bits.robIdx.value.poke(3.U)
        dut.io.wb.expect(false.B)
        dut.io.issue.valid.expect(false.B)
        dut.clock.step()
        zero(dut.io.redirect)
        dut.io.issue.ready.poke(true.B)
        dut.io.issue.valid.expect(false.B)

        // Reusing the destination after cancellation must require a fresh result.
        dut.clock.step(2)
        consumer(12, rob = 6)
        dut.io.issue.valid.expect(false.B)
        result(12, 0x2468, rob = 5)
        dut.io.issue.valid.expect(true.B)
        dut.clock.step()
        dut.io.result.valid.poke(false.B)
        dut.io.data1.expect("h2468".U)
      }
    }
  }
}
