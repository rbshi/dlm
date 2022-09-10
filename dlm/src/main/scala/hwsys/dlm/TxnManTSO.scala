package hwsys.dlm

import spinal.core.{UInt, _}
import spinal.core.Mem
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.bus.amba4.axi._
import spinal.lib.fsm.StateMachine
import hwsys.util._

import scala.language.postfixOps

// TODO: each channel may contain multiple tables, the tId to address translation logic will be dedicated
class TxnManTSO(conf: SysConfig) extends Component with RenameIO {

  val io = TxnManCSIO(conf)
  io.setDefault()

  // lkGet and lkRlse are be arbitrated and sent to io
  val lkReqGetLoc, lkReqRlseLocAbt, lkReqRlseLocNrm, lkReqGetRmt, lkReqRlseRmt = Stream(LkReq(conf, isTIdTrunc = false))
  io.lkReqLoc <-/< StreamArbiterFactory.roundRobin.noLock.onArgs(lkReqGetLoc, lkReqRlseLocAbt, lkReqRlseLocNrm)
  io.lkReqRmt <-/< StreamArbiterFactory.roundRobin.noLock.onArgs(lkReqGetRmt, lkReqRlseRmt)

  for (e <- Seq(lkReqGetLoc, lkReqRlseLocAbt, lkReqRlseLocNrm, lkReqGetRmt, lkReqRlseRmt))
    e.valid := False

  // store the txn instruction
  val txnMem = Mem(TxnEntry(conf), conf.dTxnMem)

  // store wr txns to commit
  val txnWrMemLoc = Mem(TxnEntry(conf), conf.dTxnMem)
  val txnWrMemRmt = Mem(TxnEntry(conf), conf.dTxnMem)

  // store txns for local tsReq (for response on tsAxi.r)
  val tsReqMemLoc = Mem(TxnEntry(conf), conf.dTxnMem)

  // store the granted wr items to release
  val lkWrMemLoc = Mem(LkResp(conf, isTIdTrunc = false), conf.dTxnMem)
  val lkWrMemRmt = Mem(LkResp(conf, isTIdTrunc = false), conf.dTxnMem)


  // context registers
  // NOTE: separate local / remote; some reg is redundant (may be simplified)
  val cntLkReqLoc, cntLkReqRmt, cntLkRespLocTab, cntLkRespLocMem, cntLkRespRmt, cntLkHoldLoc, cntLkHoldRmt,
  cntLkReqWrLoc, cntLkReqWrRmt, cntLkHoldWrLoc, cntLkHoldWrRmt, cntCmtReqLoc, cntCmtReqRmt, cntCmtRespLoc, cntCmtRespRmt,
  cntRlseReqLoc, cntRlseReqRmt, cntRlseReqWrLoc, cntRlseReqWrRmt,
  cntTsRlseRdPush, cntTsRlseRdPop,  cntTsRlseWrPush, cntTsRlseWrPop
  = Vec(Reg(UInt(conf.wMaxTxnLen bits)).init(0), conf.nTxnCS)

  val tsTxn = Vec(Reg(UInt(conf.wTimeStamp bits)), conf.nTxnCS)

  // memory ts update join queue (share between rdReq TsUp and wrReq TsUp during commit)
  val tsWrRdReq, tsWrWrReq = Stream(tsWrEntry(conf))
  val tsWr = StreamArbiterFactory.roundRobin.noLock.onArgs(tsWrRdReq, tsWrWrReq)

  val tsRlseEntryRd = Stream(TxnEntry(conf))
  // push while tsWrRd/tsWrWr.fire and pop while tsAxi.b.fire
  val tsRlseMemRdLoc, tsRlseMemWrLoc = Mem(TxnEntry(conf), conf.dTxnMem)

  // status register
  val rAbort = Vec(RegInit(False), conf.nTxnCS)
  val rReqDone, rRlseDone = Vec(RegInit(True), conf.nTxnCS) // init to True, to trigger the first txnMem load and issue
  // respStatus: ReqTab (on-chip), Mem (off-chip)
  // TODO: reset after reloading the txn
  val rRespStatusTab, rRespStatusMem = Vec(Reg(Bits(log2Up(conf.maxTxnLen) bits)).default(0), conf.nTxnCS)

  // axi arbiter to demux io.axi
  val axiRdArb = Axi4ReadOnlyArbiter(conf.axiConf, 2)
  val axiWrArb = Axi4WriteOnlyArbiter(conf.axiConf, 2, 0)
  axiRdArb.io.output <> io.axi.toReadOnly()
  axiWrArb.io.output <> io.axi.toWriteOnly()

