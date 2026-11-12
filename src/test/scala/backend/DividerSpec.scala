package minixiangshan.backend

import chisel3._
import chiseltest._
import minixiangshan.TestInputs.zero
import minixiangshan.backend.execute.Divider
import minixiangshan.backend.decode.DivOp
import minixiangshan.config._
import org.scalatest.flatspec.AnyFlatSpec

class DividerSpec extends AnyFlatSpec with ChiselScalatestTester {
  it should "sign extend every RV64 word division result including unsigned and zero divisors" in {
    implicit val p: Parameters = new Parameters(Map.empty)
    test(new Divider) { dut =>
      zero(dut.io.in.bits)
      zero(dut.io.redirectInfo)
      dut.io.in.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.clock.step(2)
      val mask = (BigInt(1) << 64) - 1
      def run(op: UInt, a: BigInt, b: BigInt, expected: BigInt): Unit = {
        dut.io.in.bits.rs1Data.poke((a & mask).U)
        dut.io.in.bits.rs2Data.poke((b & mask).U)
        dut.io.in.bits.uop.ctrl.divOp.poke(op)
        dut.io.in.valid.poke(true.B)
        dut.io.in.ready.expect(true.B)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)
        var cycles = 0
        while (!dut.io.out.valid.peek().litToBoolean && cycles < 70) {
          dut.clock.step()
          cycles += 1
        }
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.data.expect((expected & mask).U)
        dut.clock.step()
      }
      run(DivOp.divuw, BigInt("ffffffff", 16), 1, -1)
      run(DivOp.remuw, BigInt("80000000", 16), BigInt("ffffffff", 16), -BigInt("80000000", 16))
      run(DivOp.divuw, 123, 0, -1)
      run(DivOp.remuw, BigInt("87654321", 16), 0, BigInt("ffffffff87654321", 16))
      run(DivOp.divw, -BigInt("80000000", 16), -1, -BigInt("80000000", 16))
      run(DivOp.remw, -37, 5, -2)
      run(DivOp.divu, BigInt("fedcba9876543210", 16), 17, BigInt("fedcba9876543210", 16) / 17)
      run(DivOp.div, -1234567890123L, 17, BigInt(-1234567890123L) / 17)
    }
  }
}
