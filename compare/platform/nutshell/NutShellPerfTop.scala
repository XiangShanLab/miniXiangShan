package sim

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils

import bus.axi4._
import bus.simplebus._
import nutcore.NutCoreConfig
import system.NutShell

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
    val s_axi_arlock  = Input(Bool())
    val s_axi_arcache = Input(UInt(4.W))
    val s_axi_arprot  = Input(UInt(3.W))
    val s_axi_arqos   = Input(UInt(4.W))
    val s_axi_aruser  = Input(Bool())
    val s_axi_arvalid = Input(Bool())
    val s_axi_arready = Output(Bool())

    val s_axi_rid    = Output(UInt(4.W))
    val s_axi_rdata  = Output(UInt(64.W))
    val s_axi_rresp  = Output(UInt(2.W))
    val s_axi_rlast  = Output(Bool())
    val s_axi_ruser  = Output(Bool())
    val s_axi_rvalid = Output(Bool())
    val s_axi_rready = Input(Bool())

    val s_axi_awid    = Input(UInt(4.W))
    val s_axi_awaddr  = Input(UInt(32.W))
    val s_axi_awlen   = Input(UInt(8.W))
    val s_axi_awsize  = Input(UInt(3.W))
    val s_axi_awburst = Input(UInt(2.W))
    val s_axi_awlock  = Input(Bool())
    val s_axi_awcache = Input(UInt(4.W))
    val s_axi_awprot  = Input(UInt(3.W))
    val s_axi_awqos   = Input(UInt(4.W))
    val s_axi_awuser  = Input(Bool())
    val s_axi_awvalid = Input(Bool())
    val s_axi_awready = Output(Bool())

    val s_axi_wdata  = Input(UInt(64.W))
    val s_axi_wstrb  = Input(UInt(8.W))
    val s_axi_wlast  = Input(Bool())
    val s_axi_wuser  = Input(Bool())
    val s_axi_wvalid = Input(Bool())
    val s_axi_wready = Output(Bool())

    val s_axi_bid    = Output(UInt(4.W))
    val s_axi_bresp  = Output(UInt(2.W))
    val s_axi_buser  = Output(Bool())
    val s_axi_bvalid = Output(Bool())
    val s_axi_bready = Input(Bool())

    val perf_done       = Output(Bool())
    val perf_signature  = Output(UInt(32.W))
    val perf_cycle_lo   = Output(UInt(32.W))
    val perf_cycle_hi   = Output(UInt(32.W))
    val perf_instret_lo = Output(UInt(32.W))
    val perf_instret_hi = Output(UInt(32.W))
    val perf_checksum   = Output(UInt(32.W))
  })
}

/**
 * Serialize the two AXI4 masters into one slave port.  Both NutShell paths use
 * the existing single-outstanding SimpleBus-to-AXI4 converters, so granting a
 * whole read burst or write transaction is sufficient here.
 */
class Axi4Mux2 extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Vec(2, new AXI4))
    val out = new AXI4
  })

  val idle :: read :: write :: Nil = Enum(3)
  val state = RegInit(idle)
  val selReg = RegInit(0.U(1.W))

  val readReq = VecInit(io.in.map(_.ar.valid))
  val writeReq = VecInit(io.in.map(_.aw.valid))
  val readSel = PriorityEncoder(readReq)
  val writeSel = PriorityEncoder(writeReq)
  val startRead = state === idle && readReq.asUInt.orR
  val startWrite = state === idle && !readReq.asUInt.orR && writeReq.asUInt.orR
  val sel = Mux(state === idle, Mux(startRead, readSel, writeSel), selReg)
  val readPath = state === read || startRead
  val writePath = state === write || startWrite

  io.out.ar.bits := io.in(sel).ar.bits
  io.out.aw.bits := io.in(sel).aw.bits
  io.out.w.bits := io.in(sel).w.bits
  io.out.ar.valid := readPath && io.in(sel).ar.valid
  io.out.aw.valid := writePath && io.in(sel).aw.valid
  io.out.w.valid := writePath && io.in(sel).w.valid
  io.out.r.ready := state === read && io.in(selReg).r.ready
  io.out.b.ready := state === write && io.in(selReg).b.ready

  for (i <- 0 until 2) {
    io.in(i).ar.ready := readPath && sel === i.U && io.out.ar.ready
    io.in(i).aw.ready := writePath && sel === i.U && io.out.aw.ready
    io.in(i).w.ready := writePath && sel === i.U && io.out.w.ready

    io.in(i).r.valid := state === read && selReg === i.U && io.out.r.valid
    io.in(i).r.bits := io.out.r.bits
    io.in(i).b.valid := state === write && selReg === i.U && io.out.b.valid
    io.in(i).b.bits := io.out.b.bits
  }

  when(io.out.ar.fire && state === idle) {
    state := read
    selReg := sel
  }.elsewhen(io.out.aw.fire && state === idle) {
    state := write
    selReg := sel
  }
  when(state === read && io.out.r.fire && io.out.r.bits.last) {
    state := idle
  }
  when(state === write && io.out.b.fire) {
    state := idle
  }
}