  // FIXME:
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


  /**
   * Component: TS Request
   * 1. context switch of txn
   * 2. send TSReq (local: both ReqTab & Memory / remote: ReqInfo)
   */
  val tsReq = new StateMachine {
    val CS_TXN = new State with EntryPoint
    val RD_TXN = new State


    val curTxnId = Reg(UInt(conf.wTxnId bits)).init(0)

    val txnMemRdCmd = Stream(UInt(conf.wTxnMemAddr bits))
    txnMemRdCmd.valid := False
    txnMemRdCmd.payload := 0
    val txnMemRd = txnMem.streamReadSync(txnMemRdCmd)
    txnMemRd.ready := False

    val txnLen, reqIdx = Reg(UInt(conf.wMaxTxnLen bits)).init(0)
    val txnOffs = curTxnId << conf.wMaxTxnLen

    val mskTxn2Start = ~rReqDone.asBits & ~rAbort.asBits // msk to indicate which txn to start
    val rIdxTxn2Start = RegNext(OHToUInt(OHMasking.first(mskTxn2Start))) // stage

    // ctx switch
    CS_TXN.whenIsActive {
      when(RegNext(mskTxn2Start.orR)) {
        curTxnId := rIdxTxn2Start // reg
        txnMemRdCmd.valid := True
        txnMemRdCmd.payload := rIdxTxn2Start << conf.wMaxTxnLen
        txnMemRd.ready := True
      }

      when(txnMemRd.fire) {
        txnMemRdCmd.valid := False
        txnLen := txnMemRd.payload.asBits(conf.wMaxTxnLen - 1 downto 0).asUInt
        // start the txn
        // allocate ts
        tsTxn(rIdxTxn2Start) := io.cntClk
        // issue the tsReq
        goto(RD_TXN)
      }
    }

    // tsReq (on-chip req table) payload
    for (e <- Seq(lkReqGetLoc, lkReqGetRmt))
      e.payload := txnMemRd.toLkReq(io.nodeId, io.txnManId, curTxnId, False, reqIdx)
    // tsReq (off-chip ts) payload on `tsAxi`
    // TODO: address calculation based on instruction

    // barrier the fire of reqTab & txAxi
    val barrierLocVld = Bool().default(False)
    val barrierLocFire = StreamBarrier(lkReqGetLoc, tsAxi.ar, barrierLocVld)

    val lkReqFire = barrierLocFire || lkReqGetRmt.fire
    val isLocal = txnMemRd.nId === io.nodeId

    RD_TXN.whenIsActive {
      // txnMem Read
      val txnCntAddr = txnOffs + reqIdx + 1
      txnMemRdCmd.valid := True
      txnMemRdCmd.payload := lkReqFire ? (txnCntAddr + 1) | txnCntAddr // backpressure
      txnMemRd.ready := lkReqFire

      //TODO: process `insTab` request

      // issue lkReq to local / rmt
      switch(isLocal) {
        is(True)(barrierLocVld := txnMemRd.valid)
        is(False)(lkReqGetRmt.valid := txnMemRd.valid)
      }

      // TODO check: `barrierLocFire` should guarantee fires on both `tsAxi` && `lkReqGetLoc`
      when(lkReqFire) {
        reqIdx := reqIdx + 1
        switch(isLocal) {
          is(True){
            tsReqMemLoc.write(txnOffs + cntLkReqLoc(curTxnId), txnMemRd.payload)
            cntLkReqLoc(curTxnId) := cntLkReqLoc(curTxnId) + 1
          }
          is(False)(cntLkReqRmt(curTxnId) := cntLkReqRmt(curTxnId) + 1)
        }
        // req wr lock
        when((txnMemRd.lkType === LkT.wr) || (txnMemRd.lkType === LkT.raw)) {
          switch(isLocal) {
            is(True) {
              txnWrMemLoc.write(txnOffs + cntLkReqWrLoc(curTxnId), txnMemRd.payload)
              cntLkReqWrLoc(curTxnId) := cntLkReqWrLoc(curTxnId) + 1
            }
            is(False) {
              txnWrMemRmt.write(txnOffs + cntLkReqWrRmt(curTxnId), txnMemRd.payload)
              cntLkReqWrRmt(curTxnId) := cntLkReqWrRmt(curTxnId) + 1
            }
          }
        }
      }

      // NOTE: lkReq of next Txn OR if abort, stop issue the req
      // NOTE: the state jump with rAbort here may cause vld without fire -> the subsequent arb should be `nolock`
      when((lkReqFire && (reqIdx === (txnLen - 1))) || rAbort(curTxnId)) {
        txnMemRdCmd.valid := False
        rReqDone(curTxnId).set()
        reqIdx.clearAll()
        // curTxnId := curTxnId + 1
        goto(CS_TXN)
      }

    }
  }


