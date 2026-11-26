package minixiangshan.mem
 
import chisel3._
import chisel3.util._
import minixiangshan.config._
import minixiangshan.backend.dispatch._
import minixiangshan.backend.decode._
import minixiangshan.backend.rename._
import minixiangshan.backend.execute._
import minixiangshan.util.CircularQueuePtr
import os.truncate
 
class LoadQueue(implicit p: Parameters) extends NSModule {
  private val wordAlignBits = log2Ceil(XLEN / 8)
 
  class LqPtrInner extends CircularQueuePtr[LqPtrInner](LqSize)
 
  class LqEntry(implicit p: Parameters) extends NSBundle {
    val robIdxFull   = new RobPtr(RobSize)
    val sqIdx        = UInt(log2Ceil(SqSize).W)
    val valid        = Bool()
    val addrValid    = Bool()
    val alreadyFlush = Bool()
    val readyToIssue = Bool() 
    val issued       = Bool()
    val dataValid    = Bool()
    val writtenBack  = Bool()
    val vaddr        = UInt(XLEN.W)
    val paddr        = UInt(XLEN.W)
    val cacheable    = Bool()
    val data         = UInt(XLEN.W)
    val excp         = new ExceptionBundle
    val lsuOp        = UInt(LsuOp.width.W)
    val pc           = UInt(XLEN.W)
    val pdst         = UInt(PhyRegIdxWidth.W)
    val rfWen        = Bool()
    val fuType       = UInt(FuType.width.W)
    val cacheOp        = new CacheOpDecode
  }
 
  val io = IO(new Bundle {
 
    val redirectInfo = Flipped(ValidIO(new redirectInfoToModule))
 
    val enq = new Bundle {
      val valid  = Input(Bool())
      val robIdx = Input(new RobPtr(RobSize))
      val sqIdx  = Input(UInt(log2Ceil(SqSize).W))
      val pc     = Input(UInt(XLEN.W))
      val pdst   = Input(UInt(PhyRegIdxWidth.W))
      val rfWen  = Input(Bool())
      val lsuOp  = Input(UInt(LsuOp.width.W))
      val fuType = Input(UInt(FuType.width.W))
      val cacheOp  = Input(new CacheOpDecode)
    }
 
    val addrWrite = new Bundle {
      val valid     = Input(Bool())
      val idx       = Input(UInt(log2Ceil(LqSize).W))
      val robIdx    = Input(new RobPtr(RobSize))
      val vaddr     = Input(UInt(XLEN.W))
      val paddr     = Input(UInt(XLEN.W))
      val cacheable = Input(Bool())
      val excp      = Input(new ExceptionBundle)
    }
 
    val sqForwardInfo  = Input(Vec(SqSize, new SqForwardInfoBundle))
    val sqOldestRobIdx = Input(new RobPtr(RobSize))
    val sqEmpty        = Input(Bool())
 
    val dcacheReq = Decoupled(new Bundle {
      val lqIdx     = UInt(log2Ceil(LqSize).W)
      val robIdx    = new RobPtr(RobSize)
      val paddr     = UInt(XLEN.W)
      val vaddr     = UInt(XLEN.W)
      val cacheable = Bool()
      val lsuOp     = UInt(LsuOp.width.W)
    })
 
    val dcacheResp = Flipped(Decoupled(new Bundle {
      val lqIdx = UInt(log2Ceil(LqSize).W)
      val data  = UInt(XLEN.W)
    }))
 
    val outResult = Decoupled(new ExeResult)
 
    val full          = Output(Bool())
    val empty         = Output(Bool())
    val enqPtr        = Output(UInt(log2Ceil(LqSize).W))
    val lqHasEntries  = Output(UInt(log2Ceil(LqSize + 1).W))
    val committedStoreEmpty = Input(Bool())
    val robHead         = Input(Valid(new RobPtr(RobSize)))

  })
    // ================================================================
    //  切断了forward和req的联系
    // ================================================================
    val entries = RegInit(VecInit(Seq.fill(LqSize)(0.U.asTypeOf(new LqEntry))))
    diffDontTouch(entries)
   
