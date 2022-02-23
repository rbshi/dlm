package hwsys.dlm.test

import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.Axi4
import spinal.lib.master
import hwsys.dlm._
import hwsys.sim._
import hwsys.util.Helpers._


class TwoNodeArbTop(sysConf: SysConfig) extends Component {

  // node0: with txnMan only
  val n0 = new Area {
    val io = new Bundle {
      val axi = master(Axi4(sysConf.axiConf))
      val cmdAxi = master(Axi4(sysConf.axiConf))
      val start = in Bool()
      val txnNumTotal = in UInt (32 bits)
      val cmdAddrOffs = in UInt (32 bits) //NOTE: unit size 64B
    }
    val txnMan = new TxnManCS(sysConf)
    val ltMCh = new LtTop(sysConf)

    txnMan.io.axi <> io.axi
    txnMan.io.cmdAxi <> io.cmdAxi
    txnMan.io.start <> io.start
    txnMan.io.txnNumTotal <> io.txnNumTotal
    txnMan.io.cmdAddrOffs <> io.cmdAddrOffs

    txnMan.io.txnManId := 0
    txnMan.io.nodeId := 0
    txnMan.io.lkReqLoc <> ltMCh.io.lt(0).lkReq
    txnMan.io.lkRespLoc <> ltMCh.io.lt(0).lkResp

    ltMCh.io.nodeId := 0

    Seq(ltMCh.io.lt(1).lkReq, ltMCh.io.lt(1).lkResp).foreach(_.tieOff(true))
  }

  // node1: with txnAgent only
  val n1 = new Area {
    val io = new Bundle {
      val axi = master(Axi4(sysConf.axiConf))
    }
    val txnAgt = new TxnAgent(sysConf)
    val ltMCh = new LtTop(sysConf)

    txnAgt.io.axi <> io.axi
    txnAgt.io.ltReq <> ltMCh.io.lt(1).lkReq
    txnAgt.io.ltResp <> ltMCh.io.lt(1).lkResp
    ltMCh.io.nodeId := 1

    Seq(ltMCh.io.lt(0).lkReq, ltMCh.io.lt(0).lkResp).foreach(_.tieOff(true))
  }

  // NetArbs
  val sendArb = new SendArbiter(1, sysConf)
  val reqDisp = new ReqDispatcher(1, sysConf)
  val respArb = new RespArbiter(1, sysConf)
  val recvDisp = new RecvDispatcher(1, sysConf)

  // connect
  n0.txnMan.io.lkReqRmt >> sendArb.io.lkReqV(0)
  n0.txnMan.io.wrRmt >> sendArb.io.wrDataV(0)

  reqDisp.io.lkReq >> n1.txnAgt.io.lkReq
  reqDisp.io.wrData >> n1.txnAgt.io.wrData

  n1.txnAgt.io.lkResp >> respArb.io.lkResp
  n1.txnAgt.io.rdData >> respArb.io.rdData

  recvDisp.io.lkRespV(0) >> n0.txnMan.io.lkRespRmt
  recvDisp.io.rdDataV(0) >> n0.txnMan.io.rdRmt

  // TODO: add some latency here
  sendArb.io.sendQ >> reqDisp.io.reqQ
  respArb.io.respQ >> recvDisp.io.recvQ
}


object TwoNodeArbSim {
  def main(args: Array[String]): Unit = {

    implicit val sysConf = new SysConfig {
      override val nNode: Int = 2
      override val nCh: Int = 2
      override val nTxnMan: Int = 1
      override val nLtPart: Int = 1
      override val nLock: Int = 4096 * nLtPart
    }

    SimConfig.withWave.compile {
      val dut = new TwoNodeArbTop(sysConf)
      dut.n0.txnMan.io.simPublic()
      dut
    }.doSim("twonode_with_netarb", 99) { dut =>
      // params
      val txnLen = 32
      val txnCnt = 128
      val txnMaxLen = sysConf.maxTxnLen-1

      dut.clockDomain.forkStimulus(period = 10)

      // cmd memory
      val fNId = (i: Int, j: Int) => 1
      val fCId = (i: Int, j: Int) => 0
      val fTId = (i: Int, j: Int) => i*txnLen + j
      val fLkAttr = (i: Int, j: Int) => 0

      val txnCtx = SimInit.txnEntrySimInt(txnCnt, txnLen, txnMaxLen)(fNId, fCId, fTId, fLkAttr).toArray
      val cmdAxiMem = SimDriver.instAxiMemSim(dut.n0.io.cmdAxi, dut.clockDomain, Some(txnCtx))

      // data memory
      val n0Axi = SimDriver.instAxiMemSim(dut.n0.io.axi, dut.clockDomain, None)
      val n1Axi = SimDriver.instAxiMemSim(dut.n1.io.axi, dut.clockDomain, None)

      dut.n0.io.start #= false
      // wait the fifo (empty_ptr) to reset
      dut.clockDomain.waitSampling(sysConf.nLock/sysConf.nLtPart+1000)

      // config
      dut.n0.io.cmdAddrOffs #= 0
      dut.n0.io.txnNumTotal #= txnCnt

      // start
      dut.n0.io.start #= true
      dut.clockDomain.waitSampling()
      dut.n0.io.start #= false

      dut.clockDomain.waitSamplingWhere(dut.n0.txnMan.io.done.toBoolean)

      println(s"[txnMan] cntTxnCmt: ${dut.n0.txnMan.io.cntTxnCmt.toBigInt}")
      println(s"[txnMan] cntTxnAbt: ${dut.n0.txnMan.io.cntTxnAbt.toBigInt}")
      println(s"[txnMan] cntTxnLd: ${dut.n0.txnMan.io.cntTxnLd.toBigInt}")
      println(s"[txnMan] cntClk: ${dut.n0.txnMan.io.cntClk.toBigInt}")
    }
  }
}