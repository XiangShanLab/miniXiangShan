package minixiangshan.frontend

import chisel3._
import chisel3.util._
import minixiangshan.config.Parameters
import minixiangshan.config._
import minixiangshan.config.NSModule
import minixiangshan.config.NSBundle
import minixiangshan.util.SimpleBlockRAM
import scala.util.Random

class BPU(implicit p: Parameters) extends NSModule {
  val io = IO(new Bundle {
    // 预测接口
    val predictReq   = Input(new BpuPredictReq)
    val predictResp  = Output(new BpuPredictResp)
    val predictFire  = Input(Bool())   

    // BPU更新接口
    val update_pd    = Input(new BpuUpdateReq)   
    val update_br    = Input(new BpuUpdateReq)   

    // RAS接口 (已弃用，保留以防止顶层连线报错)
    val rasRestore     = Input(Bool())
    val rasRestoreTop  = Input(UInt(log2Ceil(rasSize).W))
  })

  // 提取公共变量：位宽计算
  val btbEntryNoValidWidth = 0.U.asTypeOf(new BTBEntryNoValid).getWidth
  val mergedWidth = btbEntryNoValidWidth + 2 // 将 BTB entry 与 2-bit PHT counter 拼接


    // ==================== 辅助计算 ====================
    val cacheLineBits = log2Ceil(blockBytes)
   
    // ==================== 实例化 4 个 整合 Bank ====================
    val validArrays = Seq.fill(4)(RegInit(VecInit(Seq.fill(BtbPhtSize)(false.B))))
    // 统一替换为合并后的 btbPhtMems
    val btbPhtMems = Seq.tabulate(4)(_ =>
      Module(new SimpleBlockRAM(depth = BtbPhtSize, width = mergedWidth, readLatency = 1)))
   
    // ==================== 读请求逻辑 ====================
    val nextPC = io.predictReq.nextPC
   
    val bankReadAddr = Wire(Vec(4, UInt(btbIndexBits.W)))
   
    for (b <- 0 until 4) {
      bankReadAddr(b) := 0.U
    }
   
    for (i <- 0 until 4) {
      val pc_i   = nextPC + (i * 4).U
      val bank   = pc_i(3, 2)
      val btbIdx = pc_i(btbIndexBits + 3, 4) // 这里统一使用 btbIndexBits 作为共用索引宽度
   
      when(bank === 0.U) {
        bankReadAddr(0) := btbIdx
      } .elsewhen(bank === 1.U) {
        bankReadAddr(1) := btbIdx
      } .elsewhen(bank === 2.U) {
        bankReadAddr(2) := btbIdx
      } .otherwise {
        bankReadAddr(3) := btbIdx
      }
    }
   
    // 发送读请求到 4 个 Bank
    for (b <- 0 until 4) {
      btbPhtMems(b).io.rd_en   := io.predictReq.rdBpu
      btbPhtMems(b).io.rd_addr := bankReadAddr(b)
    }
   
    // ==================== 预测处理逻辑 ====================
    val curPC = io.predictReq.pc
   
    val btbEntries   = Wire(Vec(4, new BTBEntryNoValid))
    val phtCounters  = Wire(Vec(4, UInt(2.W)))
    val entryValids  = Wire(Vec(4, Bool()))
    val btbHits      = Wire(Vec(4, Bool()))
    val predTakens   = Wire(Vec(4, Bool()))
    val crossLines   = Wire(Vec(4, Bool()))
   