    val enqPtr = RegInit({
      val p = Wire(new LqPtrInner); p.value := 0.U; p.flag := false.B; p
    })
    val deqPtr = RegInit({
      val p = Wire(new LqPtrInner); p.value := 0.U; p.flag := false.B; p
    })
   
    val empty = deqPtr === enqPtr
    val full  = (deqPtr.value === enqPtr.value) && (deqPtr.flag =/= enqPtr.flag)
   
    io.full   := full
    io.empty  := empty
    io.enqPtr := enqPtr.value
    //val count = enqPtr.distanceTo(deqPtr)
    //io.lqHasEntries := count
    val lqHasEntriesReg = RegInit(0.U(log2Ceil(LqSize + 1).W))
    io.lqHasEntries := lqHasEntriesReg
   
    // ================================================================
    //  1. 入队
    // ================================================================
    val enqFire = io.enq.valid && !full
   
    when(enqFire) {
      val idx = enqPtr.value
      entries(idx).robIdxFull   := io.enq.robIdx
      entries(idx).sqIdx        := io.enq.sqIdx
      entries(idx).valid        := true.B
      entries(idx).addrValid    := false.B
      entries(idx).alreadyFlush := false.B
      
      entries(idx).readyToIssue := false.B // ★ 入队时初始化为 false
      
      entries(idx).issued       := false.B
      entries(idx).dataValid    := false.B
      entries(idx).writtenBack  := false.B
      entries(idx).vaddr        := 0.U
      entries(idx).paddr        := 0.U
      entries(idx).cacheable    := false.B
      entries(idx).data         := 0.U
      entries(idx).excp         := 0.U.asTypeOf(new ExceptionBundle)
      entries(idx).lsuOp        := io.enq.lsuOp
      entries(idx).pc           := io.enq.pc
      entries(idx).pdst         := io.enq.pdst
      entries(idx).rfWen        := io.enq.rfWen
      entries(idx).fuType       := io.enq.fuType
      entries(idx).cacheOp        := io.enq.cacheOp
      enqPtr := enqPtr + 1.U
    }
   
    // ================================================================
    //  重定向
    // ================================================================
    val doRedirect      = io.redirectInfo.valid && io.redirectInfo.bits.doRedirect
    val redirectRobIdx = io.redirectInfo.bits.robIdx
    val isNewer = Wire(Vec(LqSize, Bool()))
   
    for (i <- 0 until LqSize) {
      val e = entries(i)
      isNewer(i) := e.robIdxFull.isAfter(redirectRobIdx) && doRedirect && e.valid
      when(isNewer(i)) {
        e.alreadyFlush := true.B
      }
    }
   
    // ================================================================
    //  2. 地址写入
    // ================================================================
    val addrTargetMatches =
      (!entries(io.addrWrite.idx).issued &&
       entries(io.addrWrite.idx).valid &&
       entries(io.addrWrite.idx).robIdxFull === io.addrWrite.robIdx) ||
      (enqFire && enqPtr.value === io.addrWrite.idx &&
       io.enq.robIdx === io.addrWrite.robIdx)
    when(io.addrWrite.valid && addrTargetMatches) {
      val idx = io.addrWrite.idx
      entries(idx).addrValid := true.B
      entries(idx).vaddr     := io.addrWrite.vaddr
      entries(idx).paddr     := io.addrWrite.paddr
      entries(idx).excp      := io.addrWrite.excp
      entries(idx).cacheable := io.addrWrite.cacheable
      when(entries(idx).cacheOp.valid) {
        entries(idx).issued := true.B
        entries(idx).dataValid := true.B
        entries(idx).data := 0.U
      }
    }
   
