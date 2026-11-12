package minixiangshan.mmu

import chisel3._
import chisel3.util._
import minixiangshan.config._
import minixiangshan.mem.L2cache.L2NativeReadIO

/** Sv39 walker. Software sets A/D bits (Svade); the walker never writes PTEs. */
class Ptw(implicit p: Parameters) extends NSModule with HasCsrParameters {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new PtwReq))
    val resp = Valid(new PtwResp)
    val l2 = new L2NativeReadIO(1)
  })

  val sIdle :: sReq :: sWait :: sDone :: Nil = Enum(4)
  val state = RegInit(sIdle)
  val level = RegInit(2.U(2.W))
  val reqVaddr = RegInit(0.U(XLEN.W))
  val reqAcc = RegInit(0.U(MmuAccType.width.W))
  val ptAddr = RegInit(0.U(XLEN.W))
  val global = RegInit(false.B)
  val result = RegInit(0.U.asTypeOf(new PtwResp))
  val vpns = VecInit((0 until 3).map(i =>
    reqVaddr(pageOffLen + (i + 1) * vpnLevelLen - 1, pageOffLen + i * vpnLevelLen)))

  io.req.ready := state === sIdle
  io.l2.req.valid := state === sReq
  io.l2.req.bits.id := 0.U
  io.l2.req.bits.addr := ptAddr
  io.l2.req.bits.size := 3.U
  io.l2.req.bits.uncache := true.B
  io.l2.cancel := false.B
  io.l2.resp.ready := state === sWait
  io.resp.valid := state === sDone
  io.resp.bits := result

  val pte = io.l2.resp.bits.data(63, 0)
  val ppn = pte(53, 10)
  val leaf = pte(1) || pte(3)
  val illegal = !pte(0) || (!pte(1) && pte(2)) || pte(63, 54).orR
  val superMisalign = Mux(level === 2.U, ppn(17, 0).orR,
    Mux(level === 1.U, ppn(8, 0).orR, false.B))
  val pagePpn = MuxLookup(level, ppn)(Seq(
    2.U -> Cat(ppn(43, 18), vpns(1), vpns(0)),
    1.U -> Cat(ppn(43, 9), vpns(0))
  ))

  switch(state) {
    is(sIdle) {
      when(io.req.fire) {
        reqVaddr := io.req.bits.vaddr
        reqAcc := io.req.bits.acc
        level := 2.U
        global := false.B
        result := 0.U.asTypeOf(new PtwResp)
        ptAddr := Cat(io.req.bits.satp(43, 0), io.req.bits.vaddr(38, 30), 0.U(3.W))
        state := sReq
      }
    }
    is(sReq) {
      when(io.l2.req.fire) { state := sWait }
    }
    is(sWait) {
      when(io.l2.resp.fire) {
        when(illegal || (leaf && (superMisalign || !pte(6) ||
          (reqAcc === MmuAccType.store && !pte(7))))) {
          result.fault := true.B
          state := sDone
        }.elsewhen(leaf) {
          result.ppn := pagePpn
          result.vpn := reqVaddr(38, 12)
          result.paddr := Cat(pagePpn, reqVaddr(11, 0))
          result.r := pte(1)
          result.w := pte(2)
          result.x := pte(3)
          result.u := pte(4)
          result.g := global || pte(5)
          result.d := pte(7)
          state := sDone
        }.elsewhen(level === 0.U || pte(4) || pte(6) || pte(7)) {
          result.fault := true.B
          state := sDone
        }.otherwise {
          ptAddr := Cat(ppn, vpns(level - 1.U), 0.U(3.W))
          level := level - 1.U
          global := global || pte(5)
          state := sReq
        }
      }
    }
    is(sDone) { state := sIdle }
  }
}