    for (i <- 0 until 4) {
      val pc_i   = curPC + (i * 4).U
      val bank   = pc_i(3, 2)
      val btbIdx = pc_i(btbIndexBits + 3, 4)
      val tag    = pc_i(XLEN - 1, btbIndexBits + 4)
   
      // 读取合并数据并拆解
      val mergedData = MuxLookup(bank, 0.U, (0 until 4).map(b => b.U -> btbPhtMems(b).io.rd_data))
      val valid      = MuxLookup(bank, false.B, (0 until 4).map(b => b.U -> validArrays(b)(btbIdx)))
   
      btbEntries(i)  := mergedData(mergedWidth - 1, 2).asTypeOf(new BTBEntryNoValid)
      phtCounters(i) := mergedData(1, 0)
      entryValids(i) := valid
   
      crossLines(i) := pc_i(XLEN - 1, cacheLineBits) =/= curPC(XLEN - 1, cacheLineBits)
      btbHits(i) := !crossLines(i) && valid && (btbEntries(i).tag === tag)
   
      val phtTaken = phtCounters(i)(1)
      predTakens(i) := btbHits(i) && (btbEntries(i).isJalr || btbEntries(i).isJal || phtTaken)
    }
   
    // ==================== 仲裁与输出 ====================
    val finalTaken  = predTakens.asUInt.orR
    val finalTarget = MuxCase(0.U, (0 until 4).map(i => predTakens(i) -> btbEntries(i).target))
    val finalOffset = MuxCase(0.U, (0 until 4).map(i => predTakens(i) -> i.U))
    val finalHit    = btbHits.asUInt.orR
   
    val selectedPos = MuxCase(0.U, (0 until 4).map(i => btbHits(i) -> i.U))
   
    io.predictResp.taken       := finalTaken
    io.predictResp.takenOffset := finalOffset
    io.predictResp.target      := finalTarget
   
    io.predictResp.meta.btbHit     := finalHit
    io.predictResp.meta.valid      := entryValids(selectedPos)
    io.predictResp.meta.btbIsJalr  := btbEntries(selectedPos).isJalr
    io.predictResp.meta.btbIsJal   := btbEntries(selectedPos).isJal
    io.predictResp.meta.btbIsCall  := btbEntries(selectedPos).isCall
    io.predictResp.meta.btbIsRet   := btbEntries(selectedPos).isRet
    io.predictResp.meta.btbOffset  := btbEntries(selectedPos).offset
    io.predictResp.meta.phtCounter := phtCounters(selectedPos)
    io.predictResp.meta.rasTop     := 0.U
    io.predictResp.meta.predTaken  := finalTaken
    io.predictResp.meta.predTarget := finalTarget
   
    // ==================== BPU 更新逻辑 ====================
    val doUpdate = io.update_br.valid || io.update_pd.valid
    val update   = Mux(io.update_br.valid, io.update_br, io.update_pd)
   
    for (b <- 0 until 4) {
      btbPhtMems(b).io.wr_en   := false.B
      btbPhtMems(b).io.wr_addr := 0.U
      btbPhtMems(b).io.wr_data := 0.U
    }
   
    when(doUpdate) {
      val updatePC     = update.pc
      val updateBank   = updatePC(3, 2)
      val updateBtbIdx = updatePC(btbIndexBits + 3, 4)
      val updateTag    = updatePC(XLEN - 1, btbIndexBits + 4)
   
      val newEntry = Wire(new BTBEntryNoValid)
      newEntry.tag    := updateTag
      newEntry.target := update.target
      newEntry.isJalr := update.isJalr
      newEntry.isJal  := update.isJal
      newEntry.isCall := update.isCall
      newEntry.isRet  := update.isRet
      newEntry.offset := update.offset
   
      val oldCounter  = update.oldPhtCounter
      val nextCounter = WireDefault(oldCounter)
      when(!update.validEntry) {
        nextCounter := 2.U
      } .elsewhen(update.taken && oldCounter =/= 3.U) {
        nextCounter := oldCounter + 1.U
      } .elsewhen(!update.taken && oldCounter =/= 0.U) {
        nextCounter := oldCounter - 1.U
      }
      
      val mergedWriteData = Cat(newEntry.asUInt, nextCounter)
   
      for (b <- 0 until 4) {
        when(updateBank === b.U) {
          btbPhtMems(b).io.wr_en   := true.B
          btbPhtMems(b).io.wr_addr := updateBtbIdx
          btbPhtMems(b).io.wr_data := mergedWriteData
          validArrays(b)(updateBtbIdx) := update.validEntry
        }
      }
    }
  
}