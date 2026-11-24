package minixiangshan.perf

import chisel3._
import minixiangshan.core_top
import minixiangshan.config._
import minixiangshan.axi._

/** Flat-port wrapper around the shared AXI4 performance platform. */
class Axi4PerfPlatform extends BlackBox {
  override def desiredName: String = "Axi4PerfPlatform"

  val io = IO(new Bundle {
    val aclk    = Input(Clock())
    val aresetn = Input(Bool())

    val s_axi_arid    = Input(UInt(4.W))
    val s_axi_araddr  = Input(UInt(32.W))
    val s_axi_arlen   = Input(UInt(8.W))
    val s_axi_arsize  = Input(UInt(3.W))
    val s_axi_arburst = Input(UInt(2.W))
    val s_axi_arlock  = Input(UInt(1.W))
    val s_axi_arcache = Input(UInt(4.W))
    val s_axi_arprot  = Input(UInt(3.W))
    val s_axi_arqos   = Input(UInt(4.W))
    val s_axi_aruser  = Input(UInt(1.W))
    val s_axi_arvalid = Input(Bool())
    val s_axi_arready = Output(Bool())

    val s_axi_rid    = Output(UInt(4.W))
    val s_axi_rdata  = Output(UInt(64.W))
    val s_axi_rresp  = Output(UInt(2.W))
    val s_axi_rlast  = Output(Bool())
    val s_axi_ruser  = Output(UInt(1.W))
    val s_axi_rvalid = Output(Bool())
    val s_axi_rready = Input(Bool())

    val s_axi_awid    = Input(UInt(4.W))
    val s_axi_awaddr  = Input(UInt(32.W))
    val s_axi_awlen   = Input(UInt(8.W))
    val s_axi_awsize  = Input(UInt(3.W))
    val s_axi_awburst = Input(UInt(2.W))
    val s_axi_awlock  = Input(UInt(1.W))
    val s_axi_awcache = Input(UInt(4.W))
    val s_axi_awprot  = Input(UInt(3.W))
    val s_axi_awqos   = Input(UInt(4.W))
    val s_axi_awuser  = Input(UInt(1.W))
    val s_axi_awvalid = Input(Bool())
    val s_axi_awready = Output(Bool())

    val s_axi_wdata  = Input(UInt(64.W))
    val s_axi_wstrb  = Input(UInt(8.W))
    val s_axi_wlast  = Input(Bool())
    val s_axi_wuser  = Input(UInt(1.W))
    val s_axi_wvalid = Input(Bool())
    val s_axi_wready = Output(Bool())

    val s_axi_bid    = Output(UInt(4.W))
    val s_axi_bresp  = Output(UInt(2.W))
    val s_axi_buser  = Output(UInt(1.W))
    val s_axi_bvalid = Output(Bool())
    val s_axi_bready = Input(Bool())

    val perf_done         = Output(Bool())
    val perf_signature    = Output(UInt(32.W))
    val perf_cycle_lo     = Output(UInt(32.W))
    val perf_cycle_hi     = Output(UInt(32.W))
    val perf_instret_lo   = Output(UInt(32.W))
    val perf_instret_hi   = Output(UInt(32.W))
    val perf_checksum     = Output(UInt(32.W))
  })
}

/**
 * Performance-comparison top for miniXiangShan.
 *
 * Only the external bus is changed.  The core, L1s, L2, and the existing
 * single-outstanding L2Bridge remain the same RTL as the normal build.
 */
class MiniPerfTop(implicit p: Parameters) extends NSRawModule {
  val difftest = if (EnableDifftest && !p(DebugConfigKeys.DifftestUseDPI))
    Some(IO(Output(new minixiangshan.difftest.CoreDifftestBundle))) else None
  val aclk    = IO(Input(Clock()))
  val aresetn = IO(Input(Bool()))

  val perf_done       = IO(Output(Bool()))
  val perf_signature  = IO(Output(UInt(32.W)))
  val perf_cycle_lo   = IO(Output(UInt(32.W)))
  val perf_cycle_hi   = IO(Output(UInt(32.W)))
  val perf_instret_lo = IO(Output(UInt(32.W)))
  val perf_instret_hi = IO(Output(UInt(32.W)))
  val perf_checksum   = IO(Output(UInt(32.W)))

