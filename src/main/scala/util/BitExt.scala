package minixiangshan.util

import chisel3._

object BitExt {
  def sign(value: UInt, width: Int): UInt = value.asSInt.pad(width).asUInt
  def zero(value: UInt, width: Int): UInt = value.pad(width)
}
