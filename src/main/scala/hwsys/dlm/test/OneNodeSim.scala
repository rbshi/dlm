package hwsys.dlm.test

import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.Axi4
import spinal.lib.master
import hwsys.dlm._
import hwsys.sim._
import hwsys.util.Helpers._

class OneNodeTop(sysConf: SysConfig) extends Component {

  val txnMan = new TxnManCS(sysConf)
  val ltMCh = new LtTop(sysConf)

  val io = new Bundle {
    val axi = master(Axi4(sysConf.axiConf))
    val cmdAxi = master(Axi4(sysConf.axiConf))
    val start = in Bool()
    val txnNumTotal = in UInt(32 bits)
    val cmdAddrOffs = in UInt(32 bits) //NOTE: unit size 64B
  }

  io.start <> txnMan.io.start
  io.txnNumTotal <> txnMan.io.txnNumTotal
  io.cmdAddrOffs <> txnMan.io.cmdAddrOffs

  io.axi <> txnMan.io.axi
  io.cmdAxi <> txnMan.io.cmdAxi
  txnMan.io.lkReqLoc <> ltMCh.io.lt(0).lkReq
  txnMan.io.lkRespLoc <> ltMCh.io.lt(0).lkResp

  txnMan.io.nodeId := 0
  txnMan.io.txnManId := 0

  ltMCh.io.nodeId := 0


  // tieOff
  Seq(txnMan.io.lkReqRmt, txnMan.io.wrRmt, txnMan.io.lkRespRmt, txnMan.io.rdRmt).foreach(_.tieOff(true))

}


object OneNodeSim {
  def main(args: Array[String]): Unit = {

    implicit val sysConf = new SysConfig {
      override val nNode: Int = 1
      override val nCh: Int = 4
      override val nLock: Int = 4096 * nCh
      override val nTxnMan: Int = 1
      override val nLtPart: Int = 1
    }

    SimConfig.withWave.compile {
      val dut = new OneNodeTop(sysConf)
      dut.txnMan.io.simPublic()
      dut
    }.doSim("onenode", 99) { dut =>
      // params
      val txnLen = 32
      val txnCnt = 128
      val txnMaxLen = sysConf.maxTxnLen-1

      dut.clockDomain.forkStimulus(period = 10)

      // data memory
      val axiMem = SimDriver.instAxiMemSim(dut.io.axi, dut.clockDomain, None)

      // cmd memory
      val txnCtx = SimInit.txnEntrySimInt(txnCnt, txnLen, txnMaxLen)((_,_) => 0, (_,_) => 0, _ + _,  (_,_) => 1).toArray
      val cmdAxiMem = SimDriver.instAxiMemSim(dut.io.cmdAxi, dut.clockDomain, Some(txnCtx))

      dut.io.start #= false
      // wait the fifo (empty_ptr) to reset
      dut.clockDomain.waitSampling(2000)

      // config
      dut.io.cmdAddrOffs #= 0
      dut.io.txnNumTotal #= txnCnt

      // start
      dut.io.start #= true
      dut.clockDomain.waitSampling()
      dut.io.start #= false

      // dut.clockDomain.waitSampling(64000)

      dut.clockDomain.waitSamplingWhere(dut.txnMan.io.done.toBoolean)

      println(s"[txnMan] cntTxnCmt: ${dut.txnMan.io.cntTxnCmt.toBigInt}")
      println(s"[txnMan] cntTxnAbt: ${dut.txnMan.io.cntTxnAbt.toBigInt}")
      println(s"[txnMan] cntTxnLd: ${dut.txnMan.io.cntTxnLd.toBigInt}")
      println(s"[txnMan] cntClk: ${dut.txnMan.io.cntClk.toBigInt}")
    }
  }
}