/** NutShell performance-comparison top using the shared external platform. */
class NutShellPerfTop extends RawModule {
  val aclk = IO(Input(Clock()))
  val aresetn = IO(Input(Bool()))

  val perf_done = IO(Output(Bool()))
  val perf_signature = IO(Output(UInt(32.W)))
  val perf_cycle_lo = IO(Output(UInt(32.W)))
  val perf_cycle_hi = IO(Output(UInt(32.W)))
  val perf_instret_lo = IO(Output(UInt(32.W)))
  val perf_instret_hi = IO(Output(UInt(32.W)))
  val perf_checksum = IO(Output(UInt(32.W)))

  // Fixed three-lane retire trace interface shared with the miniXiangShan
  // harness.  NutShell retires up to two instructions per cycle; lane 2 is
  // tied low.  These are ROB retire signals, not unordered CDB writes.
  val trace_valid = IO(Output(UInt(3.W)))
  val trace_pc_0  = IO(Output(UInt(64.W)))
  val trace_pc_1  = IO(Output(UInt(64.W)))
  val trace_pc_2  = IO(Output(UInt(64.W)))
  val trace_inst_0 = IO(Output(UInt(32.W)))
  val trace_inst_1 = IO(Output(UInt(32.W)))
  val trace_inst_2 = IO(Output(UInt(32.W)))

  val (soc, mmioBridge, busMux, platform) = withClockAndReset(aclk, ~aresetn) {
    val soc = Module(new NutShell()(NutCoreConfig(FPGAPlatform = false, EnableDifftest = false)))
    val mmioBridge = Module(new SimpleBus2AXI4Converter(new AXI4, false))
    val busMux = Module(new Axi4Mux2)
    val platform = Module(new Axi4PerfPlatform)
    (soc, mmioBridge, busMux, platform)
  }

  val traceValid = Wire(UInt(2.W))
  val tracePc0 = Wire(UInt(64.W))
  val tracePc1 = Wire(UInt(64.W))
  val traceInst0 = Wire(UInt(64.W))
  val traceInst1 = Wire(UInt(64.W))
  BoringUtils.addSink(traceValid, "perfTraceValid")
  BoringUtils.addSink(tracePc0, "perfTracePc0")
  BoringUtils.addSink(tracePc1, "perfTracePc1")
  BoringUtils.addSink(traceInst0, "perfTraceInst0")
  BoringUtils.addSink(traceInst1, "perfTraceInst1")

  trace_valid := Cat(0.U(1.W), traceValid)
  trace_pc_0 := tracePc0
  trace_pc_1 := tracePc1
  trace_pc_2 := 0.U
  trace_inst_0 := traceInst0(31, 0)
  trace_inst_1 := traceInst1(31, 0)
  trace_inst_2 := 0.U

  mmioBridge.io.in <> soc.io.mmio
  busMux.io.in(0) <> soc.io.mem
  busMux.io.in(1) <> mmioBridge.io.out