  /**
   * component2: ts response
   *
   * */
  val tsRespLoc = new StateMachine {

    val WAIT_RESP = new State with EntryPoint
    val LOCAL_RD_REQ_UPTS = new State

    // ctx switch (two individual sets for tsAxi.resp & lkRespLoc)
    // ctx switch: tsAxi (curTxnId: tsAxi.rid.truncated, cnt++ as axi with same id is in order)
    // Timing: tsAxi.r.fire / curTxnIdTsAxi / txnOffsTsAxi => rCurTxnIdTsAxi / txnEntryRespTsAxi => rrCurTxnIdTsAxi / rTxnEntryRespTsAxi
    val curTxnIdTsAxi = tsAxi.r.id // no need to .trim(1) as tsAxi.r.id is only 5 bits
    val rCurTxnIdTsAxi = RegNextWhen(curTxnIdTsAxi, tsAxi.r.fire)
    val rrCurTxnIdTsAxi = RegNext(rCurTxnIdTsAxi)
    val txnOffsTsAxi = curTxnIdTsAxi << conf.wMaxTxnLen
    val txnEntryRespTsAxi = tsReqMemLoc.readSync(txnOffsTsAxi + cntLkRespLocMem(curTxnIdTsAxi))
    val rTxnEntryRespTsAxi = RegNext(txnEntryRespTsAxi)
    when(tsAxi.r.fire)(cntLkRespLocMem(curTxnIdTsAxi) := cntLkRespLocMem(curTxnIdTsAxi) + 1) // TODO: abstract this
    val rcntLkRespLocMem = RegNext(cntLkRespLocMem(curTxnIdTsAxi))
    val rTsAxiRdFire = RegNext(tsAxi.r.fire)

    // function logic
    // TODO: TS compare logic
    val tsAxiIsGrant = True


    // ctx switch: lkRespLoc
    // Timing: lkRespLoc.fire / curTxnIdLkRespLoc => rCurTxnIdLkRespLoc / rLkRespLoc
    val curTxnIdLkRespLoc = io.lkRespLoc.txnId
    val rLkRespLoc = RegNextWhen(io.lkRespLoc, io.lkRespLoc.fire)
    val txnOffsLkRespLoc = curTxnIdLkRespLoc << conf.wMaxTxnLen

    // interaction between lkRespLoc & tsAxi.r
    io.lkRespLoc.ready := isActive(WAIT_RESP)
    tsAxi.r.ready := isActive(WAIT_RESP)

    val tsUpSel = Bool().default(False)
    val rTsUpSel = RegNext(tsUpSel).init(False) // TsUp info selection between tsAxi (1) and lkResp (0)

    val enWrLkMemLocTsAxi, enWrLkMemLocLkResp = Bool().default(False)

    // tsReq issued on both lkReq & tsAxi, must wait response before go to next txn

    WAIT_RESP.whenIsActive {
      // tsAxi.r.fire.pipe (for tsReqMemLoc.readSync)
      when(rTsAxiRdFire) {
        cntLkRespLocMem(rCurTxnIdTsAxi) := cntLkRespLocMem(rCurTxnIdTsAxi) + 1
        when(tsAxiIsGrant) {
          // case: grant
          // set rRespStatus
          rRespStatusMem(rCurTxnIdTsAxi)(rcntLkRespLocMem).set()
          // check lkRespLoc grant
          when(rRespStatusTab(rCurTxnIdTsAxi)(rcntLkRespLocMem)){
            // operation granted
            switch(txnEntryRespTsAxi.lkType){
              is(LkT.rd){
                tsUpSel.set()
                goto(LOCAL_RD_REQ_UPTS)
              }
              is(LkT.wr){
                enWrLkMemLocTsAxi.set()
                cntLkHoldWrLoc(rCurTxnIdTsAxi) := cntLkHoldWrLoc(rCurTxnIdTsAxi) + 1
              }
              is(LkT.raw) {
                tsUpSel.set()
                enWrLkMemLocTsAxi.set()
                cntLkHoldWrLoc(rCurTxnIdTsAxi) := cntLkHoldWrLoc(rCurTxnIdTsAxi) + 1
                goto(LOCAL_RD_REQ_UPTS)
              }
              is(LkT.insTab)()
            }
          }
        } otherwise {
          // case: abort
          rAbort(rCurTxnIdTsAxi) := True
        }
      }

      // lkRespLoc.fire
      when(io.lkRespLoc.fire) {
        cntLkRespLocTab(curTxnIdLkRespLoc) := cntLkRespLocTab(curTxnIdLkRespLoc) + 1
        switch(io.lkRespLoc.respType) {
          // case: grant
          is(LockRespType.grant) {
            // set rRespStatus
            rRespStatusTab(curTxnIdLkRespLoc)(io.lkRespLoc.lkIdx).set()
            // check tsAxi grant
            when(rRespStatusMem(curTxnIdLkRespLoc)(io.lkRespLoc.lkIdx)) {
              // operation grant
              switch(io.lkRespLoc.lkType) {
                is(LkT.rd){
                  goto(LOCAL_RD_REQ_UPTS)
                }
                is(LkT.wr) {
                  enWrLkMemLocLkResp.set()
                  cntLkHoldWrLoc(curTxnIdLkRespLoc) := cntLkHoldWrLoc(curTxnIdLkRespLoc) + 1
                }
                is(LkT.raw) {
                  enWrLkMemLocLkResp.set()
                  cntLkHoldWrLoc(curTxnIdLkRespLoc) := cntLkHoldWrLoc(curTxnIdLkRespLoc) + 1
                  goto(LOCAL_RD_REQ_UPTS)
                }
                is(LkT.insTab)()
              }
            } otherwise {
              // case: abort
              rAbort(curTxnIdLkRespLoc) := True
            }
          }
        }
      }

      // FIXME: double check
      lkWrMemLoc.write(tsUpSel ? ((rCurTxnIdTsAxi << conf.wMaxTxnLen) + cntLkHoldWrLoc(rCurTxnIdTsAxi)) |
        ((curTxnIdLkRespLoc << conf.wMaxTxnLen) + cntLkHoldWrLoc(curTxnIdLkRespLoc)),
        tsUpSel ? txnEntryRespTsAxi.toLkResp(io.nodeId, io.txnManId, rCurTxnIdTsAxi, False, 0) | io.lkRespLoc.payload,
        enWrLkMemLocTsAxi | enWrLkMemLocLkResp)

    }

    // TODO: data path
    // FIXME: tId -> addr translation logic
    // txnAxi.ar.addr := (((rLkResp.tId << rLkResp.wLen) << 6) + (rLkResp.cId << conf.wChSize)).resized

    // data interface on txnAxi: if rTsUpSel, use the resp info on tsAxi; otherwise, use lkResp
    val tupStartAddr = rTsUpSel ? ((rTxnEntryRespTsAxi.tId << 6) + (rTxnEntryRespTsAxi.cId << conf.wChSize)).resized | ((rLkRespLoc.tId << 6) + (rLkRespLoc.cId << conf.wChSize)).resized
    txnAxi.ar.addr := tupStartAddr // start with 64-bit r&w timestamp
    val txnId = rTsUpSel ? rrCurTxnIdTsAxi | rLkRespLoc.txnId
    txnAxi.ar.id := txnId
    txnAxi.ar.len := rTsUpSel ? ((U(1) << rTxnEntryRespTsAxi.wLen)-1) | ((U(1) << rLkRespLoc.wLen) - 1)
    txnAxi.ar.size := log2Up(512 / 8)
    txnAxi.ar.setBurstINCR()

    tsWrRdReq.txnId := rTsUpSel ? rrCurTxnIdTsAxi | rLkRespLoc.txnId
    tsWrRdReq.ts := rTsUpSel ? tsTxn(rrCurTxnIdTsAxi) | tsTxn(rLkRespLoc.txnId)
    tsWrRdReq.addr := tupStartAddr
    tsWrRdReq.isWr := False

    tsRlseEntryRd.payload := rTsUpSel ? rTxnEntryRespTsAxi | rLkRespLoc.toTxnEntry()

    // barrier the txnAxi.ar & ts update queue (sharing the tsAxi.aw,w with commit logic), insert the lkEntry & ts update
    val barrierFireTxnRdTsWr = StreamBarrier(txnAxi.ar, tsWrRdReq, tsRlseEntryRd, isActive(LOCAL_RD_REQ_UPTS))

    val txnOffs = txnId << conf.wMaxTxnLen

    LOCAL_RD_REQ_UPTS.whenIsActive {
      // write tsRlseEntryRd to tsRlseRdLoc memory for release purpose
      tsRlseMemRdLoc.write(txnOffs + cntTsRlseRdPush(txnId), tsRlseEntryRd, barrierFireTxnRdTsWr)
      when(barrierFireTxnRdTsWr){
        cntTsRlseRdPush(txnId) := cntTsRlseRdPush(txnId) + 1
        goto(WAIT_RESP)
      }
    }

  }


