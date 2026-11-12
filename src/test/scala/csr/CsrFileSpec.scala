package minixiangshan.csr

import chisel3._
import chiseltest._
import minixiangshan.TestInputs.zero
import minixiangshan.config._
import org.scalatest.flatspec.AnyFlatSpec

class CsrFileSpec extends AnyFlatSpec with ChiselScalatestTester {
  it should "preserve RV64 CSR high bits and enforce RV64 satp and counter layout" in {
    implicit val p: Parameters = new Parameters(Map.empty)
    test(new CsrFile) { dut =>
      zero(dut.io.wReq)
      zero(dut.io.rReq)
      zero(dut.io.trapReq)
      dut.io.irqBus.poke(0.U)
      dut.io.commitCount.poke(0.U)
      dut.io.lrValidSet.poke(false.B)
      dut.io.lrValidClear.poke(false.B)
      dut.clock.step(2)
      def read(addr: Int, value: BigInt): Unit = {
        dut.io.rReq.addr.poke(addr.U)
        dut.io.rResp.data.expect(value.U)
      }
      def write(addr: Int, value: BigInt): Unit = {
        dut.io.wReq.wen.poke(true.B)
        dut.io.wReq.addr.poke(addr.U)
        dut.io.wReq.data.poke(value.U)
        dut.clock.step()
        dut.io.wReq.wen.poke(false.B)
      }
      read(0x301, BigInt("8000000000141100", 16))
      read(0x300, BigInt("a00000000", 16))
      read(0x100, BigInt("200000000", 16))
      val high = BigInt("ffffffff80001237", 16)
      for (addr <- Seq(0x341, 0x141)) {
        write(addr, high)
        read(addr, high & ~BigInt(3))
      }
      for (addr <- Seq(0x340, 0x140, 0x305, 0x105, 0x343, 0x143)) {
        write(addr, high)
        read(addr, high)
      }
      val satp = BigInt("8abcd123456789ab", 16)
      write(0x180, satp)
      read(0x180, satp)
      dut.difftest.get.satp.expect(satp.U)
      write(0x180, BigInt("9000000000000000", 16))
      read(0x180, satp)
      write(0x180, 0)
      read(0x180, 0)
      val count = BigInt("1234567887654321", 16)
      write(0xb00, count)
      read(0xb00, count)
      write(0xb02, count)
      read(0xc02, count)
      dut.io.commitCount.poke(3.U)
      dut.clock.step()
      read(0xb02, count + 3)
      dut.io.commitCount.poke(0.U)
      dut.io.trapReq.valid.poke(true.B)
      dut.io.trapReq.isInterrupt.poke(true.B)
      dut.io.trapReq.cause.poke(7.U)
      dut.io.trapReq.epc.poke((high & ~BigInt(3)).U)
      dut.io.trapReq.priv.poke(3.U)
      dut.clock.step()
      dut.io.trapReq.valid.poke(false.B)
      dut.clock.step()
      read(0x342, BigInt("8000000000000007", 16))
      read(0x341, high & ~BigInt(3))
      dut.difftest.get.mcause.expect("h8000000000000007".U)
    }
  }
}
