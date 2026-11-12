package minixiangshan.axi

import chisel3._
import chisel3.util._
import minixiangshan.config._

/**
 * AXI3 to AXI4 compatibility adapter used by the performance harness.
 *
 * Both interfaces are now 64 bits wide in the RV64 performance build.  AXI3
 * and AXI4 differ only in channel metadata here (AXI3 has WID, AXI4 has
 * user/qos fields), so data, strobes and burst metadata pass through directly.
 * The shared DRAM/platform therefore sees one identical transaction stream.
 */
class Axi3ToAxi4(implicit p: Parameters) extends NSModule {
  require(XLEN == 64, "Axi3ToAxi4 performance path requires the RV64 64-bit data bus")
  val io = IO(new Bundle {
    val in  = Flipped(new AXI3MasterIO)
    val out = new AXI4MasterIO
  })

  // Read address channel
  io.in.ar.arready := io.out.ar.arready
  io.out.ar.data.arid    := io.in.ar.data.arid
  io.out.ar.data.araddr  := io.in.ar.data.araddr(31, 0)
  io.out.ar.data.arlen   := io.in.ar.data.arlen
  io.out.ar.data.arsize  := io.in.ar.data.arsize
  io.out.ar.data.arburst := io.in.ar.data.arburst
  io.out.ar.data.arlock  := 0.U
  io.out.ar.data.arcache := io.in.ar.data.arcache
  io.out.ar.data.arprot  := io.in.ar.data.arprot
  io.out.ar.data.arqos   := 0.U
  io.out.ar.data.aruser  := 0.U
  io.out.ar.data.arvalid := io.in.ar.data.arvalid

  // Read data channel
  io.out.r.rready := io.in.r.rready
  io.in.r.data.rid   := io.out.r.data.rid
  io.in.r.data.rdata := io.out.r.data.rdata
  io.in.r.data.rresp := io.out.r.data.rresp
  io.in.r.data.rlast := io.out.r.data.rlast
  io.in.r.data.rvalid := io.out.r.data.rvalid

  // Write address channel
  io.in.aw.awready := io.out.aw.awready
  io.out.aw.data.awid    := io.in.aw.data.awid
  io.out.aw.data.awaddr  := io.in.aw.data.awaddr(31, 0)
  io.out.aw.data.awlen   := io.in.aw.data.awlen
  io.out.aw.data.awsize  := io.in.aw.data.awsize
  io.out.aw.data.awburst := io.in.aw.data.awburst
  io.out.aw.data.awlock  := 0.U
  io.out.aw.data.awcache := io.in.aw.data.awcache
  io.out.aw.data.awprot  := io.in.aw.data.awprot
  io.out.aw.data.awqos   := 0.U
  io.out.aw.data.awuser  := 0.U
  io.out.aw.data.awvalid := io.in.aw.data.awvalid

  // Write data channel.  AXI3 WID has no AXI4 equivalent; the shared platform
  // is single-outstanding and routes B responses by the AW ID.
  io.in.w.wready := io.out.w.wready
  io.out.w.data.wdata := io.in.w.data.wdata
  io.out.w.data.wstrb := io.in.w.data.wstrb
  io.out.w.data.wlast := io.in.w.data.wlast
  io.out.w.data.wuser := 0.U
  io.out.w.data.wvalid := io.in.w.data.wvalid

  // Write response channel
  io.in.b.data.bid    := io.out.b.data.bid
  io.in.b.data.bresp  := io.out.b.data.bresp
  io.in.b.data.bvalid := io.out.b.data.bvalid
  io.out.b.bready     := io.in.b.bready

  // The comparison platform has a 32-bit address map; fail on aliasing.
  when(io.in.ar.data.arvalid) {
    assert(io.in.ar.data.araddr(63, 32) === 0.U, "performance read address exceeds platform")
  }
  when(io.in.aw.data.awvalid) {
    assert(io.in.aw.data.awaddr(63, 32) === 0.U, "performance write address exceeds platform")
  }
}
