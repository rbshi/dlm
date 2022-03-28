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

// TxnMan per node:Node:Channel:Partition per channel
object Gen1T2N1C8P {
  implicit val sysConf = new SysConfig {
    override val nNode: Int = 2
    override val nCh: Int = 1
    override val nTxnMan: Int = 1
    override val nLtPart: Int = 8
    override val nLock: Int = (((1<<10)<<10)<<8)>>6
  }
}

object Gen1T2N4C8P {
  implicit val sysConf = new SysConfig {
    override val nNode: Int = 2
    override val nCh: Int = 4
    override val nTxnMan: Int = 1
    override val nLtPart: Int = 8
    override val nLock: Int = (((1<<10)<<10)<<8)>>6
  }
}

object Gen1T2N8C8P {
  implicit val sysConf = new SysConfig {
    override val nNode: Int = 2
    override val nCh: Int = 8
    override val nTxnMan: Int = 1
    override val nLtPart: Int = 8
    override val nLock: Int = (((1<<10)<<10)<<8)>>6
  }
}

object Gen2T2N8C8P {
  implicit val sysConf = new SysConfig {
    override val nNode: Int = 2
    override val nCh: Int = 8
    override val nTxnMan: Int = 2
    override val nLtPart: Int = 8
    override val nLock: Int = (((1<<10)<<10)<<8)>>6
  }
}



object SysCoyote1T2N1C8PGen {
  import Gen1T2N1C8P._
  def main(args: Array[String]): Unit = {
    MySpinalConfig.generateVerilog{
      val top = new WrapSys()
      top.renameIO()
      top.setDefinitionName("design_user_wrapper_1t2n1c8p")
      top
    }
  }
}


object SysCoyote1T2N4C8PGen {
  import Gen1T2N4C8P._
  def main(args: Array[String]): Unit = {
    MySpinalConfig.generateVerilog{
      val top = new WrapSys()
      top.renameIO()
      top.setDefinitionName("design_user_wrapper_1t2n4c8p")
      top
    }
  }
}


object SysCoyote2T2N8C8PGen {
  import Gen2T2N8C8P._
  def main(args: Array[String]): Unit = {
    MySpinalConfig.generateVerilog{
      val top = new WrapSys()
      top.renameIO()
      top.setDefinitionName("design_user_wrapper_2t2n8c8p")
      top
    }
  }
}

object SysCoyote1T2N8C8PGen {
  import Gen1T2N8C8P._
  def main(args: Array[String]): Unit = {
    MySpinalConfig.generateVerilog{
      val top = new WrapSys()
      top.renameIO()
      top.setDefinitionName("design_user_wrapper_1t2n8c8p")
      top
    }
  }
}