  /**
   * component3: axi response
   * */

  val compAxiResp = new Area {
    // rd resp
    txnAxi.r.ready := True
    // write resp
    txnAxi.b.ready := True

    val rAxiBFire = RegNext(txnAxi.b.fire)
    val rAxiBId = RegNext(txnAxi.b.id)
    when(rAxiBFire) {
      cntCmtRespLoc(rAxiBId) := cntCmtRespLoc(rAxiBId) + 1
    }
  }



  /**
   * Component: txnCommit
   * */

  val txnCommit = new StateMachine {

    val CS_TXN = new State with EntryPoint
    val LOCAL_AW, LOCAL_W = new State

    val curTxnId = Reg(UInt(conf.wTxnId bits)).init(0)
    val txnOffs = curTxnId << conf.wMaxTxnLen

    val cmtTxn = txnWrMemLoc.readSync(txnOffs + cntCmtReqLoc(curTxnId))
    val rCmtTxn = RegNext(cmtTxn)

    val nBeat = Reg(UInt(8 bits)).init(0)

    val getAllLkResp = (cntLkReqLoc(curTxnId) === cntLkRespLocMem(curTxnId)) && (cntLkReqLoc(curTxnId) === cntLkRespLocTab(curTxnId)) &&
      (cntLkReqRmt(curTxnId) === cntLkRespRmt(curTxnId))

    CS_TXN.whenIsActive {
      /**
       * 1. get All lk resp
       * 2. sent out all lk req
       * 3. no abort
       * 4. send #cmtReq < #LkHoldWr local
       * */
      val cmtCret = getAllLkResp && rReqDone(curTxnId) && ~rAbort(curTxnId) && (cntCmtReqLoc(curTxnId) < cntLkHoldWrLoc(curTxnId))
      when(cmtCret) {
        goto(LOCAL_AW)
      } otherwise {
        curTxnId := curTxnId + 1
      }
    }

    // TODO: data path
    // fixme: tId -> addr translation logic
    // txnAxi.aw.addr := (((cmtTxn.tId << cmtTxn.wLen) << 6) + (cmtTxn.cId << conf.wChSize)).resized
    txnAxi.aw.addr := ((cmtTxn.tId << 6) + (cmtTxn.cId << conf.wChSize)).resized
    txnAxi.aw.id := curTxnId
    txnAxi.aw.len := (U(1) << cmtTxn.wLen) - 1
    txnAxi.aw.size := log2Up(512 / 8)
    txnAxi.aw.setBurstINCR()
    txnAxi.aw.valid := isActive(LOCAL_AW)

    LOCAL_AW.whenIsActive {
      when(txnAxi.aw.fire) {
        goto(LOCAL_W)
      }
    }

    txnAxi.w.data.setAll()
    txnAxi.w.last := (nBeat === (U(1) << rCmtTxn.wLen) - 1)
    txnAxi.w.valid := isActive(LOCAL_W)

    LOCAL_W.whenIsActive {

      when(txnAxi.w.fire) {
        nBeat := nBeat + 1
        when(txnAxi.w.last) {
          cntCmtReqLoc(curTxnId) := cntCmtReqLoc(curTxnId) + 1
          nBeat.clearAll()
          goto(CS_TXN)
        }
      }
      // txnAxi.b will be tackled in component3
    }
  }


