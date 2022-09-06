package hwsys.dlm

import spinal.core._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axilite.AxiLite4SlaveFactory
import spinal.lib._
import hwsys.coyote._
import hwsys.util.Helpers._


trait SysConfig {

  // sys params
  val nNode : Int
  val nCh : Int
  val nLock : Int
  val nTxnMan : Int

  val nTab: Int = 8 // MAX 8 tables

  // derivative params
  def wNId = log2Up(nNode)
  def wCId = log2Up(nCh)
  def wTId = log2Up(nLock)
  def wTabId: Int = log2Up(nTab)
  def wTxnManId = log2Up(nTxnMan)

  def nTxnAgent = nNode -1

  // CC mode: "NW (no wait)", "BW (bounded wait)", "TSO (timestamp ordering)"
  // def ccProt = "NW"
  // def ccProt = "BW"
  def ccProt = "TSO"

  // txnMan params
  val nTxnCS = 64 // concurrent txn count, limited by axi arid (6 bits)
  val maxTxnLen = 64 // max len of each txn, space of on-chip mem (include the txnHd)

  val wTimeOut = 24

  val wTimeStamp = 26 // say us precision, 64 seconds in total (10+10+6)

  def wMaxTxnLen= log2Up(maxTxnLen)
  def wLkIdx = log2Up(maxTxnLen) // lkIdx in one Txn, for OoO response
  def wTxnId = log2Up(nTxnCS)

  def dTxnMem = nTxnCS * maxTxnLen
  def wTxnMemAddr = log2Up(dTxnMem)

  // LT params
  val nLtPart : Int

  val wLkType = 2
  val wOwnerCnt = 8
  val wWaitCnt = 8
  val wHtBucket = 8
  def wHtTable = 9 // depth 512: one BRAM
  def wLlTable = 9 // depth of LL
  def wLtPart = log2Up(nLtPart)

  def wHtValNW = 1 + wOwnerCnt // 1-bit lock status (ex/sh)
  def wHtValBW = 1 + wOwnerCnt + wLlTable + 1 // 1-bit lock status (ex/sh)

  // FIXME: for sim
  val wChSize = 28 // 256MB of each channel (used as offset with global addressing)


  val wTupLenPow = 3 //len(tuple)=2^wLen; maxLen = 64B << 7 = 8192 B

  // onFly control
  val wMaxTupLen = 2 // 64 << 2

  val axiConf = Axi4Config(
    addressWidth = 64,
    dataWidth = 512,
    idWidth = 6,
    useStrb = true,
    useBurst = true,
    useId = true,
    useLock = false,
    useRegion = false,
    useCache = false,
    useProt = false,
    useQos = false,
    useLen = true
  )

}

case class TxnEntry(conf: SysConfig) extends Bundle {
  val nId = UInt(conf.wNId bits)
  val cId = UInt(conf.wCId bits)
  val tId = UInt(conf.wTId bits)
  val tabId = UInt(conf.wTabId bits)
  val lkType = LkT()
  val wLen = UInt(conf.wTupLenPow bits) // len(tuple)=2^wLen; maxLen = 64B << 7 = 8192 B

  def toLkReq(srcNodeId: UInt, txnManId: UInt, curTxnId: UInt, release: Bool, lkIdx: UInt): LkReq = {
    val lkReq = LkReq(this.conf, false) // process in txnMan, so false
    lkReq.assignSomeByName(this)
    lkReq.snId := srcNodeId
    lkReq.txnManId := txnManId
    lkReq.txnId := curTxnId
    lkReq.lkRelease := release
    lkReq.lkIdx := lkIdx
    lkReq.txnAbt := False
    lkReq.txnTimeOut := False
    lkReq.tsTxn := 0 // FIXME: affect NW/BW cases?
    lkReq
  }
}

case class LkReq(conf: SysConfig, isTIdTrunc: Boolean) extends Bundle {
  val nId = UInt(conf.wNId bits)
  val cId = UInt(conf.wCId bits)
  val tId = if(isTIdTrunc) UInt(conf.wTId - conf.wLtPart bits) else UInt(conf.wTId bits)
  val tabId = UInt(conf.wTabId bits)
  val snId = UInt(conf.wNId bits) // src node (who issue the txn) Id
  val txnManId = UInt(conf.wTxnManId bits)
  val txnId = UInt(conf.wTxnId bits)
  val lkType = LkT()
  val lkRelease = Bool()
  val txnTimeOut = (conf.ccProt=="BW") generate Bool()
  val txnAbt = Bool() // when req wr rlse, if txnAbt, then no data to commit
  val lkIdx = UInt(conf.wLkIdx bits)
  val wLen = UInt(conf.wTupLenPow bits)
  val tsTxn = (conf.ccProt=="TSO") generate UInt(conf.wTimeStamp bits)
}

