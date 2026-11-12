package minixiangshan.backend.decode

import chisel3._
import chiseltest._
import firrtl.options.TargetDirAnnotation
import org.scalatest.flatspec.AnyFlatSpec

import scala.util.Random

class UpperImmediateHarness(xlen: Int) extends Module {
  val io = IO(new Bundle {
    val inst = Input(UInt(32.W))
    val imm = Output(UInt(xlen.W))
  })
  io.imm := ImmGen(io.inst, ImmType.u, xlen)
}

class ImmGenSpec extends AnyFlatSpec with ChiselScalatestTester {
  for (xlen <- Seq(64)) {
    it should s"generate signed U immediates without zero-width hardware at XLEN=$xlen" in {
      test(new UpperImmediateHarness(xlen)).withAnnotations(
        Seq(TargetDirAnnotation(s"target/immgen-test-$xlen"))) { dut =>
        val rng = new Random(0x55494d4dL)
        val instructions = Seq(0, 0x7ffff037, 0x80000037, 0xfffff037) ++
          Seq.fill(256)(rng.nextInt())
        for (inst <- instructions) {
          dut.io.inst.poke((BigInt(inst) & BigInt("ffffffff", 16)).U)
          val signed = BigInt(inst & 0xfffff000)
          dut.io.imm.expect((signed & ((BigInt(1) << xlen) - 1)).U)
        }
      }
    }
  }
}
