package minixiangshan.mmu

import chisel3._
import chisel3.util._

import minixiangshan.config._
import minixiangshan.backend.decode.LsuOp

/* ============================================================================
 *  RISC-V Sv39 MMU
 *
 *  · satp.MODE = 0 (Bare)：恒等映射
 *  · satp.MODE = 8 (Sv39)：TLB 查找 + 三级硬件页表遍历
 *  · 两个翻译端口（取指 / 访存）共享一个页表遍历器，按轮转仲裁
 *  · Bare 取指和数据请求各走独立的一级弹性流水线
 *  · 权限检查：R/W/X 位、U 位、SUM、MXR
 *  · 未实现 PMP/PMA，因此不产生 access fault；MMIO 区域标记为不可缓存
 * ==========================================================================*/
class Mmu(implicit p: Parameters) extends NSModule with HasCsrParameters {
  val io = IO(new MmuIoBundle)

  val tlb = Module(new Tlb)
  val ptw = Module(new Ptw)

  // ---------------- PTW 物理读端口透传 ----------------
  io.ptwRead.req.valid  := ptw.io.l2.req.valid
  ptw.io.l2.req.ready   := io.ptwRead.req.ready
  io.ptwRead.req.bits   := ptw.io.l2.req.bits
  io.ptwRead.cancel     := ptw.io.l2.cancel
  ptw.io.l2.resp.valid  := io.ptwRead.resp.valid
  io.ptwRead.resp.ready := ptw.io.l2.resp.ready
  ptw.io.l2.resp.bits   := io.ptwRead.resp.bits

  // ---------------- satp ----------------
  val sv39 = io.fromCsr.satp(63, 60) === 8.U
  val pagedI = sv39 && io.fromCsr.priv =/= PRIV_M_VAL.U
  val pagedM = sv39 && io.fromCsr.dataPriv =/= PRIV_M_VAL.U
  val asid = io.fromCsr.satp(SATP_ASID_HI, SATP_ASID_LO)

  // ---------------- 状态机 ----------------
  val sIdle :: sWalk :: sDrain :: sResp :: Nil = Enum(4)
  val state = RegInit(sIdle)

  val curPort      = RegInit(0.U(1.W))      // 0 = 取指，1 = 访存
  val lastServ     = RegInit(0.U(1.W))      // 轮转仲裁：上次服务的端口
  val reqVaddr     = RegInit(0.U(XLEN.W))
  val reqLsuOp     = RegInit(0.U(LsuOp.width.W))
  val reqAcc       = RegInit(0.U(MmuAccType.width.W))
  val reqPriv      = RegInit(0.U(privLen.W))
  val respPaddr    = RegInit(0.U(XLEN.W))
  val respMisalign = RegInit(false.B)
  val respPageFlt  = RegInit(false.B)
  val respAccessFlt = RegInit(false.B)

  // Bare data requests use an independent elastic response stage. Keep the
  // accepted response until consumed, even if satp changes in the meantime.
  val bareMemValid = RegInit(false.B)
  val bareMemResp = RegInit(0.U.asTypeOf(new MmuToSqResp))
  val bareIcacheValid = RegInit(false.B)
  val bareIcacheResp = RegInit(0.U.asTypeOf(new MmuToIcache))
  val sharedMemBusy = (state =/= sIdle) && (curPort === 1.U)
  val sharedIcacheBusy = (state =/= sIdle) && (curPort === 0.U)
  val sharedMemPending = pagedM && io.fromMem.valid && !bareMemValid
  val sharedIcachePending = pagedI && io.fromIcache.valid && !bareIcacheValid

  // ---------------- 输入仲裁（轮转） ----------------
  val prioIcache = !lastServ
  val acceptI = io.fromIcache.fire && pagedI && !io.fromIcacheFlush && !io.tlbFlush.valid
  val acceptM = io.fromMem.fire && pagedM && !io.fromMemFlush && !io.tlbFlush.valid

  io.fromIcache.ready := Mux(pagedI,
    (state === sIdle) && !io.tlbFlush.valid && !bareIcacheValid && (!sharedMemPending || prioIcache),
    !sharedIcacheBusy && (!bareIcacheValid || io.toIcache.ready))
  io.fromMem.ready := Mux(pagedM,
    (state === sIdle) && !io.tlbFlush.valid && !bareMemValid && (!sharedIcachePending || !prioIcache),
    !sharedMemBusy && (!bareMemValid || io.toMem.ready))