  // The DMA frontend is unused by this bare-metal kernel.
  val frontend = WireInit(0.U.asTypeOf(new AXI4))
  frontend.r.ready := true.B
  frontend.b.ready := true.B
  soc.io.frontend <> frontend
  soc.io.meip := 0.U

  platform.io.aclk := aclk
  platform.io.aresetn := aresetn
  platform.io.s_axi_arid := Cat(0.U(3.W), busMux.io.out.ar.bits.id)
  platform.io.s_axi_araddr := busMux.io.out.ar.bits.addr
  platform.io.s_axi_arlen := busMux.io.out.ar.bits.len
  platform.io.s_axi_arsize := busMux.io.out.ar.bits.size
  platform.io.s_axi_arburst := busMux.io.out.ar.bits.burst
  platform.io.s_axi_arlock := busMux.io.out.ar.bits.lock
  platform.io.s_axi_arcache := busMux.io.out.ar.bits.cache
  platform.io.s_axi_arprot := busMux.io.out.ar.bits.prot
  platform.io.s_axi_arqos := busMux.io.out.ar.bits.qos
  platform.io.s_axi_aruser := busMux.io.out.ar.bits.user
  platform.io.s_axi_arvalid := busMux.io.out.ar.valid
  busMux.io.out.ar.ready := platform.io.s_axi_arready

  busMux.io.out.r.bits.id := platform.io.s_axi_rid(0)
  busMux.io.out.r.bits.data := platform.io.s_axi_rdata
  busMux.io.out.r.bits.resp := platform.io.s_axi_rresp
  busMux.io.out.r.bits.last := platform.io.s_axi_rlast
  busMux.io.out.r.bits.user := platform.io.s_axi_ruser
  busMux.io.out.r.valid := platform.io.s_axi_rvalid
  platform.io.s_axi_rready := busMux.io.out.r.ready

  platform.io.s_axi_awid := Cat(0.U(3.W), busMux.io.out.aw.bits.id)
  platform.io.s_axi_awaddr := busMux.io.out.aw.bits.addr
  platform.io.s_axi_awlen := busMux.io.out.aw.bits.len
  platform.io.s_axi_awsize := busMux.io.out.aw.bits.size
  platform.io.s_axi_awburst := busMux.io.out.aw.bits.burst
  platform.io.s_axi_awlock := busMux.io.out.aw.bits.lock
  platform.io.s_axi_awcache := busMux.io.out.aw.bits.cache
  platform.io.s_axi_awprot := busMux.io.out.aw.bits.prot
  platform.io.s_axi_awqos := busMux.io.out.aw.bits.qos
  platform.io.s_axi_awuser := busMux.io.out.aw.bits.user
  platform.io.s_axi_awvalid := busMux.io.out.aw.valid
  busMux.io.out.aw.ready := platform.io.s_axi_awready

  platform.io.s_axi_wdata := busMux.io.out.w.bits.data
  platform.io.s_axi_wstrb := busMux.io.out.w.bits.strb
  platform.io.s_axi_wlast := busMux.io.out.w.bits.last
  platform.io.s_axi_wuser := false.B
  platform.io.s_axi_wvalid := busMux.io.out.w.valid
  busMux.io.out.w.ready := platform.io.s_axi_wready

  busMux.io.out.b.bits.id := platform.io.s_axi_bid(0)
  busMux.io.out.b.bits.resp := platform.io.s_axi_bresp
  busMux.io.out.b.bits.user := platform.io.s_axi_buser
  busMux.io.out.b.valid := platform.io.s_axi_bvalid
  platform.io.s_axi_bready := busMux.io.out.b.ready

  perf_done := platform.io.perf_done
  perf_signature := platform.io.perf_signature
  perf_cycle_lo := platform.io.perf_cycle_lo
  perf_cycle_hi := platform.io.perf_cycle_hi
  perf_instret_lo := platform.io.perf_instret_lo
  perf_instret_hi := platform.io.perf_instret_hi
  perf_checksum := platform.io.perf_checksum

}