// TODO: now LkResp bypass all info in LkReq
case class LkResp(conf: SysConfig, isTIdTrunc: Boolean) extends Bundle {
  val nId = UInt(conf.wNId bits)
  val cId = UInt(conf.wCId bits)
  val tId = if(isTIdTrunc) UInt(conf.wTId - conf.wLtPart bits) else UInt(conf.wTId bits)
  val tabId = UInt(conf.wTabId bits)
  val snId = UInt(conf.wNId bits) // src node (who issue the txn) Id
  val txnManId = UInt(conf.wTxnManId bits)
  val txnId = UInt(conf.wTxnId bits)
  val lkType = LkT()
  val lkRelease = Bool()
  val txnAbt = Bool()
  val lkIdx = UInt(conf.wLkIdx bits)
  val wLen = UInt(conf.wTupLenPow bits)
  // TODO: how to reuse the above W/O bundle hierarchy

  val respType = LockRespType()
  val lkWaited = Bool() // if the lkResp is from dequeue, lkWaited = true

  def toLkRlseReq(txnAbt: Bool, lkIdx: UInt, timeOut: Bool): LkReq = {
    val lkReq = LkReq(this.conf, false) // process in txnMan, so false
    lkReq.assignSomeByName(this)
    lkReq.lkRelease.allowOverride := True
    lkReq.lkIdx.allowOverride := lkIdx
    lkReq.txnAbt.allowOverride := txnAbt
    lkReq.txnTimeOut.allowOverride := timeOut
    lkReq
  }

}

object LockTableIO {
  def apply(conf: SysConfig, isTIdTrunc: Boolean): LockTableIO = {
    val ret = new LockTableIO(conf, isTIdTrunc)
    ret
  }
  def apply(conf: SysConfig): LockTableIO = {
    val ret = new LockTableIO(conf, false)
    ret
  }
}

class LockTableIO(conf: SysConfig, isTIdTrunc: Boolean) extends Bundle{
  val lkReq = slave Stream(LkReq(conf, isTIdTrunc))
  val lkResp = master Stream(LkResp(conf, isTIdTrunc))
}

case class RdmaCtrlIO() extends Bundle {
  val en = in Bool()
  val len = in UInt(32 bits)
  val qpn = in UInt(10 bits)
  val flowId = in UInt(4 bits)
}

class NodeIO(implicit sysConf: SysConfig) extends Bundle {
  val axi = Vec(master(Axi4(sysConf.axiConf)), sysConf.nTxnMan + sysConf.nTxnAgent)
  val cmdAxi = Vec(master(Axi4(sysConf.axiConf)), sysConf.nTxnMan)

  val nodeId = in UInt(sysConf.wNId bits)
  val txnNumTotal = in UInt(32 bits)
  val cmdAddrOffs = in Vec(UInt(32 bits), sysConf.nTxnMan) //NOTE: unit size 64B
  val start = in Bool()

  val done = out Vec(Bool(), sysConf.nTxnMan)
  val cntTxnCmt, cntTxnAbt, cntTxnLd = out Vec(UInt(32 bits), sysConf.nTxnMan)
  val cntClk = out Vec(UInt(40 bits), sysConf.nTxnMan)
}

// Node + Q IO
class NodeFlowIO(implicit sysConf: SysConfig) extends NodeIO {
  // network flow IO
  val sendQ = master Stream Bits(512 bits)
  val respQ = master Stream Bits(512 bits)
  val reqQ = slave Stream Bits(512 bits)
  val recvQ = slave Stream Bits(512 bits)
  // network arb status
  val sendStatusVld, recvStatusVld = out Bool()
  val nReq, nWrCmtReq, nRdGetReq, nResp, nWrCmtResp, nRdGetResp = out UInt(4 bits)
}

// Node + RDMA IO
class NodeNetIO(implicit sysConf: SysConfig) extends Bundle{
  val node = new NodeIO()
  val rdma = new RdmaIO()
  val rdmaCtrl = in Vec(RdmaCtrlIO(), 2)

