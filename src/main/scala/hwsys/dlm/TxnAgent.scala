package hwsys.dlm

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import hwsys.util.Helpers._


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
  io.axi.aw.addr := (((reqFork2.tId << reqFork2.wLen) << 6) + (reqFork2.cId << conf.wChSize)).resized
  io.axi.aw.id := 0
  io.axi.aw.len := (U(1)<<reqFork2.wLen) -1
  io.axi.aw.size := log2Up(512/8)
  io.axi.aw.setBurstINCR()

  if(conf.axiConf.useStrb){
    io.axi.w.payload.strb.setAll()
  }

  val nBeat = RegNextWhen((U(1)<<reqFork2.wLen)-1, reqFork2.fire)
  val axiWrVld = RegNextWhen(True, reqFork2.fire, False)
  axiWrVld.clearWhen(io.axi.w.last && io.axi.w.fire)

  io.axi.w.data := io.wrData.payload
  io.axi.w.last := (nBeat === 0)
  when(axiWrVld) {io.axi.w.arbitrationFrom(io.wrData)} otherwise {
    io.wrData.ready.clear()
    io.axi.w.valid.clear()
  }

  // wr resp
  io.axi.b.ready.set()

  // resp
  val isRdRespGrant = ~io.ltResp.lkRelease && ~io.ltResp.lkType && (io.ltResp.respType === LockRespType.grant)
  io.ltResp.conditionFork2(isRdRespGrant, io.lkResp, io.axi.ar)
  // payload assignment
  io.axi.ar.addr := (((io.ltResp.tId << io.ltResp.wLen) << 6) + (io.ltResp.cId << conf.wChSize)).resized
  io.axi.ar.id := 0 // dont care
  io.axi.ar.len := (U(1)<<io.ltResp.wLen) -1
  io.axi.ar.size := log2Up(512/8)
  io.axi.ar.setBurstINCR()
  io.lkResp.payload := io.ltResp.payload

  // rdData: directly bpss the io.axi.r
  io.rdData.translateFrom(io.axi.r)((a, b) => a.assignFromBits(b.data))

}
