package hwsys.dlm

import spinal.core.{UInt, _}
import spinal.lib._
import hwsys.util._

class LtCh(sysConf: SysConfig) extends Component{
  val io = LockTableIO(sysConf)
  val ltAry = Array.fill(sysConf.nLtPart)(new LockTable(sysConf))

  // use the LSB bits for lkReq dispatching
  val lkReq2Lt = Stream(LkReq(sysConf, true))

  lkReq2Lt.arbitrationFrom(io.lkReq)
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

  io.lkResp.arbitrationFrom(lkRespArb.io.output)
  for ((name, elem) <- io.lkResp.payload.elements){
    if (name != "tId") elem := lkRespArb.io.output.payload.find(name)
  }
  io.lkResp.tId := (lkRespArb.io.output.tId ## lkRespArb.io.chosen).asUInt.resized
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

