  /**
   * Component: local ts (table) release (normal of Wr: TsUp + abort of Wr: tsRlse)
   * */

  val tsRlseWr = new StateMachine {

    val CS_TXN = new State with EntryPoint
    val LOCAL_WR_REQ_UPTS = new State

    val curTxnId = Reg(UInt(conf.wTxnId bits)).init(0)
    val txnOffs = curTxnId << conf.wMaxTxnLen
    val lkItem = lkWrMemLoc.readSync(txnOffs + cntRlseReqWrLoc(curTxnId))
    val getAllLkResp = (cntLkReqLoc(curTxnId) === cntLkRespLocTab(curTxnId)) &&
      (cntLkReqLoc(curTxnId) === cntLkRespLocMem(curTxnId)) && (cntLkReqRmt(curTxnId) === cntLkRespRmt(curTxnId))

    CS_TXN.whenIsActive {
      /**
       * Normal case
       * 1. get all lk resp
       * 2. rAbort || rReqDone
       * 3. rAbort || WrLoc <= CmtRespLoc
       * 4. send #RlseReq loc === #LkHold local
       * */
      val rlseCretNormal = getAllLkResp && (rAbort(curTxnId) || rReqDone(curTxnId)) && (rAbort(curTxnId) ||
        cntRlseReqWrLoc(curTxnId) < cntCmtRespLoc(curTxnId) || cntRlseReqWrLoc(curTxnId)===0)
      when(rlseCretNormal) {
        goto(LOCAL_WR_REQ_UPTS)
      } otherwise {
        curTxnId := curTxnId + 1
      }
    }
    
    tsWrWrReq.txnId := curTxnId
    tsWrWrReq.ts := tsTxn(curTxnId)
    tsWrWrReq.addr := ((lkItem.tId << 6) + (lkItem.cId << conf.wChSize)).resized
    tsWrWrReq.isWr := True

    lkReqRlseLocAbt.payload := lkItem.toLkRlseReq(rAbort(curTxnId), lkItem.lkIdx, False)

    LOCAL_WR_REQ_UPTS.whenIsActive {
      when(rAbort(curTxnId)) {
        // if release with abort: lkRlse only
        lkReqRlseLocAbt.valid.set()
        when(lkReqRlseLocAbt.fire) {
          cntRlseReqWrLoc(curTxnId) := cntRlseReqWrLoc(curTxnId) + 1
          goto(CS_TXN)
        }
      } otherwise {
        // else release with commit, TsUpdate on tsWrWrReq & push entry to tsRlseEntryWr for release
        tsWrWrReq.valid.set()
        tsRlseMemWrLoc.write(txnOffs+cntTsRlseWrPush(curTxnId), lkItem.toTxnEntry(), tsWrWrReq.fire)
        when(tsWrWrReq.fire){
          cntTsRlseWrPush(curTxnId) := cntTsRlseWrPush(curTxnId) + 1
          cntRlseReqWrLoc(curTxnId) := cntRlseReqWrLoc(curTxnId) + 1 // FIXME: too early to increase?
          goto(CS_TXN)
        }
      }
    }

  }


