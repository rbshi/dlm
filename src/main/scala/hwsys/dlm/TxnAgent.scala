package hwsys.dlm

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import hwsys.util.Helpers._
import spinal.core.Component.push

class TxnAgent(conf: SysConfig) extends Component {

  val io = TxnAgentIO(conf)
  // NOTE: since packaged lkReq net lane may contain multi lkReq, size is 512/64(lk size)
  val lkReqQ = io.lkReq.queue(8)

  // stream FIFO to tmp store the wrRlse req (later issue after get axi.b)
  val lkReqRlseWrFifo = StreamFifo(LkReq(conf, false), 8)

  // demux the lkReqQ (if wrRlse, to Fifo, else to ltReq)
  val isWrReqRlse = lkReqQ.lkRelease && lkReqQ.lkType && ~lkReqQ.txnAbt // NOTE: not abt
  val lkReqQFork, lkReqBpss = cloneOf(lkReqQ)

  val lkReqQDmx = StreamDemux(lkReqQ, isWrReqRlse.asUInt, 2)
  lkReqQDmx(0) >> lkReqBpss
  lkReqQDmx(1) >> lkReqQFork // fork to both lkReqRlseWrFifo and axi.aw

  // arb the lkReq from lkReqBpss / lkReqRlseWrFifo
  io.ltReq << StreamArbiterFactory.roundRobin.onArgs(lkReqBpss, lkReqRlseWrFifo.io.pop.continueWithToken(io.axi.b.fire, 8))

  val (reqFork1, reqFork2) = StreamFork2(lkReqQFork) // asynchronous
  lkReqRlseWrFifo.io.push << reqFork1
  io.axi.aw.arbitrationFrom(reqFork2)

  // default axi.aw, axi.w
  // io.axi.aw.addr := (((reqFork2.tId << reqFork2.wLen) << 6) + (reqFork2.cId << conf.wChSize)).resized
  io.axi.aw.addr := ((reqFork2.tId << 6) + (reqFork2.cId << conf.wChSize)).resized
  io.axi.aw.id := 0
  io.axi.aw.len := (U(1)<<reqFork2.wLen) -1
  io.axi.aw.size := log2Up(512/8)
  io.axi.aw.setBurstINCR()

  if(conf.axiConf.useStrb){
    io.axi.w.payload.strb.setAll()
  }

  // axi.aw / axi.w is individually processed (nBeat should be fifo)
  val nBeatQ = StreamFifoLowLatency(cloneOf(io.lkReq.wLen), 8)
  nBeatQ.io.push.payload := ((U(1)<<reqFork2.wLen)-1).resized
  nBeatQ.io.push.valid := reqFork2.fire
  nBeatQ.io.pop.ready := io.axi.w.last && io.axi.w.fire

  val cntBeat = Counter(io.lkReq.wLen.getWidth bits, io.axi.w.fire)
  when(io.axi.w.last && io.axi.w.fire) (cntBeat.clear())

  val wrDataQ = cloneOf(io.wrData)
  wrDataQ << io.wrData.queue(8)
  val wrDataQCtrl = wrDataQ.continueWithToken(reqFork2.fire, io.axi.w.last && io.axi.w.fire, 8)
  io.axi.w.data := wrDataQCtrl.payload
  io.axi.w.last := (cntBeat === nBeatQ.io.pop.payload)
  io.axi.w.arbitrationFrom(wrDataQCtrl)

  // wr resp
  io.axi.b.ready.set()

  // resp
  val isRdRespGrant = ~io.ltResp.lkRelease && ~io.ltResp.lkType && (io.ltResp.respType === LockRespType.grant)
  io.ltResp.conditionFork2(isRdRespGrant, io.lkResp, io.axi.ar)
  // payload assignment
  // io.axi.ar.addr := (((io.ltResp.tId << io.ltResp.wLen) << 6) + (io.ltResp.cId << conf.wChSize)).resized
  io.axi.ar.addr := ((io.ltResp.tId << 6) + (io.ltResp.cId << conf.wChSize)).resized
  io.axi.ar.id := 0 // dont care
  io.axi.ar.len := (U(1)<<io.ltResp.wLen) -1
  io.axi.ar.size := log2Up(512/8)
  io.axi.ar.setBurstINCR()
  io.lkResp.payload := io.ltResp.payload

  // rdData: directly bpss the io.axi.r
  io.rdData.translateFrom(io.axi.r)((a, b) => a.assignFromBits(b.data))

}