    // ================================================================
    //  3. 第一级：SQ 分析与前递仲裁 (状态打拍)
    //
    //  Keep registered eligibility for the slow path, with a same-cycle
    //  cacheable, nonconflicting path through the same SQ safety checks.
    // ================================================================
    val fastIssueReady = WireInit(VecInit(Seq.fill(LqSize)(false.B)))
    for (lqI <- 0 until LqSize) {
      val e = entries(lqI)
      val isUncache     = !e.cacheable
      val loadPaddrWord = e.paddr(XLEN - 1, wordAlignBits)
   
      // 基础条件：有效，地址算出，还没发射，没有异常，没有被冲刷，且尚未被判定为 readyToIssue
      val basicReady = e.valid && e.addrValid && !e.issued &&
                       !e.excp.hasException && !e.alreadyFlush && !e.readyToIssue
   
      when(basicReady) {
        val sqOlderActive       = Wire(Vec(SqSize, Bool()))
        val sqOlderAddrUnknown  = Wire(Vec(SqSize, Bool()))
        val sqOlderUncache      = Wire(Vec(SqSize, Bool()))
        val sqOlderAddrConflict = Wire(Vec(SqSize, Bool()))
   
        for (sqI <- 0 until SqSize) {
          val sq = io.sqForwardInfo(sqI)
          val isOlder  = e.robIdxFull.isAfter(sq.robIdxFull) || sq.committed
          val isActive = sq.valid && !sq.alreadyFlush && !sq.hasException
   
          sqOlderActive(sqI)       := isOlder && isActive
          sqOlderAddrUnknown(sqI)  := sqOlderActive(sqI) && !sq.addrValid
          sqOlderUncache(sqI)      := sqOlderActive(sqI) && sq.addrValid && !sq.cacheable
          sqOlderAddrConflict(sqI) := sqOlderActive(sqI) && sq.addrValid && sq.cacheable &&
                                      (sq.paddr(XLEN - 1, wordAlignBits) === loadPaddrWord)
        }
   
        val hasOlderActive      = sqOlderActive.reduce(_ || _)
        val hasOlderAddrUnknown = sqOlderAddrUnknown.reduce(_ || _)
        val hasOlderUncache     = sqOlderUncache.reduce(_ || _)
   
        val conflictCount       = PopCount(sqOlderAddrConflict)
        val hasNoConflict       = conflictCount === 0.U
        val hasSingleConflict   = conflictCount === 1.U
        fastIssueReady(lqI) := !isUncache && !hasOlderAddrUnknown &&
                              !hasOlderUncache && hasNoConflict
   
        // 情况 1：Uncache load (必须在 ROB Head 才能发射)
        when(isUncache) {
          when(!hasOlderActive && io.robHead.bits === e.robIdxFull) {
             e.readyToIssue := true.B  // 满足条件，标记为可发射
          }
        }
        // 情况 2：Cacheable load
        .otherwise {
          when(!hasOlderActive) {
            e.readyToIssue := true.B
          }
          .otherwise {
            // 有未决地址或 uncache 更老 store 时，继续等待，readyToIssue 维持 false
            when(!hasOlderAddrUnknown && !hasOlderUncache) {
              when(hasNoConflict) {
                e.readyToIssue := true.B
              }
              .elsewhen(hasSingleConflict && !e.cacheOp.valid) {
                // 处理唯一的前递冲突
                val singleConflictIdx = PriorityEncoder(sqOlderAddrConflict)
                val singleSq = io.sqForwardInfo(singleConflictIdx)
   
                when(LsuOp.isStore(singleSq.lsuOp) && singleSq.dataValid &&
                     MemData.storeCoversLoad(singleSq.paddr, singleSq.lsuOp,
                                             e.paddr, e.lsuOp) && !isNewer(lqI)) {
                  // ★ 前递逻辑：数据已经就绪，直接截断！不需要 dcache，直接视为已经发射和数据有效
                  e.issued    := true.B
                  e.dataValid := true.B
                  
                  val alignedStore = MemData.alignStore(
                    singleSq.data, singleSq.paddr, singleSq.lsuOp, XLEN)
                  e.data := MemData.extract(alignedStore, e.paddr, e.lsuOp, XLEN)
                }
              }
            }
          }
        }
      }
    }
   