  /**
   * Component: local ts release (normal case: reaction to tsAxi.b)
   * */
  val tsRlseNrm = new StateMachine {

    val FIRE_B = new State with EntryPoint
    val FIRE_RELEASE = new State

    val isRlseRd = cntTsRlseRdPush(tsAxi.b.id) =/= cntTsRlseRdPop(tsAxi.b.id) // PushPtr > PopPtr
    val txnOffs = tsAxi.b.id << conf.wMaxTxnLen
    val txnEntryRd = tsRlseMemRdLoc.readSync(txnOffs + cntTsRlseRdPop(tsAxi.b.id))
    val txnEntryWr = tsRlseMemWrLoc.readSync(txnOffs + cntTsRlseWrPop(tsAxi.b.id))

    lkReqRlseLocNrm.payload := isRlseRd ? txnEntryRd.toLkReq(io.nodeId, io.txnManId, tsAxi.b.id, True, 0) | txnEntryWr.toLkReq(io.nodeId, io.txnManId, tsAxi.b.id, True, 0)

    FIRE_B.whenIsActive {
      tsAxi.b.ready := True
      when(tsAxi.b.fire) {
        switch(isRlseRd){
          is(True)(cntTsRlseRdPop(tsAxi.b.id) := cntTsRlseRdPop(tsAxi.b.id) + 1)
          is(False)(cntTsRlseWrPop(tsAxi.b.id) := cntTsRlseWrPop(tsAxi.b.id) + 1)
        }
        goto(FIRE_RELEASE)
      }
    }

    FIRE_RELEASE.whenIsActive {
      tsAxi.b.ready := False
      lkReqRlseLocNrm.valid.set()
      when(lkReqRlseLocNrm.fire)(goto(FIRE_B))
    }

  }