  val inVaddr = Mux(acceptI, io.fromIcache.bits.vaddr, io.fromMem.bits.vaddr)
  val inPort  = Mux(acceptI, 0.U(1.W), 1.U(1.W))
  val inAcc   = Mux(acceptI, MmuAccType.fetch,
                Mux(LsuOp.isStore(io.fromMem.bits.lsuOp), MmuAccType.store, MmuAccType.load))
  val inPriv  = Mux(acceptI, io.fromCsr.priv, io.fromCsr.dataPriv)

  // ---------------- 组合 TLB 查找 ----------------
  tlb.io.lookup.req.vaddr := inVaddr
  tlb.io.lookup.req.asid  := asid
  tlb.io.lookup.req.acc   := inAcc

  val tlbHit      = tlb.io.lookup.resp.hit
  val tlbHitPaddr = Cat(tlb.io.lookup.resp.ppn, inVaddr(pageOffLen - 1, 0))
  val canonical = inVaddr(63, 39) === Fill(25, inVaddr(38))
  val needWalk = !tlbHit && canonical

  // ---------------- 权限检查 ----------------
  def permFault(r: Bool, w: Bool, x: Bool, u: Bool, acc: UInt, priv: UInt): Bool = {
    val sum = io.fromCsr.sum
    val mxr = io.fromCsr.mxr
    val accessOk = Mux(acc === MmuAccType.store, w,
                    Mux(acc === MmuAccType.fetch, x,
                      r || (mxr && x)))
    val privOk = Mux(priv === PRIV_U_VAL.U, u,
                  Mux(priv === PRIV_S_VAL.U,
                    !u || (sum && (acc =/= MmuAccType.fetch)),
                    true.B))
    !(accessOk && privOk)
  }

  // 访存地址非对齐（按访问宽度）
  def memMisalign(vaddr: UInt, lsuOp: UInt): Bool =
    Mux(LsuOp.isDoubleWord(lsuOp), vaddr(2, 0) =/= 0.U,
      Mux(LsuOp.isByte(lsuOp), false.B,
        Mux(LsuOp.isHalf(lsuOp), vaddr(0),
          vaddr(1, 0) =/= 0.U)))

  def fetchMisalign(vaddr: UInt): Bool = vaddr(1, 0) =/= 0.U

  // Match NutShell's physical address map: CLINT/PLIC and external MMIO are uncached.
  def isCacheable(paddr: UInt): Bool = PerfAddressMap.isCacheable(paddr)

  // Select held responses by their saved valid bits, not the current satp.
  when(io.fromIcacheFlush) {
    bareIcacheValid := false.B
  }.elsewhen(!pagedI && io.fromIcache.ready) {
    bareIcacheValid := io.fromIcache.valid
  }.elsewhen(io.toIcache.fire) {
    bareIcacheValid := false.B
  }
  when(io.fromIcache.fire && !pagedI) {
    val misalign = fetchMisalign(io.fromIcache.bits.vaddr)
    bareIcacheResp.paddr := io.fromIcache.bits.vaddr
    bareIcacheResp.cacheable := isCacheable(io.fromIcache.bits.vaddr)
    bareIcacheResp.error.misalign := misalign
    bareIcacheResp.error.pageFault := false.B
    bareIcacheResp.error.accessFault := false.B
    bareIcacheResp.hasError := misalign
  }

  when(io.fromMemFlush) {
    bareMemValid := false.B
  }.elsewhen(!pagedM && io.fromMem.ready) {
    bareMemValid := io.fromMem.valid
  }.elsewhen(io.toMem.fire) {
    bareMemValid := false.B
  }
  when(io.fromMem.fire && !pagedM) {
    val misalign = memMisalign(io.fromMem.bits.vaddr, io.fromMem.bits.lsuOp)
    bareMemResp.paddr := io.fromMem.bits.vaddr
    bareMemResp.cacheable := isCacheable(io.fromMem.bits.vaddr)
    bareMemResp.error.misalign := misalign
    bareMemResp.error.pageFault := false.B
    bareMemResp.error.accessFault := false.B
    bareMemResp.hasError := misalign
  }

  // ---------------- PTW 请求默认值 ----------------
  ptw.io.req.valid       := false.B
  ptw.io.req.bits.vaddr  := inVaddr
  ptw.io.req.bits.acc    := inAcc
  ptw.io.req.bits.satp   := io.fromCsr.satp

  tlb.io.fill.valid := false.B
  tlb.io.fill.vpn   := 0.U
  tlb.io.fill.asid  := asid
  tlb.io.fill.ppn   := 0.U
  tlb.io.fill.r     := false.B
  tlb.io.fill.w     := false.B
  tlb.io.fill.x     := false.B
  tlb.io.fill.u     := false.B
  tlb.io.fill.g     := false.B
  tlb.io.fill.d     := false.B

