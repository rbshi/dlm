package hwsys.dlm

import spinal.core.{log2Up, _}
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import hwsys.util.Helpers._
import hwsys.util._
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
  val ltReqImme, ltReqRlsePass2, ltReqRlsePass3 = cloneOf(lkReqQ) // ltReqRlse happens after change the memory TS
  io.ltReq << StreamArbiterFactory.roundRobin.onArgs(ltReqImme, ltReqRlsePass2, ltReqRlsePass3)

  // paths to lkResp
  val lkRespPass1, lkRespPass2 = cloneOf(io.lkResp)
  io.lkResp << StreamArbiterFactory.roundRobin.onArgs(lkRespPass1, lkRespPass2)

  // demux lkReq
  val isLkReqImme = ~lkReqQ.lkRelease || (lkReqQ.lkRelease && lkReqQ.isWr && lkReqQ.txnAbt)
  val lkWrCmtRlse = cloneOf(lkReqQ)
  // Vec(lkWrCmtRlse, ltReqImme) = StreamDemux(lkReqQ, isLkReqImme.asUInt, 2)
  val lkReqQDmx = StreamDemux(lkReqQ, isLkReqImme.asUInt, 2)
  lkReqQDmx(0) <> lkWrCmtRlse
  lkReqQDmx(1) <> ltReqImme

  // demux lkResp
  val ltRespAbt, ltRespGrant = cloneOf(io.ltResp)
  // Vec(ltRespAbt, ltRespGrant) = StreamDemux(io.lkResp, io.lkResp.isGrant.asUInt, 2)
  val lkRespDmx = StreamDemux(io.lkResp, io.lkResp.isGrant.asUInt, 2)
  lkRespDmx(0) <> ltRespAbt
  lkRespDmx(1) <> ltRespGrant

  // if ltResp is abt, directly to io.lkResp
  ltRespAbt >> lkRespPass1

  // FIFOs in the pipeline (all the FIFOs store the lkResp type)
  // push the granted ltResp
  val lkRespFifoS1 = StreamFifo(LkResp(conf, isTIdTrunc = false), 16)

  val lkRespFifoS2 = StreamFifo(LkResp(conf, isTIdTrunc = false), 16)


  // Event: io.ltResp (only lkReqGet exists, no resp for lkRlse) >> ltRespFifo
  val (ltRespGrantFork1, ltRespGrantFork2) = StreamFork2(ltRespGrant)
  ltRespGrantFork1 >> lkRespFifoS1.io.push
  tsAxi.ar.addr := ((ltRespGrantFork2.tId << 6) + (ltRespGrantFork2.cId << conf.wChSize)).resized
  tsAxi.ar.id := 0
  tsAxi.ar.len := 0
  tsAxi.ar.size := log2Up(conf.wTimeStamp * 2 / 8) // read both Rd/Wr TS
  tsAxi.ar.setBurstINCR()
  tsAxi.ar.arbitrationFrom(ltRespGrantFork2)

  tsAxi.r.ready := lkRespFifoS2.io.availability > 0 // FIXME: all interfaces are ready

  // process the tsAxi.r
  val tsRdGrant = tsAxi.r.data(conf.wTimeStamp*2-1 downto conf.wTimeStamp).asUInt <= lkRespFifoS1.io.pop.tsTxn
  val tsWrGrant = (tsAxi.r.data(conf.wTimeStamp*2-1 downto conf.wTimeStamp).asUInt <= lkRespFifoS1.io.pop.tsTxn) &&
    (tsAxi.r.data(conf.wTimeStamp-1 downto 0).asUInt <= lkRespFifoS1.io.pop.tsTxn)

  val isRdGrant = (lkRespFifoS1.io.pop.lkType === LkT.rd) && tsRdGrant
  val isWrGrant = (lkRespFifoS1.io.pop.lkType =/= LkT.rd) && tsWrGrant
  val isAbt = ~isRdGrant || ~isWrGrant

  val isWrCmtRlse = ~isLkReqImme

  val tsAxiAwNrm, tsAxiAwCmt = Stream(Axi4Aw(axiConfDemux))
  val tsAxiWNrm, tsAxiWCmt = Stream(Axi4W(axiConfDemux))

  tsAxiAwNrm.addr := ((lkRespFifoS1.io.pop.tId << 6) + (lkRespFifoS1.io.pop.cId << conf.wChSize)).resized
  tsAxiAwCmt.addr := ((lkWrCmtRlse.tId << 6) + (lkWrCmtRlse.cId << conf.wChSize)).resized
  for (e <- Seq(tsAxiAwNrm, tsAxiAwCmt)){
    e.id := 0
    e.len := 0 // 64 bit
    e.size := log2Up(512 / 8)
    e.setBurstINCR()
  }

  val wrMask = ((U(1) << conf.wTimeStamp / 8) - 1).asBits
  tsAxiWNrm.strb := wrMask.resized // update read sample
  tsAxiWCmt.strb := (wrMask << conf.wTimeStamp / 8).resized
  for (e <- Seq(tsAxiWNrm, tsAxiWCmt)) {
    e.data.setAll()
    e.last := True
  }

  // FIXME: is now a simple mux
  tsAxi.aw << (isWrCmtRlse ? tsAxiAwCmt | tsAxiAwNrm)
  tsAxi.w << (isWrCmtRlse ? tsAxiWCmt | tsAxiWNrm)

  //
  val (f1, f2, f3, f4, f5) = StreamFork5(lkRespFifoS1.io.pop)
  lkRespPass2.payload := lkRespFifoS1.io.pop
  lkRespPass2.respType.allowOverride := isAbt ? LockRespType.abort | LockRespType.grant

  // txnAxi.ar
  txnAxi.ar.addr := ((lkRespFifoS1.io.pop.tId << 6) + (lkRespFifoS1.io.pop.cId << conf.wChSize)).resized
  txnAxi.ar.id := 0
  txnAxi.ar.len := (U(1) << lkRespFifoS1.io.pop.wLen) - 1
  txnAxi.ar.size := log2Up(512 / 8)
  txnAxi.ar.setBurstINCR()

  lkRespPass2.arbitrationFrom(f1)
  txnAxi.ar.arbitrationFrom(f2.throwWhen(~isRdGrant))
  tsAxiAwNrm.arbitrationFrom(f3.throwWhen(~isRdGrant))
  tsAxiWNrm.arbitrationFrom(f4.throwWhen(~isRdGrant))
  ltReqRlsePass2.arbitrationFrom(f5.throwWhen(~isAbt))



  val (ff1, ff2, ff3) = StreamFork3(lkWrCmtRlse)
  txnAxi.aw.addr := ((lkWrCmtRlse.tId << 6) + (lkWrCmtRlse.cId << conf.wChSize)).resized
  txnAxi.aw.id := 0
  txnAxi.aw.len := (U(1) << lkWrCmtRlse.wLen) - 1
  txnAxi.aw.size := log2Up(512 / 8)
  txnAxi.aw.setBurstINCR()

  txnAxi.aw.arbitrationFrom(ff1)
  tsAxiAwCmt.arbitrationFrom(ff2)
  tsAxiWCmt.arbitrationFrom(ff3)

  tsAxi.b.ready := True
  val lkWrCmtRlseResp = cloneOf(lkRespFifoS1.io.pop)
  lkWrCmtRlseResp.translateFrom(lkWrCmtRlse)(_ := _.toLkResp())
  lkRespFifoS2.io.push << StreamMux(isWrCmtRlse.asUInt, Vec(lkWrCmtRlseResp, lkRespFifoS1.io.pop)).continueWhen(tsAxi.w.fire) // FIXME: check if pressure
  ltReqRlsePass3.translateFrom(lkRespFifoS2.io.pop)(_ := _.toLkRlseReq(False, 0, False)).continueWhen(tsAxi.b.fire)


  // axi.aw / axi.w is individually processed (nBeat should be fifo)
  val nBeatQ = StreamFifoLowLatency(cloneOf(io.lkReq.wLen), 8)
  nBeatQ.io.push.payload := ((U(1)<<lkWrCmtRlse.wLen)-1).resized
  nBeatQ.io.push.valid := lkWrCmtRlse.fire
  nBeatQ.io.pop.ready := txnAxi.w.last && txnAxi.w.fire

  val cntBeat = Counter(io.lkReq.wLen.getWidth bits, txnAxi.w.fire)
  when(txnAxi.w.last && txnAxi.w.fire) (cntBeat.clear())

  val wrDataQ = cloneOf(io.wrData)
  wrDataQ << io.wrData.queue(8)
  val wrDataQCtrl = wrDataQ.continueWithToken(txnAxi.aw.fire, io.axi.w.last && io.axi.w.fire, 8)
  io.axi.w.data := wrDataQCtrl.payload
  io.axi.w.last := (cntBeat === nBeatQ.io.pop.payload)
  io.axi.w.arbitrationFrom(wrDataQCtrl)

  // rdData: directly bpss the io.axi.r
  io.rdData.translateFrom(io.axi.r)((a, b) => a.assignFromBits(b.data))

}

