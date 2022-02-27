package hwsys.util

import spinal.core._
import spinal.lib._

case class CntIncDec(bitCnt: BitCount, incFlag: Bool, decFlag: Bool) {

  val cnt = Reg(UInt(bitCnt)).init(0)
  val willOverflow = (cnt === (1<<bitCnt.value) -1) && incFlag
  val willUnderflow = (cnt === 0) && decFlag
  val willClear = False.allowOverride

  def clearAll(): Unit = willClear := True

  switch((incFlag, decFlag)) {
    is(True, False) (cnt := cnt + 1)
    is(False, True) (cnt := cnt -1)
    default ()
  }

  when(willClear) (cnt.clearAll())

}

case class CntDynmicBound(upBoundEx: UInt, incFlag: Bool, decFlag: Bool = False) {

  val cnt = Reg(UInt(upBoundEx.getWidth bits)).init(0)
  val willOverflow = (cnt === upBoundEx -1) && incFlag
  val willUnderflow = (cnt === 0) && decFlag
  val willClear = False.allowOverride

  def clearAll(): Unit = willClear := True

  switch((incFlag, decFlag)) {
    is(True, False) (cnt := cnt + 1)
    is(False, True) (cnt := cnt -1)
    default ()
  }

  when(willClear || willOverflow) (cnt.clearAll())

}

case class AccumIncDec(bitCnt: BitCount, incFlag: Bool, decFlag: Bool, incVal: UInt, decVal: UInt) {

  val accum = Reg(UInt(bitCnt)).init(0)
  val willClear = False.allowOverride

  def clearAll(): Unit = willClear := True

  switch((incFlag, decFlag)) {
    is(True, True) (accum := accum + incVal - decVal)
    is(True, False) (accum := accum + incVal)
    is(False, True) (accum := accum - decVal)
    default ()
  }

  when(willClear) (accum.clearAll())
}