  // Fixed three-lane retire trace interface.  The current core retires up to
  // CommitWidth=3 instructions per cycle; unused lanes are tied to zero so the
  // shared C++ testbench can read both cores with one schema.
  val trace_valid = IO(Output(UInt(3.W)))
  val trace_pc_0  = IO(Output(UInt(XLEN.W)))
  val trace_pc_1  = IO(Output(UInt(XLEN.W)))
  val trace_pc_2  = IO(Output(UInt(XLEN.W)))
  val trace_inst_0 = IO(Output(UInt(32.W)))
  val trace_inst_1 = IO(Output(UInt(32.W)))
  val trace_inst_2 = IO(Output(UInt(32.W)))

  val core = Module(new core_top)
  difftest.foreach(_ := core.difftestState.get)
  core.aclk    := aclk
  core.aresetn := aresetn
  core.intrpt  := 0.U
  core.break_point := false.B
  core.infor_flag  := false.B
  core.reg_num     := 0.U

  trace_valid := core.commit_valid.asUInt
  trace_pc_0  := core.commit_pc(0)
  trace_inst_0 := core.commit_inst(0)
  if (CommitWidth > 1) {
    trace_pc_1 := core.commit_pc(1)
    trace_inst_1 := core.commit_inst(1)
  } else {
    trace_pc_1 := 0.U
    trace_inst_1 := 0.U
  }
  if (CommitWidth > 2) {
    trace_pc_2 := core.commit_pc(2)
    trace_inst_2 := core.commit_inst(2)
  } else {
    trace_pc_2 := 0.U
    trace_inst_2 := 0.U
  }

  val platform = withClockAndReset(aclk, ~aresetn) {
    Module(new Axi4PerfPlatform)
  }
  platform.io.aclk := aclk
  platform.io.aresetn := aresetn

  // The comparison platform has a 32-bit address map.
  withClockAndReset(aclk, ~aresetn) {
    when(core.arvalid) {
      assert(core.araddr(63, 32) === 0.U, "performance read address exceeds platform")
    }
    when(core.awvalid) {
      assert(core.awaddr(63, 32) === 0.U, "performance write address exceeds platform")
    }
  }

  platform.io.s_axi_arid    := core.arid
  platform.io.s_axi_araddr  := core.araddr(31, 0)
  platform.io.s_axi_arlen   := core.arlen
  platform.io.s_axi_arsize  := core.arsize
  platform.io.s_axi_arburst := core.arburst
  platform.io.s_axi_arlock  := core.arlock
  platform.io.s_axi_arcache := core.arcache
  platform.io.s_axi_arprot  := core.arprot
  platform.io.s_axi_arqos   := core.arqos
  platform.io.s_axi_aruser  := core.aruser
  platform.io.s_axi_arvalid := core.arvalid
  core.arready := platform.io.s_axi_arready

  core.rid    := platform.io.s_axi_rid
  core.rdata  := platform.io.s_axi_rdata
  core.rresp  := platform.io.s_axi_rresp
  core.rlast  := platform.io.s_axi_rlast
  core.ruser  := platform.io.s_axi_ruser
  core.rvalid := platform.io.s_axi_rvalid
  platform.io.s_axi_rready := core.rready

  platform.io.s_axi_awid    := core.awid
  platform.io.s_axi_awaddr  := core.awaddr(31, 0)
  platform.io.s_axi_awlen   := core.awlen
  platform.io.s_axi_awsize  := core.awsize
  platform.io.s_axi_awburst := core.awburst
  platform.io.s_axi_awlock  := core.awlock
  platform.io.s_axi_awcache := core.awcache
  platform.io.s_axi_awprot  := core.awprot
  platform.io.s_axi_awqos   := core.awqos
  platform.io.s_axi_awuser  := core.awuser
  platform.io.s_axi_awvalid := core.awvalid
  core.awready := platform.io.s_axi_awready

  platform.io.s_axi_wdata  := core.wdata
  platform.io.s_axi_wstrb  := core.wstrb
  platform.io.s_axi_wlast  := core.wlast
  platform.io.s_axi_wuser  := core.wuser
  platform.io.s_axi_wvalid := core.wvalid
  core.wready  := platform.io.s_axi_wready

  core.bid    := platform.io.s_axi_bid
  core.bresp  := platform.io.s_axi_bresp
  core.buser  := platform.io.s_axi_buser
  core.bvalid := platform.io.s_axi_bvalid
  platform.io.s_axi_bready := core.bready

  perf_done       := platform.io.perf_done
  perf_signature  := platform.io.perf_signature
  perf_cycle_lo   := platform.io.perf_cycle_lo
  perf_cycle_hi   := platform.io.perf_cycle_hi
  perf_instret_lo := platform.io.perf_instret_lo
  perf_instret_hi := platform.io.perf_instret_hi
  perf_checksum   := platform.io.perf_checksum
}
