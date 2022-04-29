package hwsys.dlm

import spinal.core.{UInt, _}
import spinal.lib._
import hwsys.util._

class LtCh(sysConf: SysConfig) extends Component{
  val io = LockTableIO(sysConf)
  val ltAry = Array.fill(sysConf.nLtPart)(new LockTableNW(sysConf))

  // each channel has multiple tables, tuplue pointer is used for insert tab
  val tupPtr = Vec(Reg(UInt(sysConf.wTId bits)).init(0), sysConf.nTab)

  // demux io.lkReq -> lkReqInsTab , lkReqTup
  val lkReqDemux = StreamDemux(io.lkReq, (io.lkReq.lkType===LkT.insTab) ? U(1) | U(0), 2) // p0: normal req; p1: insTab
  val lkReqInsTab = lkReqDemux(1)
  val lkReqTup = lkReqDemux(0)

  // resp of lkReqInsTab
  val lkRespInsTab, lkRespTup = cloneOf(io.lkResp)
  lkRespInsTab.translateFrom(lkReqInsTab)((a, b) => {
    a.assignSomeByName(b)
    a.respType := LockRespType.grant
    a.tId.allowOverride
    a.tId := tupPtr(b.tabId) //
  })

  // increase the tupPtr
  when(lkRespInsTab.fire) {
    tupPtr(lkRespInsTab.payload.tabId) := tupPtr(lkRespInsTab.payload.tabId) + lkReqInsTab.payload.tId
  }

  // use the LSB bits for lkReq dispatching
  val lkReq2Lt = Stream(LkReq(sysConf, true))

  lkReq2Lt.arbitrationFrom(lkReqTup)
  for ((name, elem) <- lkReq2Lt.payload.elements){
    if (name != "tId") elem := io.lkReq.payload.find(name)
  }
  lkReq2Lt.tId := io.lkReq.payload.tId(lkReq2Lt.tId.high downto sysConf.wLtPart).resized

  // demux lock_req to multiple LTs
  val lkReq2LtDemux = StreamDemux(lkReq2Lt, io.lkReq.payload.tId(sysConf.wLtPart-1 downto 0), sysConf.nLtPart)
  (ltAry, lkReq2LtDemux).zipped.foreach(_.io.lkReq <-/< _) // pipelined and avoid the high fanout

  // arb the lkResp
  val lkRespArb = StreamArbiterFactory.roundRobin.build(LkResp(sysConf, true), sysConf.nLtPart)
  (lkRespArb.io.inputs, ltAry).zipped.foreach(_ <-/< _.io.lkResp)


  lkRespTup.arbitrationFrom(lkRespArb.io.output)
  for ((name, elem) <- lkRespTup.payload.elements){
    if (name != "tId") elem := lkRespArb.io.output.payload.find(name)
  }
  lkRespTup.tId := (lkRespArb.io.output.tId ## lkRespArb.io.chosen).asUInt.resized

  io.lkResp << StreamArbiterFactory.roundRobin.on(Seq(lkRespInsTab, lkRespTup))
}

class LtTop(sysConf: SysConfig) extends Component {
  // ltIO number: nTxnMan + (nNode -1)
  val io = new Bundle {
    val nodeId = in UInt(sysConf.wNId bits)
    val lt = Vec(LockTableIO(sysConf), sysConf.nTxnMan + sysConf.nNode -1)
  }

  // ltCh number: nCh
  val ltChAry = Array.fill(sysConf.nCh)(new LtCh(sysConf))
  // crossbar (lkReq/Resp bi-direction)
  StreamCrossbarFactory.on(io.lt.map(_.lkReq), ltChAry.map(_.io.lkReq),  io.lt.map(_.lkReq.cId))((o, i) => o.assignAllByName(i))
  StreamCrossbarFactory.on(ltChAry.map(_.io.lkResp), io.lt.map(_.lkResp), ltChAry.map(_.io.lkResp).map(fLkRespDemux(_)))((o, i) => o.assignAllByName(i))

  // demux the lkResp to nTxnMan txnMan and (nNode - 1) txnAgent
  def fLkRespDemux(lkResp: LkResp): UInt = {
    val demuxSel = UInt(log2Up(sysConf.nTxnMan + sysConf.nNode - 1) bits)
    when(io.nodeId < lkResp.snId) {
      demuxSel := (sysConf.nTxnMan + lkResp.snId - 1).resized
    } otherwise {
      when(io.nodeId > lkResp.snId) {
        demuxSel := (sysConf.nTxnMan + lkResp.snId).resized
      } otherwise {
        demuxSel := lkResp.txnManId.resized
      }
    }
    demuxSel
  }

}

