  // TODO: consider how to abstract the similar functions?
  /**
   * Component: rRlseDone ctrl, local release
   * */
  val rlseDoneCtrlLoc = new Area {
    val rCurTxnId = RegNextWhen(io.lkReqLoc.txnId, io.lkReqLoc.fire) // stage also when lkGet
    val rFire = RegNext(lkReqRlseLocAbt.fire | lkReqRlseLocNrm.fire, False)
    val getAllLkRlse = (cntRlseReqLoc(rCurTxnId) === cntLkHoldLoc(rCurTxnId)) && (cntRlseReqRmt(rCurTxnId) === cntLkHoldRmt(rCurTxnId))
    val getAllLkResp = (cntLkReqLoc(rCurTxnId) === cntLkRespLocMem(rCurTxnId)) && (cntLkReqLoc(rCurTxnId) === cntLkRespLocTab(rCurTxnId)) &&
      (cntLkReqRmt(rCurTxnId) === cntLkRespRmt(rCurTxnId))
    /**
     * Criterion of rRlseDone.set
     * 1. lkRlse.fire
     * 2. getAllRlse && getAllLkResp
     * 3. rReqDone
     * */
    when(rFire && (getAllLkRlse && getAllLkResp) && rReqDone(rCurTxnId)) {
      rRlseDone(rCurTxnId).set()
      when(rAbort(rCurTxnId))(io.cntTxnAbt := io.cntTxnAbt + 1) otherwise (io.cntTxnCmt := io.cntTxnCmt + 1)
    }
  }

  /**
   * Component: rRlseDone ctrl, remote release
   * */
  val rlseDoneCtrlRmt = new Area {
    val rCurTxnId = RegNextWhen(io.lkReqRmt.txnId, io.lkReqRmt.fire) // stage also when lkGet
    val rFire = RegNext(lkReqRlseRmt.fire, False)
    val getAllLkRlse = (cntRlseReqLoc(rCurTxnId) === cntLkHoldLoc(rCurTxnId)) && (cntRlseReqRmt(rCurTxnId) === cntLkHoldRmt(rCurTxnId))
    val getAllLkResp = (cntLkReqLoc(rCurTxnId) === cntLkRespLocMem(rCurTxnId)) && (cntLkReqLoc(rCurTxnId) === cntLkRespLocTab(rCurTxnId)) &&
      (cntLkReqRmt(rCurTxnId) === cntLkRespRmt(rCurTxnId))
    when(rFire && (getAllLkRlse && getAllLkResp) && rReqDone(rCurTxnId)) {
      rRlseDone(rCurTxnId).set()
      when(rAbort(rCurTxnId))(io.cntTxnAbt := io.cntTxnAbt + 1) otherwise (io.cntTxnCmt := io.cntTxnCmt + 1)
    }
  }
  

  /**
   * Component: ts write to memory
   * */
  val tsWrMem = new Area {
    tsAxi.aw.addr := tsWr.addr
    tsAxi.aw.id := tsWr.txnId
    tsAxi.aw.len := 0 // 64 bit
    tsAxi.aw.size := log2Up(512 / 8)
    tsAxi.aw.setBurstINCR()

    tsAxi.w.data := tsWr.ts ## tsWr.ts // concatenate, write with strb bits
    assert(
      assertion = conf.wTimeStamp % 8 == 0,
      message = s"wTimeStamp=${conf.wTimeStamp}, MUST be a multiple of 8."
    )
    val wrMask = ((U(1) << conf.wTimeStamp / 8) - 1).asBits
    tsAxi.w.strb := (tsWr.isWr ? (wrMask << conf.wTimeStamp / 8) | wrMask).resized
    tsAxi.w.last := True

    // TODO: simplify
    val barrierFireTsWr = StreamBarrier(tsAxi.aw, tsAxi.w, tsWr.valid)
    tsWr.ready := barrierFireTsWr
  }
  

  /**
   * component7: loadTxnMem
   *
   * */