    // ================================================================
    //  3b. 第二级：基于状态寄存器，计算 DCache 发射候选 
    //
    //  Safe cacheable loads need not wait for readyToIssue to be registered.
    // ================================================================
    val issueCandidates = Wire(Vec(LqSize, Bool()))
    for (i <- 0 until LqSize) {
      val idx = (deqPtr.value + i.U)(log2Ceil(LqSize) - 1, 0)
      val e = entries(idx)
      issueCandidates(i) := e.valid && !e.issued && !e.alreadyFlush && 
                            !e.excp.hasException && !e.cacheOp.valid &&
                            (e.readyToIssue || fastIssueReady(idx))
    }
   
    val hasIssueCandidate = issueCandidates.reduce(_ || _)
    val issueOffset       = PriorityEncoder(issueCandidates)
    val issueIdx          = (deqPtr.value + issueOffset)(log2Ceil(LqSize) - 1, 0)
    val issueEntry        = entries(issueIdx)
   
    // ── 3c. 真正的 DCache 请求 ──
    io.dcacheReq.valid           := hasIssueCandidate && !isNewer(issueIdx) 
    io.dcacheReq.bits.lqIdx      := issueIdx
    io.dcacheReq.bits.paddr      := issueEntry.paddr
    io.dcacheReq.bits.vaddr      := issueEntry.vaddr
    io.dcacheReq.bits.cacheable  := issueEntry.cacheable
    io.dcacheReq.bits.robIdx     := issueEntry.robIdxFull
    io.dcacheReq.bits.lsuOp := Mux(issueEntry.lsuOp === LsuOp.lrw,
      LsuOp.lw, issueEntry.lsuOp)
   
    when(io.dcacheReq.fire) {
      entries(issueIdx).issued := true.B
      // 发射后，不需要刻意清空 readyToIssue，因为 issued = true 会在此后一直屏蔽它
    }
   
    // ================================================================
    //  4. 接收 DCache 响应 & 5. 向后端写回 (包含旁路机制)
    // ================================================================
    io.dcacheResp.ready := true.B
    
    when(io.dcacheResp.fire) {
      val idx = io.dcacheResp.bits.lqIdx
      entries(idx).dataValid := true.B
      entries(idx).data      := io.dcacheResp.bits.data
    }
   
    val wbCandidates = Wire(Vec(LqSize, Bool()))
    for (i <- 0 until LqSize) {
      val idx = (deqPtr.value + i.U)(log2Ceil(LqSize) - 1, 0)
      val e = entries(idx)
      wbCandidates(i) := e.valid && !e.alreadyFlush && (e.dataValid || e.excp.hasException) && !e.writtenBack
    }
   
    val hasLocalWbCandidate = wbCandidates.reduce(_ || _)
    val wbOffset            = PriorityEncoder(wbCandidates)
    val localWbIdx          = (deqPtr.value + wbOffset)(log2Ceil(LqSize) - 1, 0)
    
    val dcacheRespValid = io.dcacheResp.valid
    val dcacheRespIdx   = io.dcacheResp.bits.lqIdx
    val dcacheRespData  = io.dcacheResp.bits.data
    val dcacheRespEntry = entries(dcacheRespIdx)
    
    val doBypass = dcacheRespValid && !dcacheRespEntry.alreadyFlush
    
    val finalWbValid = doBypass || hasLocalWbCandidate
    val finalWbIdx   = Mux(doBypass, dcacheRespIdx, localWbIdx)
    val finalWbData  = Mux(doBypass, dcacheRespData, entries(localWbIdx).data)
    
    val finalWbEntry = entries(finalWbIdx)
    val wbIsCacheOp    = finalWbEntry.cacheOp.valid
   
    io.outResult.valid                := finalWbValid
    io.outResult.bits.data            := finalWbData
    io.outResult.bits.memValid        := !wbIsCacheOp
    io.outResult.bits.memRead         := !wbIsCacheOp
    io.outResult.bits.memWrite        := false.B
    io.outResult.bits.vaddr        := finalWbEntry.vaddr
    io.outResult.bits.memPaddr        := finalWbEntry.paddr
    io.outResult.bits.memStoreData    := 0.U
    io.outResult.bits.storeValid      := false.B
    io.outResult.bits.redirect.valid  := DontCare
    io.outResult.bits.redirect.bits.valid  := DontCare
    io.outResult.bits.redirect.bits.robIdx := DontCare
    io.outResult.bits.csrWen     := DontCare
    io.outResult.bits.csrWaddr   := DontCare
    io.outResult.bits.csrWdata   := DontCare
    io.outResult.bits.csrTimer   := DontCare
    io.outResult.bits.tlbFillIdx := 0.U
   
