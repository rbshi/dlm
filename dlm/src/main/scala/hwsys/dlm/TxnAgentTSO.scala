package hwsys.dlm

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import hwsys.util.Helpers._
import spinal.core.Component.push

class TxnAgentTSO (conf: SysConfig) extends Component {

  val io = TxnAgentIO(conf)

  // axi arbiter to demux io.axi
  val axiRdArb = Axi4ReadOnlyArbiter(conf.axiConf, 2)
  val axiWrArb = Axi4WriteOnlyArbiter(conf.axiConf, 2, 0)
  axiRdArb.io.output <> io.axi.toReadOnly()
  axiWrArb.io.output <> io.axi.toWriteOnly()

  // DEMUX the Axi to tsAxi & txnAxi
  val axiConfDemux = Axi4Config(
    addressWidth = 64,
    dataWidth = 512,
    idWidth = 5,
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
  val txnAxi, tsAxi = master(Axi4(axiConfDemux))
  txnAxi.toReadOnly() <> axiRdArb.io.inputs(0)
  tsAxi.toReadOnly() <> axiRdArb.io.inputs(1)
  txnAxi.toWriteOnly() <> axiWrArb.io.inputs(0)
  tsAxi.toWriteOnly() <> axiWrArb.io.inputs(1)

  // NOTE: since packaged lkReq net lane may contain multi lkReq, size is 512/64(lk size)
  val lkReqQ = io.lkReq.queue(8)

  // ports to ltReq, happens in different pipeline, ltReqImme covers ltReqGet & ltReqWrRlseAbt
  val ltReqImme, ltReqRdRlse, ltReqWrRlseCmt = cloneOf(lkReqQ)
  // arb the ports to io.ltReq
  io.ltReq << StreamArbiterFactory.roundRobin.onArgs(ltReqImme, ltReqRdRlse, ltReqWrRlseCmt)

  // ports to lkResp
  val lkRespAbt, lkRespCmt = cloneOf(io.lkResp)
  io.lkResp << StreamArbiterFactory.roundRobin.onArgs(lkRespAbt, lkRespCmt)

  // demux lkReq
  val isLkReqImme = ~lkReqQ.lkRelease || (lkReqQ.lkRelease && lkReqQ.isWr && lkReqQ.txnAbt)
  val lkReqQDmx = StreamDemux(lkReqQ, isLkReqImme.asUInt, 2)
  lkReqQDmx(0) >> // tsAxi.aw & txnAxi.aw
  lkReqQDmx(1) >> ltReqImme

  // FIFOs in the pipeline (all the FIFOs store the lkResp type)
  // push the ltResp; pop and 1. tsAxi.ar if granted OR 2. lkResp if abort
  val ltRespFifo = StreamFifo(LkResp(conf, isTIdTrunc = false), 16)

  // push the half-granted lkResp; pop 1. LkResp (Grant/Abort) AND 2. txnAxi.ar
  val tsAxiRdFifo = StreamFifo(LkResp(conf, isTIdTrunc = false), 16)

  // push the granted Rd LkResp OR WrCmt LkReq (MUX); pop tsAxi.aw,w; WrData via txnAxi.aw,w if WrCmt
  val tsAxiWrFifo = StreamFifo(LkResp(conf, isTIdTrunc = false), 16)

  // push the granted Rd LkResp OR WrCmt LkReq poped out from tsAxiWrFifo; pop LtReq.rlse if is Rd LkResp
  val tsAxiBFifo = StreamFifo(LkResp(conf, isTIdTrunc = false), 16)





















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
  val isRdRespGrant = ~io.ltResp.lkRelease && (io.ltResp.lkType===LkT.rd || io.ltResp.lkType===LkT.raw) && (io.ltResp.respType === LockRespType.grant)
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