  val compLoadTxn = new StateMachine {
    val IDLE = new State with EntryPoint
    val CS_TXN, RD_CMDAXI, LD_TXN = new State

    val curTxnId = Reg(UInt(conf.wTxnId bits)).init(0)
    val cntTxn = Reg(UInt(32 bits)).init(0)
    val txnOffs = curTxnId << conf.wMaxTxnLen

    io.cmdAxi.ar.addr := (((cntTxn << conf.wMaxTxnLen) << log2Up(8)) + (io.cmdAddrOffs << 6)).resized // each txn takes 8 Bytes
    io.cmdAxi.ar.id := 0
    // each 512 b contains 8 txn word (64 b / word)
    io.cmdAxi.ar.len := ((U(1) << (conf.wMaxTxnLen - 3)) - 1).resized
    io.cmdAxi.ar.size := log2Up(512 / 8)
    io.cmdAxi.ar.setBurstINCR()

    // rd on cmdAxi
    io.cmdAxi.ar.valid := isActive(RD_CMDAXI)

    val rCmdAxiData = RegNextWhen(io.cmdAxi.r.data, io.cmdAxi.r.fire)
    val rCmdAxiFire = RegNext(io.cmdAxi.r.fire, False)
    // 512 / 64
    val cmdAxiDataSlice = rCmdAxiData.subdivideIn(8 slices)

    val rTxnMemLd = RegInit(False)
    val cntTxnWordInLine = Counter(8, rTxnMemLd) // FIXME: 8*8 instructions in each axi word
    val cntTxnWord = Counter(conf.wMaxTxnLen bits, rTxnMemLd)

    // IDLE: wait start signal
    IDLE.whenIsActive {
      when(io.start) {
        // reset regs
        curTxnId.clearAll()
        cntTxn.clearAll()
        goto(CS_TXN)
      }
    }
    
    CS_TXN.whenIsActive {
      // TODO Txn slot empty criterion: 
      when(rRlseDone(curTxnId)) {
        goto(RD_CMDAXI)
      } otherwise {
        curTxnId := curTxnId + 1
      }
    }

    RD_CMDAXI.whenIsActive {
      when(io.cmdAxi.ar.fire) {
        rTxnMemLd.clear()
        cntTxnWordInLine.clear()
        cntTxnWord.clear()
        goto(LD_TXN)
      }
    }

    // load txnMem
    io.cmdAxi.r.ready := (isActive(LD_TXN) && cntTxnWordInLine === 0 && ~rCmdAxiFire) ? True | False

    LD_TXN.whenIsActive {
      when(io.cmdAxi.r.fire)(rTxnMemLd.set())

      val txnSt = TxnEntry(conf)
      txnSt.assignFromBits(cmdAxiDataSlice(cntTxnWordInLine)) // cmdAxiDataSlice is staged from io.cmdAxi.r
      txnMem.write(txnOffs + cntTxnWord, txnSt, rTxnMemLd)

      when(cntTxnWordInLine.willOverflow)(rTxnMemLd.clear())
      
      when(cntTxnWord.willOverflow) {
        //TODO: clear all cnt register
        for (e <- Seq(cntLkReqLoc, cntLkReqRmt, cntLkRespLocTab, cntLkRespLocMem, cntLkRespRmt, cntLkHoldLoc, cntLkHoldRmt,
          cntLkReqWrLoc, cntLkReqWrRmt, cntLkHoldWrLoc, cntLkHoldWrRmt, cntCmtReqLoc, cntCmtReqRmt, cntCmtRespLoc, cntCmtRespRmt,
          cntRlseReqLoc, cntRlseReqRmt, cntRlseReqWrLoc, cntRlseReqWrRmt,
          cntTsRlseRdPush, cntTsRlseRdPop, cntTsRlseWrPush, cntTsRlseWrPop))
          e(curTxnId) := U(0, conf.wMaxTxnLen bits) // why the clearAll() DOES NOT work?

        for (e <- Seq(rReqDone, rAbort, rRlseDone))
          e(curTxnId).clear()

        io.cntTxnLd := io.cntTxnLd + 1
        cntTxn := cntTxn + 1

        when(cntTxn === (io.txnNumTotal - 1))(goto(IDLE)) otherwise (goto(CS_TXN))
      } // load one txn finished
    }
  }

  // io.done: all txn rlseDone; all txn loaded; set done only once
  when(rRlseDone.andR && io.cntTxnLd === io.txnNumTotal && ~io.done)(io.done.set())

  
  
  /**
   * Component: clk counter
   * */
  
  // io.cntClk & clear status reg
  val clkCnt = new StateMachine {
    val IDLE = new State with EntryPoint
    val CNT = new State
    IDLE.whenIsActive {
      when(io.start) {
        io.cntClk.clearAll()

        // clear status reg
        io.done.clear()
        Seq(io.cntTxnCmt, io.cntTxnAbt, io.cntTxnLd).foreach(_.clearAll())

        goto(CNT)
      }
    }

    CNT.whenIsActive {
      io.cntClk := io.cntClk + 1
      when(io.done)(goto(IDLE))
    }
  }


}