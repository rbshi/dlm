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

    // network
    val sendQ = master Stream Bits(512 bits)
    val respQ = master Stream Bits(512 bits)
    val reqQ = slave Stream Bits(512 bits)
    val recvQ = slave Stream Bits(512 bits)
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
  // val arbFlow = new ArbDataFlow(sysConf.nTxnMan)
  val txnAgent = new TxnAgent(sysConf)
  val sendArb = new SendArbiter(sysConf.nTxnMan)
  val recvDisp = new RecvDispatcher(sysConf.nTxnMan)
  val reqDisp = new ReqDispatcher(sysConf.nTxnMan)
  val respArb = new RespArbiter(sysConf.nTxnMan)

  sendArb.io.sendQ <> io.sendQ
  recvDisp.io.recvQ <> io.recvQ
  reqDisp.io.reqQ <> io.reqQ
  respArb.io.respQ <> io.respQ

  (sendArb.io.lkReqV, txnManAry).zipped.foreach(_ <> _.io.lkReqRmt)
  (sendArb.io.wrDataV, txnManAry).zipped.foreach(_ <> _.io.wrRmt)
  (recvDisp.io.lkRespV, txnManAry).zipped.foreach(_ <> _.io.lkRespRmt)
  (recvDisp.io.rdDataV, txnManAry).zipped.foreach(_ <> _.io.rdRmt)

  reqDisp.io.wrData <> txnAgent.io.wrData
  reqDisp.io.lkReq <> txnAgent.io.lkReq
  respArb.io.rdData <> txnAgent.io.rdData
  respArb.io.lkResp <> txnAgent.io.lkResp

  txnAgent.io.ltReq <> ltMCh.io.lt(sysConf.nTxnMan).lkReq
  txnAgent.io.ltResp <> ltMCh.io.lt(sysConf.nTxnMan).lkResp
  txnAgent.io.axi <> io.axi(sysConf.nTxnMan)

}















