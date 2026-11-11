// Shared performance platform for NutShell and miniXiangShan.
//
// This is deliberately a small AXI4 slave, not a full interconnect:
//   * 64-bit data, 32-bit address, FIXED/INCR/WRAP bursts
//   * one outstanding read and one outstanding write
//   * one-cycle first response, one beat per cycle afterwards
//   * DRAM at 0x8000_0000 (16 MiB window used by the bare-metal images)
//   * performance/exit registers at 0x4000_f000
//   * write-only console at 0x4000_f038 (low data byte)
//
// Both CPU harnesses instantiate this exact file, so the external memory and
// MMIO timing are identical in the two simulations.
`timescale 1ns/1ps

module Axi4PerfPlatform(
  input  wire        aclk,
  input  wire        aresetn,

  input  wire [3:0]  s_axi_arid,
  input  wire [31:0] s_axi_araddr,
  input  wire [7:0]  s_axi_arlen,
  input  wire [2:0]  s_axi_arsize,
  input  wire [1:0]  s_axi_arburst,
  input  wire        s_axi_arlock,
  input  wire [3:0]  s_axi_arcache,
  input  wire [2:0]  s_axi_arprot,
  input  wire [3:0]  s_axi_arqos,
  input  wire        s_axi_aruser,
  input  wire        s_axi_arvalid,
  output wire        s_axi_arready,

  output wire [3:0]  s_axi_rid,
  output wire [63:0] s_axi_rdata,
  output wire [1:0]  s_axi_rresp,
  output wire        s_axi_rlast,
  output wire        s_axi_ruser,
  output wire        s_axi_rvalid,
  input  wire        s_axi_rready,

  input  wire [3:0]  s_axi_awid,
  input  wire [31:0] s_axi_awaddr,
  input  wire [7:0]  s_axi_awlen,
  input  wire [2:0]  s_axi_awsize,
  input  wire [1:0]  s_axi_awburst,
  input  wire        s_axi_awlock,
  input  wire [3:0]  s_axi_awcache,
  input  wire [2:0]  s_axi_awprot,
  input  wire [3:0]  s_axi_awqos,
  input  wire        s_axi_awuser,
  input  wire        s_axi_awvalid,
  output wire        s_axi_awready,

  input  wire [63:0] s_axi_wdata,
  input  wire [7:0]  s_axi_wstrb,
  input  wire        s_axi_wlast,
  input  wire        s_axi_wuser,
  input  wire        s_axi_wvalid,
  output wire        s_axi_wready,

  output wire [3:0]  s_axi_bid,
  output wire [1:0]  s_axi_bresp,
  output wire        s_axi_buser,
  output wire        s_axi_bvalid,
  input  wire        s_axi_bready,

  output reg         perf_done,
  output reg  [31:0] perf_signature,
  output reg  [31:0] perf_cycle_lo,
  output reg  [31:0] perf_cycle_hi,
  output reg  [31:0] perf_instret_lo,
  output reg  [31:0] perf_instret_hi,
  output reg  [31:0] perf_checksum
);

  localparam integer MEM_WORDS = 2 * 1024 * 1024; // 16 MiB
  localparam [31:0]  DRAM_BASE = 32'h8000_0000;
  localparam [31:0]  PERF_BASE = 32'h4000_f000;

  reg [63:0] mem [0:MEM_WORDS-1];
  integer i;

`ifdef DRAM_INIT
  // The compile-time image is the default.  +dram_init=<file> overrides it at
  // runtime, which lets diagnostics and alternative benchmarks reuse one
  // simulator build without changing the timing of the platform.
  reg [1023:0] dram_init_file;
  initial begin
    if (!$value$plusargs("dram_init=%s", dram_init_file))
      dram_init_file = `DRAM_INIT;
    $readmemh(dram_init_file, mem);
  end
`endif

  function automatic is_perf_addr(input [31:0] a);
    is_perf_addr = (a[31:8] == 24'h4000f0);
  endfunction

  function automatic [20:0] dram_index(input [31:0] a);
    dram_index = ((a - DRAM_BASE) >> 3) & (MEM_WORDS - 1);
  endfunction

  // AXI4 burst address for one beat.  NutShell's caches issue WRAP bursts for
  // critical-word-first refills; the wrapping boundary is the total burst
  // size, not the cache line size stored by the slave.
  function automatic [31:0] burst_addr(
    input [31:0] base,
    input [7:0]  beat,
    input [7:0]  len,
    input [2:0]  size,
    input [1:0]  burst
  );
    reg [31:0] beat_bytes;
    reg [31:0] total_bytes;
    reg [31:0] wrap_mask;
    begin
      beat_bytes  = 32'd1 << size;
      total_bytes = (32'd1 << size) * (len + 32'd1);
      wrap_mask   = total_bytes - 32'd1;
      if (burst == 2'b00) begin
        burst_addr = base;
      end else if (burst == 2'b10) begin
        burst_addr = (base & ~wrap_mask) |
                     ((base + beat * beat_bytes) & wrap_mask);
      end else begin
        burst_addr = base + beat * beat_bytes;
      end
    end
  endfunction

  // ---------------------------------------------------------------------------
  // Read channel
  // ---------------------------------------------------------------------------
  reg        rd_active;
  reg        rd_is_perf;
  reg [31:0] rd_addr;
  reg [7:0]  rd_len;
  reg [2:0]  rd_size;
  reg [1:0]  rd_burst;
  reg [7:0]  rd_cnt;
  reg [3:0]  rd_id;

  assign s_axi_arready = ~rd_active;
  assign s_axi_rvalid  = rd_active;
  assign s_axi_rlast   = rd_active && (rd_cnt == rd_len);
  assign s_axi_rid     = rd_id;
  assign s_axi_rresp   = 2'b00;
  assign s_axi_ruser   = 1'b0;
  assign s_axi_rdata   = rd_is_perf ? 64'h0 :
                         mem[dram_index(burst_addr(
                           rd_addr, rd_cnt, rd_len, rd_size, rd_burst))];

  always @(posedge aclk or negedge aresetn) begin
    if (!aresetn) begin
      rd_active  <= 1'b0;
      rd_is_perf <= 1'b0;
      rd_addr    <= 32'b0;
      rd_len     <= 8'b0;
      rd_size    <= 3'b0;
      rd_burst   <= 2'b0;
      rd_cnt     <= 8'b0;
      rd_id      <= 4'b0;
    end else begin
      if (!rd_active && s_axi_arvalid) begin
        rd_active  <= 1'b1;
        rd_is_perf <= is_perf_addr(s_axi_araddr);
        rd_addr    <= s_axi_araddr;
        rd_len     <= s_axi_arlen;
        rd_size    <= s_axi_arsize;
        rd_burst   <= s_axi_arburst;
        rd_cnt     <= 8'b0;
        rd_id      <= s_axi_arid;
      end else if (rd_active && s_axi_rready) begin
        if (rd_cnt == rd_len) begin
          rd_active <= 1'b0;
          rd_cnt    <= 8'b0;
        end else begin
          rd_cnt <= rd_cnt + 8'b1;
        end
      end
    end
  end

  // ---------------------------------------------------------------------------
  // Write channel and performance registers
  // ---------------------------------------------------------------------------
  reg        aw_latched;
  reg [31:0] aw_addr;
  reg [7:0]  aw_len;
  reg [2:0]  aw_size;
  reg [1:0]  aw_burst;
  reg [3:0]  aw_id;
  reg [7:0]  wr_cnt;
  reg        bvalid;
  reg [3:0]  bid;

  wire        w_accept = aw_latched || s_axi_awvalid;
  wire [31:0] w_addr   = burst_addr(
    aw_latched ? aw_addr : s_axi_awaddr,
    wr_cnt,
    aw_latched ? aw_len : s_axi_awlen,
    aw_latched ? aw_size : s_axi_awsize,
    aw_latched ? aw_burst : s_axi_awburst);

  assign s_axi_awready = ~aw_latched && ~bvalid;
  assign s_axi_wready  = w_accept && ~bvalid;
  assign s_axi_bvalid  = bvalid;
  assign s_axi_bid     = bid;
  assign s_axi_bresp   = 2'b00;
  assign s_axi_buser   = 1'b0;

  always @(posedge aclk or negedge aresetn) begin
    if (!aresetn) begin
      aw_latched  <= 1'b0;
      aw_addr     <= 32'b0;
      aw_len      <= 8'b0;
      aw_size     <= 3'b0;
      aw_burst    <= 2'b0;
      aw_id       <= 4'b0;
      wr_cnt      <= 8'b0;
      bvalid      <= 1'b0;
      bid         <= 4'b0;
      perf_done   <= 1'b0;
      perf_signature  <= 32'b0;
      perf_cycle_lo   <= 32'b0;
      perf_cycle_hi   <= 32'b0;
      perf_instret_lo <= 32'b0;
      perf_instret_hi <= 32'b0;
      perf_checksum   <= 32'b0;
    end else begin
      if (s_axi_awvalid && s_axi_awready) begin
        aw_latched <= 1'b1;
        aw_addr    <= s_axi_awaddr;
        aw_len     <= s_axi_awlen;
        aw_size    <= s_axi_awsize;
        aw_burst   <= s_axi_awburst;
        aw_id      <= s_axi_awid;
        wr_cnt     <= 8'b0;
        bid        <= s_axi_awid;
      end

      if (s_axi_wvalid && s_axi_wready) begin
        if (!is_perf_addr(w_addr)) begin
          for (i = 0; i < 8; i = i + 1) begin
            if (s_axi_wstrb[i]) begin
              mem[dram_index(w_addr)][i*8 +: 8] <= s_axi_wdata[i*8 +: 8];
            end
          end
        end else if (s_axi_wstrb[3:0] != 4'b0) begin
          case (w_addr[7:0])
            8'h00: perf_signature  <= s_axi_wdata[31:0];
            8'h08: perf_cycle_lo   <= s_axi_wdata[31:0];
            8'h10: perf_cycle_hi   <= s_axi_wdata[31:0];
            8'h18: perf_instret_lo <= s_axi_wdata[31:0];
            8'h20: perf_instret_hi <= s_axi_wdata[31:0];
            8'h28: perf_checksum   <= s_axi_wdata[31:0];
            8'h30: perf_done       <= 1'b1;
            8'h38: begin
              $write("%c", s_axi_wdata[7:0]);
              if (s_axi_wdata[7:0] == 8'h0a) $fflush();
            end
            default: ;
          endcase
        end

        if (s_axi_wlast) begin
          aw_latched <= 1'b0;
          wr_cnt     <= 8'b0;
          bvalid     <= 1'b1;
        end else begin
          wr_cnt <= wr_cnt + 8'b1;
        end
      end

      if (bvalid && s_axi_bready) begin
        bvalid <= 1'b0;
      end
    end
  end

endmodule
