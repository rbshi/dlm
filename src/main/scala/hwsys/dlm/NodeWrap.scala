package hwsys.dlm

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.Axi4


class NodeWrap(implicit sysConf: SysConfig) extends Component {

  val io = new Bundle {
    val nodeId = in UInt(sysConf.wNId bits)
    val axi = Vec(master(Axi4(sysConf.axiConf)), sysConf.nTxnMan + sysConf.nNode -1)
    val cmdAxi = Vec(master(Axi4(sysConf.axiConf)), sysConf.nTxnMan)
    val start = in Bool()
    val txnNumTotal = in UInt(32 bits)
    val cmdAddrOffs = in Vec(UInt(32 bits), sysConf.nTxnMan) //NOTE: unit size 64B

    val done = out Vec(Bool(), sysConf.nTxnMan)
    val cntTxnCmt, cntTxnAbt, cntTxnLd = out Vec(UInt(32 bits), sysConf.nTxnMan)
    val cntClk = out Vec(UInt(40 bits), sysConf.nTxnMan)
  }

  val txnManAry = Array.fill(sysConf.nTxnMan)(new TxnManCS(sysConf))
  val ltMCh = new LtTop(sysConf)

  txnManAry.foreach{ i =>
    i.io.nodeId := io.nodeId
    i.io.start := io.start
    i.io.txnNumTotal := io.txnNumTotal
  }

  (txnManAry, io.cmdAxi).zipped.foreach(_.io.cmdAxi <> _)
  (txnManAry, io.cmdAddrOffs).zipped.foreach(_.io.cmdAddrOffs <> _)
  (txnManAry, io.done).zipped.foreach(_.io.done <> _)
  (txnManAry, io.cntTxnCmt).zipped.foreach(_.io.cntTxnCmt <> _)
  (txnManAry, io.cntTxnAbt).zipped.foreach(_.io.cntTxnAbt <> _)
  (txnManAry, io.cntTxnLd).zipped.foreach(_.io.cntTxnLd <> _)
  (txnManAry, io.cntClk).zipped.foreach(_.io.cntClk <> _)

  // txnMan connects to part of io vec
  txnManAry.zipWithIndex.foreach {case (txnMan, idx) =>
    txnMan.io.txnManId <> idx
    txnMan.io.axi <> io.axi(idx)
    txnMan.io.lkReqLoc <> ltMCh.io.lt (idx).lkReq
    txnMan.io.lkRespLoc <> ltMCh.io.lt (idx).lkResp
  }

  ltMCh.io.nodeId := io.nodeId

  // two node wrap
  // TODO: lkReqRmt arbiter with nId
  // val arbFlowAry = Array.fill(sysConf.nNode-1)(new ArbDataFlow(sysConf.nTxnMan))
  val arbFlow = new ArbDataFlow(sysConf.nTxnMan)
  val txnAgent = new TxnAgent(sysConf)

  (arbFlow.sendArb.io.lkReqV, txnManAry).zipped.foreach(_ <> _.io.lkReqRmt)
  (arbFlow.sendArb.io.wrDataV, txnManAry).zipped.foreach(_ <> _.io.wrRmt)
  (arbFlow.recvDisp.io.lkRespV, txnManAry).zipped.foreach(_ <> _.io.lkRespRmt)
  (arbFlow.recvDisp.io.rdDataV, txnManAry).zipped.foreach(_ <> _.io.rdRmt)

  arbFlow.reqDisp.io.wrData <> txnAgent.io.wrData
  arbFlow.reqDisp.io.lkReq <> txnAgent.io.lkReq
  arbFlow.respArb.io.rdData <> txnAgent.io.rdData
  arbFlow.respArb.io.lkResp <> txnAgent.io.lkResp

  txnAgent.io.ltReq <> ltMCh.io.lt(sysConf.nTxnMan).lkReq
  txnAgent.io.ltResp <> ltMCh.io.lt(sysConf.nTxnMan).lkResp
  txnAgent.io.axi <> io.axi(sysConf.nTxnMan)

}