    val wbUop = io.outResult.bits.uop
    wbUop.pc         := finalWbEntry.pc
    wbUop.inst       := 0.U
    wbUop.excp       := finalWbEntry.excp
    wbUop.imm        := 0.U
    wbUop.csrAddress := 0.U
    wbUop.ldst       := 0.U
    wbUop.lrs1       := 0.U
    wbUop.lrs2       := 0.U
    wbUop.pdst       := finalWbEntry.pdst
    wbUop.prs1       := 0.U
    wbUop.prs2       := 0.U
    wbUop.oldPdst    := 0.U
    wbUop.rs1Valid   := false.B
    wbUop.rs2Valid   := false.B
    wbUop.rdValid    := finalWbEntry.rfWen
    wbUop.robIdx     := finalWbEntry.robIdxFull
    wbUop.robIdxFull := finalWbEntry.robIdxFull
    wbUop.issueQueue := 0.U
    wbUop.prs1Busy   := false.B
    wbUop.prs2Busy   := false.B
    wbUop.isSta      := false.B
    wbUop.isStd      := false.B
   
    val wbLqIdx = Wire(new LqPtr(LqSize))
    wbLqIdx.value := finalWbIdx
    wbLqIdx.flag  := false.B
    wbUop.lqIdx   := wbLqIdx
   
    val wbSqIdx = Wire(new SqPtr(SqSize))
    wbSqIdx.value := finalWbEntry.sqIdx
    wbSqIdx.flag  := false.B
    wbUop.sqIdx   := wbSqIdx
   
    wbUop.ctrl.fuType   := finalWbEntry.fuType
    wbUop.ctrl.lsuOp    := finalWbEntry.lsuOp
    wbUop.ctrl.barOp    := BarOp.none
    wbUop.ctrl.rfWen    := finalWbEntry.rfWen
    wbUop.ctrl.memRead  := !wbIsCacheOp
    wbUop.ctrl.memWrite := false.B
    wbUop.ctrl.aluOp    := 0.U
    wbUop.ctrl.bruOp    := 0.U
    wbUop.ctrl.csrOp    := 0.U
    wbUop.ctrl.tlbOp    := TlbOp.none
    wbUop.ctrl.mulOp    := 0.U
    wbUop.ctrl.divOp    := 0.U
    wbUop.ctrl.src1Type := 0.U
    wbUop.ctrl.src2Type := 0.U
    wbUop.ctrl.immType  := 0.U
    wbUop.ctrl.csrWen   := false.B
    wbUop.ctrl.isBranch := false.B
    wbUop.ctrl.isJump   := false.B
    wbUop.ctrl.privLevel   := 0.U
    wbUop.ctrl.isIdle   := false.B
    wbUop.ctrl.waitForward := false.B
    wbUop.ctrl.blockBackward := false.B
    wbUop.ctrl.flushOnCommit := false.B
    wbUop.cacheOp := finalWbEntry.cacheOp
   
    wbUop.pdInfo  := DontCare
    wbUop.bpuInfo := DontCare
    wbUop.snptId  := DontCare
   
    when(io.outResult.fire) {
      entries(finalWbIdx).writtenBack := true.B
    }
   
    // ================================================================
    //  6. 出队
    // ================================================================
    val canDeq = entries(deqPtr.value).valid &&
                 (entries(deqPtr.value).writtenBack || entries(deqPtr.value).alreadyFlush)
    when(canDeq) {
      entries(deqPtr.value).valid := false.B
      deqPtr := deqPtr + 1.U
    }

    when(enqFire && !canDeq) {
      lqHasEntriesReg := lqHasEntriesReg + 1.U
    }.elsewhen(!enqFire && canDeq) {
      lqHasEntriesReg := lqHasEntriesReg - 1.U
    }
}