  // PTW 返回结果的权限检查
  val ptwPermFault = permFault(ptw.io.resp.bits.r, ptw.io.resp.bits.w,
                               ptw.io.resp.bits.x, ptw.io.resp.bits.u,
                               reqAcc, reqPriv)

  switch(state) {
    is(sIdle) {
      when(acceptI || acceptM) {
        curPort  := inPort
        lastServ := inPort
        reqVaddr := inVaddr
        reqLsuOp := io.fromMem.bits.lsuOp
        reqAcc   := inAcc
        reqPriv  := inPriv
        when(!needWalk) {
          respPaddr     := tlbHitPaddr
          respMisalign  := Mux(acceptI, fetchMisalign(inVaddr),
                               memMisalign(inVaddr, io.fromMem.bits.lsuOp))
          respPageFlt   := !canonical || (inAcc === MmuAccType.store && !tlb.io.lookup.resp.d) ||
                            permFault(tlb.io.lookup.resp.r, tlb.io.lookup.resp.w,
                                             tlb.io.lookup.resp.x, tlb.io.lookup.resp.u,
                                             inAcc, inPriv)
          respAccessFlt := false.B
          state         := sResp
        }.otherwise {
          ptw.io.req.valid := true.B
          when(ptw.io.req.fire) { state := sWalk }
        }
      }
    }

    is(sWalk) {
      when(ptw.io.resp.valid) {
        val pr = ptw.io.resp.bits
        when(!pr.fault && !io.tlbFlush.valid && !io.fromIcacheFlush && !io.fromMemFlush) {
          tlb.io.fill.valid := true.B
          tlb.io.fill.vpn   := pr.vpn
          tlb.io.fill.asid  := asid
          tlb.io.fill.ppn   := pr.ppn
          tlb.io.fill.r     := pr.r
          tlb.io.fill.w     := pr.w
          tlb.io.fill.x     := pr.x
          tlb.io.fill.u     := pr.u
          tlb.io.fill.g     := pr.g
          tlb.io.fill.d     := pr.d
        }
        respPaddr     := pr.paddr
        respMisalign  := Mux(curPort === 0.U, fetchMisalign(reqVaddr),
                             memMisalign(reqVaddr, reqLsuOp))
        respPageFlt   := pr.fault || ptwPermFault
        respAccessFlt := false.B
        state         := sResp
      }
    }

    is(sDrain) {
      // 等待被放弃的页表遍历结束，保证 PTW 回到空闲
      when(ptw.io.resp.valid) { state := sIdle }
    }

    is(sResp) {
      when(Mux(curPort === 0.U, io.toIcache.fire, io.toMem.fire)) {
        state := sIdle
      }
    }
  }

  // ---------------- 重定向冲刷 ----------------
  val curFlushed = Mux(curPort === 0.U, io.fromIcacheFlush, io.fromMemFlush)
  when((state === sWalk || state === sResp) && curFlushed) {
    // 若页表遍历恰在本周期返回，则直接回空闲；否则进入排空态等待
    state := Mux(state === sWalk && !ptw.io.resp.valid, sDrain, sIdle)
  }

  // ---------------- 输出 ----------------
  io.toIcache.valid           := (state === sResp) && (curPort === 0.U)
  io.toIcache.bits.paddr      := respPaddr
  io.toIcache.bits.cacheable  := isCacheable(respPaddr)
  io.toIcache.bits.error.misalign    := respMisalign
  io.toIcache.bits.error.pageFault   := respPageFlt
  io.toIcache.bits.error.accessFault := respAccessFlt
  io.toIcache.bits.hasError   := respMisalign || respPageFlt || respAccessFlt
  when(bareIcacheValid) {
    io.toIcache.valid := true.B
    io.toIcache.bits := bareIcacheResp
  }

  io.toMem.valid              := (state === sResp) && (curPort === 1.U)
  io.toMem.bits.paddr         := respPaddr
  io.toMem.bits.cacheable     := isCacheable(respPaddr)
  io.toMem.bits.error.misalign    := respMisalign
  io.toMem.bits.error.pageFault   := respPageFlt
  io.toMem.bits.error.accessFault := respAccessFlt
  io.toMem.bits.hasError      := respMisalign || respPageFlt || respAccessFlt

  when(bareMemValid) {
    io.toMem.valid := true.B
    io.toMem.bits := bareMemResp
  }

  // ---------------- TLB 冲刷 ----------------
  tlb.io.flush := io.tlbFlush
  io.tlbFillIdx := tlb.io.fillIdx
}
