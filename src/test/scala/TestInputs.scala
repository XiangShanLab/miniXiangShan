package minixiangshan

import chisel3._
import chiseltest._

object TestInputs {
  def zero(data: Data): Unit = data match {
    case b: Bool => b.poke(false.B)
    case u: UInt => u.poke(0.U)
    case v: Vec[_] => v.foreach(zero)
    case r: Record => r.elements.values.foreach(zero)
    case other => throw new IllegalArgumentException(s"unsupported test input: $other")
  }
}
