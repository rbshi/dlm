package hwsys.dlm

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axilite._
import spinal.lib.bus.amba4.axi._
import hwsys.coyote._
import hwsys.util._
import hwsys.util.Helpers._

object MySpinalConfig extends SpinalConfig(
  targetDirectory = "generated_rtl/",
  defaultConfigForClockDomains = ClockDomainConfig(
    resetKind = SYNC,
    resetActiveLevel = LOW
  )
)

object Gen1T2N {
  implicit val sysConf = new SysConfig {
    override val nNode: Int = 2
    override val nCh: Int = 1
    override val nTxnMan: Int = 1
    override val nLtPart: Int = 4
    override val nLock: Int = 4096 * nLtPart
  }
}


object SysCoyote1T2NGen {
  import Gen1T2N._
  def main(args: Array[String]): Unit = {
    MySpinalConfig.generateVerilog{
      val top = new WrapSys()
      top.renameIO()
      top.setDefinitionName("design_user_wrapper_1t2n")
      top
    }
  }

}
