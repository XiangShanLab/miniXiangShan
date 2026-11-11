package minixiangshan.mem

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorCFlags
import firrtl.options.TargetDirAnnotation
import minixiangshan.TestInputs.zero
import minixiangshan.backend.decode.LsuOp
import minixiangshan.config._
import org.scalatest.flatspec.AnyFlatSpec

import scala.util.Random

class LoadQueueSpec extends AnyFlatSpec with ChiselScalatestTester {
  for (xlen <- Seq(32, 64)) {
    it should s"issue safe loads early while preserving SQ ordering and forwarding at XLEN=$xlen" in {
      implicit val p: Parameters = new Parameters(Map(CoreConfigKeys.XLEN -> xlen))
      test(new LoadQueue).withAnnotations(Seq(VerilatorBackendAnnotation,
        VerilatorCFlags(Seq("-DWData=IData")),
        TargetDirAnnotation(s"target/lq-fast-$xlen"))) { dut =>
        dut.clock.setTimeout(0)
        val addr = BigInt("80001000", 16)
        def inputs(): Unit = {
          zero(dut.io.enq)
          zero(dut.io.addrWrite)
          zero(dut.io.sqForwardInfo)
          zero(dut.io.sqOldestRobIdx)
          zero(dut.io.robHead)
          zero(dut.io.redirectInfo)
          dut.io.sqEmpty.poke(true.B)
          dut.io.committedStoreEmpty.poke(true.B)
          dut.io.dcacheReq.ready.poke(false.B)
          dut.io.dcacheResp.valid.poke(false.B)
          zero(dut.io.dcacheResp.bits)
          dut.io.outResult.ready.poke(true.B)
        }
        def reset(): Unit = {
          inputs()
          dut.reset.poke(true.B)
          dut.clock.step(2)
          dut.reset.poke(false.B)
        }
        def prime(rob: Int = 20, cacheable: Boolean = true, op: Int = 3,
                  address: BigInt = addr, fault: Boolean = false): Int = {
          val idx = dut.io.enqPtr.peek().litValue.toInt
          dut.io.enq.valid.poke(true.B)
          dut.io.enq.robIdx.value.poke((rob % 32).U)
          dut.io.enq.robIdx.flag.poke(((rob / 32) % 2 != 0).B)
          dut.io.enq.pdst.poke(7.U)
          dut.io.enq.rfWen.poke(true.B)
          dut.io.enq.lsuOp.poke(op.U)
          dut.clock.step()
          dut.io.enq.valid.poke(false.B)
          dut.io.addrWrite.valid.poke(true.B)
          dut.io.addrWrite.idx.poke(idx.U)
          dut.io.addrWrite.robIdx.value.poke((rob % 32).U)
          dut.io.addrWrite.robIdx.flag.poke(((rob / 32) % 2 != 0).B)
          dut.io.addrWrite.vaddr.poke(address.U)
          dut.io.addrWrite.paddr.poke(address.U)
          dut.io.addrWrite.cacheable.poke(cacheable.B)
          dut.io.addrWrite.excp.excpVec.poke(
            (if (fault) BigInt(1) << ExcType.LPAGE.id else BigInt(0)).U)
          dut.clock.step()
          dut.io.addrWrite.valid.poke(false.B)
          idx
        }
        def store(idx: Int = 0, rob: Int = 10, address: BigInt = addr + 32): Unit = {
          val s = dut.io.sqForwardInfo(idx)
          s.valid.poke(true.B)
          s.paddr.poke(address.U)
          s.robIdxFull.value.poke((rob % 32).U)
          s.robIdxFull.flag.poke(((rob / 32) % 2 != 0).B)
          s.addrValid.poke(true.B)
          s.cacheable.poke(true.B)
          s.lsuOp.poke(LsuOp.sw)
          s.dataValid.poke(true.B)
          s.data.poke("ha1b2c3d4".U)
        }
        def complete(idx: Int, data: BigInt): Unit = {
          dut.io.dcacheReq.valid.expect(true.B)
          dut.io.dcacheReq.bits.lqIdx.expect(idx.U)
          dut.io.dcacheReq.ready.poke(true.B)
          dut.clock.step()
          dut.io.dcacheReq.ready.poke(false.B)
          dut.io.dcacheResp.valid.poke(true.B)
          dut.io.dcacheResp.bits.lqIdx.poke(idx.U)
          dut.io.dcacheResp.bits.data.poke(data.U)
          dut.io.outResult.valid.expect(true.B)
          dut.io.outResult.bits.data.expect(data.U)
          dut.io.outResult.bits.uop.pdst.expect(7.U)
          dut.clock.step()
          dut.io.dcacheResp.valid.poke(false.B)
          dut.clock.step(2)
          dut.io.empty.expect(true.B)
        }

        reset()
        val rng = new Random(0x1a00 + xlen)
        // Reuse all LQ indices and wrap the ROB pointer repeatedly.
        for (n <- 0 until 256) {
          val rob = n % 64
          zero(dut.io.sqForwardInfo)
          if (n % 2 == 0) store(rob = (rob + 61) % 64)
          val idx = prime(rob = rob)
          dut.io.dcacheReq.valid.expect(true.B) // First cycle after address write.
          dut.io.dcacheReq.bits.paddr.expect(addr.U)
          val delay = rng.nextInt(4)
          for (_ <- 0 until delay) {
            dut.io.dcacheReq.valid.expect(true.B)
            dut.io.dcacheReq.bits.lqIdx.expect(idx.U)
            dut.clock.step()
          }
          complete(idx, BigInt(xlen, rng))
        }

        for (kind <- Seq("unknown", "uncached-store", "conflict", "two-conflicts")) {
          reset()
          store(address = if (kind.contains("conflict")) addr else addr + 32)
          if (kind == "unknown") dut.io.sqForwardInfo(0).addrValid.poke(false.B)
          if (kind == "uncached-store") dut.io.sqForwardInfo(0).cacheable.poke(false.B)
          if (kind.contains("conflict")) dut.io.sqForwardInfo(0).dataValid.poke(false.B)
          if (kind == "two-conflicts") store(idx = 1, rob = 11, address = addr)
          val idx = prime()
          for (_ <- 0 until 4) {
            dut.io.dcacheReq.valid.expect(false.B)
            dut.io.outResult.valid.expect(false.B)
            dut.clock.step()
          }
          zero(dut.io.sqForwardInfo)
          // Removing the blocker makes the safe path eligible immediately.
          dut.io.dcacheReq.valid.expect(true.B)
          complete(idx, 0x12345678)
        }

        // A younger, flushed or faulting store must not block an older load.
        for (kind <- Seq("younger", "flushed", "faulting")) {
          reset()
          store(rob = if (kind == "younger") 21 else 10)
          dut.io.sqForwardInfo(0).addrValid.poke(false.B)
          dut.io.sqForwardInfo(0).alreadyFlush.poke((kind == "flushed").B)
          dut.io.sqForwardInfo(0).hasException.poke((kind == "faulting").B)
          val idx = prime()
          complete(idx, 0x87654321L)
        }

        // A committed store is still older even if its ROB tag was recycled.
        reset()
        store(rob = 21)
        dut.io.sqForwardInfo(0).committed.poke(true.B)
        dut.io.sqForwardInfo(0).addrValid.poke(false.B)
        prime()
        dut.io.dcacheReq.valid.expect(false.B)

        // Forward byte lanes with sign extension; do not also issue to cache.
        reset()
        store(address = addr)
        prime(op = 1, address = addr + 1)
        dut.io.dcacheReq.valid.expect(false.B)
        dut.clock.step()
        dut.io.outResult.valid.expect(true.B)
        dut.io.outResult.bits.data.expect(((BigInt(1) << xlen) - 61).U)
        dut.io.dcacheReq.valid.expect(false.B)

        // Uncacheable loads retain their registered ROB-head-only slow path.
        reset()
        prime(cacheable = false)
        dut.clock.step(3)
        dut.io.dcacheReq.valid.expect(false.B)
        dut.io.robHead.valid.poke(true.B)
        dut.io.robHead.bits.value.poke(20.U)
        dut.io.dcacheReq.valid.expect(false.B)
        dut.clock.step()
        dut.io.dcacheReq.valid.expect(true.B)

        // Neither an exception nor a same-cycle redirect may enter the fast path.
        reset()
        prime(fault = true)
        dut.io.dcacheReq.valid.expect(false.B)
        dut.io.outResult.valid.expect(true.B)
        dut.io.outResult.bits.uop.excp.excpVec.expect((BigInt(1) << ExcType.LPAGE.id).U)
        reset()
        prime()
        dut.io.redirectInfo.valid.poke(true.B)
        dut.io.redirectInfo.bits.doRedirect.poke(true.B)
        dut.io.redirectInfo.bits.robIdx.value.poke(19.U)
        dut.io.dcacheReq.valid.expect(false.B)
        dut.clock.step(2)
        dut.io.empty.expect(true.B)
      }
    }
  }
}