  def regMap(r: AxiLite4SlaveFactory, baseR: Int): Int = {
    implicit val baseReg = baseR
    // in
    // rdmaCtrl MSB <-> LSB: flowId(4b), qpn(24b), len(32b), en(1b)
    r.rwInPort(rdmaCtrl(0).en, r.getAddr(0), 0, "TxnEng: RDMA Mstr en")
    r.rwInPort(rdmaCtrl(0).len, r.getAddr(0), 1, "TxnEng: RDMA Mstr len")
    r.rwInPort(rdmaCtrl(0).qpn, r.getAddr(0), 33, "TxnEng: RDMA Mstr qpn")
    r.rwInPort(rdmaCtrl(0).flowId, r.getAddr(0), 57, "TxnEng: RDMA Mstr flowId")

    r.rwInPort(rdmaCtrl(1).en, r.getAddr(1), 0, "TxnEng: RDMA Slve en")
    r.rwInPort(rdmaCtrl(1).len, r.getAddr(1), 1, "TxnEng: RDMA Slve len")
    r.rwInPort(rdmaCtrl(1).qpn, r.getAddr(1), 33, "TxnEng: RDMA Slve qpn")
    r.rwInPort(rdmaCtrl(1).flowId, r.getAddr(1), 57, "TxnEng: RDMA Slve flowId")

    val rStart = r.rwInPort(node.start,     r.getAddr(2), 0, "TxnEng: start")
    rStart.clearWhen(rStart) // auto clear start sig
    r.rwInPort(node.nodeId,     r.getAddr(3), 0, "TxnEng: nodeId")
    r.rwInPort(node.txnNumTotal, r.getAddr(4), 0, "TxnEng: txnNumTotal")

    var assignOffs = 5
    node.cmdAddrOffs.foreach { e =>
      r.rwInPort(e, r.getAddr(assignOffs), 0, "TxnEng: cmemAddr")
      assignOffs += 1
    }

    // out
    node.cntTxnCmt.foreach { e =>
      r.read(e, r.getAddr(assignOffs), 0, "TxnEng: cntTxnCmt")
      assignOffs += 1
    }
    node.cntTxnAbt.foreach { e =>
      r.read(e, r.getAddr(assignOffs), 0, "TxnEng: cntTxnAbt")
      assignOffs += 1
    }
    node.cntTxnLd.foreach { e =>
      r.read(e, r.getAddr(assignOffs), 0, "TxnEng: cntTxnLd")
      assignOffs += 1
    }
    node.cntClk.foreach { e =>
      r.read(e, r.getAddr(assignOffs), 0, "TxnEng: cntClk")
      assignOffs += 1
    }
    r.read(node.done.asBits, r.getAddr(assignOffs), 0, "TxnEng: done bitVector")
    assignOffs += 1

    assignOffs
  }

}


case class TxnAgentIO(conf: SysConfig) extends Bundle {
  // from net arb
  val lkReq = slave Stream LkReq(conf, isTIdTrunc = false)
  val wrData = slave Stream Bits(512 bits)
  val lkResp = master Stream LkResp(conf, isTIdTrunc = false)
  val rdData = master Stream Bits(512 bits)

  // local data axi
  val axi = master(Axi4(conf.axiConf))

  // lt
  val ltReq = master Stream LkReq(conf, isTIdTrunc = false)
  val ltResp = slave Stream LkResp(conf, isTIdTrunc = false)
}


case class TxnManCSIO(conf: SysConfig) extends Bundle {

  // local/rmt req interface
  val lkReqLoc, lkReqRmt = master Stream LkReq(conf, isTIdTrunc = false)
  val lkRespLoc, lkRespRmt = slave Stream LkResp(conf, isTIdTrunc = false)

  // rd/wr data from/to remote
  val rdRmt = slave Stream Bits(512 bits)
  val wrRmt = master Stream Bits(512 bits)

  // local data axi
  val axi = master(Axi4(conf.axiConf))

  // cmd axi
  val cmdAxi = master(Axi4(conf.axiConf))

  // control signals (wire the input to the top AXIL registers)
  val start = in Bool() //NOTE: hold for 1 cycle

  // txnMan config
  val nodeId = in UInt (conf.wNId bits) // to avoid confusing with lkReq/Resp.nId
  val txnManId = in UInt (conf.wTxnManId bits)
  val txnNumTotal = in UInt (32 bits)
  val cmdAddrOffs = in UInt (32 bits) //NOTE: unit size 64B


  val done = out(Reg(Bool())).init(False)
  val cntTxnCmt, cntTxnAbt, cntTxnLd = out(Reg(UInt(32 bits))).init(0)
  val cntClk = out(Reg(UInt(40 bits))).init(0)

  def setDefault() = {

    // tie-off cmdAxi.write
    cmdAxi.aw.valid.clear()
    cmdAxi.w.valid.clear()
    cmdAxi.aw.addr := 0
    cmdAxi.aw.id := 0
    cmdAxi.aw.len := 0
    cmdAxi.aw.size := log2Up(512 / 8)
    cmdAxi.aw.setBurstINCR()
    cmdAxi.w.last := False
    cmdAxi.w.data.clearAll()
    cmdAxi.b.ready := True
    if (conf.axiConf.useStrb) {
      axi.w.strb.setAll()
      cmdAxi.w.strb.setAll()
    }
  }
}

// Lock types: Read, Write, ReadAndWrite, insertTab ()
object LkT extends SpinalEnum {
  val rd, wr, raw, insTab = newElement()
}



















