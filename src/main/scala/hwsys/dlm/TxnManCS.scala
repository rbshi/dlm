package hwsys.dlm

import spinal.core.{UInt, _}
import spinal.core.Mem
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.bus.amba4.axi._
import spinal.lib.fsm.StateMachine
import hwsys.util._

// TODO: each channel may contain multiple tables, the tId to address translation logic will be dedicated
class TxnManCS(conf: SysConfig) extends Component with RenameIO {

  val io = TxnManCSIO(conf)
  io.setDefault()

  // lkGet and lkRlse are be arbitrated and sent to io
  val lkReqGetLoc, lkReqRlseLoc, lkReqGetRmt, lkReqRlseRmt = Stream(LkReq(conf, isTIdTrunc = false))
  io.lkReqLoc <-/< StreamArbiterFactory.roundRobin.noLock.onArgs(lkReqGetLoc, lkReqRlseLoc)
  io.lkReqRmt <-/< StreamArbiterFactory.roundRobin.noLock.onArgs(lkReqGetRmt, lkReqRlseRmt)

  for (e <- Seq(lkReqGetLoc, lkReqRlseLoc, lkReqGetRmt, lkReqRlseRmt))
    e.valid := False

  val txnMem = Mem(TxnEntry(conf), conf.dTxnMem)

  // store wr tnxs to commit
  val txnWrMemLoc = Mem(TxnEntry(conf), conf.dTxnMem)
  val txnWrMemRmt = Mem(TxnEntry(conf), conf.dTxnMem)
  // store the obtained lock items to release
  val lkMemLoc = Mem(LkResp(conf, isTIdTrunc = false), conf.dTxnMem)
  val lkMemRmt = Mem(LkResp(conf, isTIdTrunc = false), conf.dTxnMem)

  // context registers
  // NOTE: separate local / remote; some reg is redundant (may be simplified)
  val cntLkReqLoc, cntLkReqRmt, cntLkRespLoc, cntLkRespRmt, cntLkHoldLoc, cntLkHoldRmt, cntLkReqWrLoc, cntLkReqWrRmt, cntLkHoldWrLoc, cntLkHoldWrRmt, cntCmtReqLoc, cntCmtReqRmt, cntCmtRespLoc, cntCmtRespRmt, cntRlseReqLoc, cntRlseReqRmt, cntRlseReqWrLoc, cntRlseReqWrRmt, cntRlseRespLoc, cntRlseRespRmt = Vec(Reg(UInt(conf.wMaxTxnLen bits)).init(0), conf.nTxnCS)
  // status register
  val rAbort = Vec(RegInit(False), conf.nTxnCS)
  val rReqDone, rRlseDone = Vec(RegInit(True), conf.nTxnCS) // init to True, to trigger the first txnMem load and issue

  /**
   * component1: lock request
   */
  val compLkReq = new StateMachine {
    val CS_TXN = new State with EntryPoint
    val RD_TXN_HD, RD_TXN = new State

    val curTxnId = Reg(UInt(conf.wTxnId bits)).init(0)

    val txnMemRdCmd = Stream(UInt(conf.wTxnMemAddr bits))
    txnMemRdCmd.valid := False
    txnMemRdCmd.payload := 0
    val txnMemRd = txnMem.streamReadSync(txnMemRdCmd)
    txnMemRd.ready := False

    val txnLen, reqLen = Reg(UInt(conf.wMaxTxnLen bits)).init(0)
    val txnOffs = curTxnId << conf.wMaxTxnLen

    for (e <- Seq(lkReqGetLoc, lkReqGetRmt))
      e.payload := txnMemRd.toLkReq(io.nodeId, io.txnManId, curTxnId, False, reqLen)

    val mskTxn2Start = ~rReqDone.asBits & ~rAbort.asBits // msk to indicate which txn to start
    val rIdxTxn2Start = RegNext(OHToUInt(OHMasking.first(mskTxn2Start))) // stage


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
        goto(RD_TXN)
      }
    }

    val lkReqFire = lkReqGetLoc.fire || lkReqGetRmt.fire
    val isLocal = txnMemRd.nId === io.nodeId

    RD_TXN.whenIsActive {

      val txnCntAddr = txnOffs + reqLen + 1
      txnMemRdCmd.valid := True
      txnMemRdCmd.payload := lkReqFire ? (txnCntAddr + 1) | txnCntAddr // backpressure
      txnMemRd.ready := lkReqFire

      when(lkReqFire) {
        reqLen := reqLen + 1
        switch(isLocal) {
          is(True)(cntLkReqLoc(curTxnId) := cntLkReqLoc(curTxnId) + 1)
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

      // issue lkReq to local / rmt
      switch(isLocal) {
        is(True)(lkReqGetLoc.valid := txnMemRd.valid)
        is(False)(lkReqGetRmt.valid := txnMemRd.valid)
      }

      // NOTE: lkReq of next Txn OR if abort, stop issue the req
      // NOTE: the state jump with rAbort here may cause vld without fire -> the subsequent arb should be `nolock`
      when((lkReqFire && (reqLen === (txnLen - 1))) || rAbort(curTxnId)) {
        txnMemRdCmd.valid := False
        rReqDone(curTxnId).set()
        reqLen.clearAll()
        // curTxnId := curTxnId + 1
        goto(CS_TXN)
      }

    }
  }


  /**
   * component2: lock response
   *
   * */
  val compLkRespLoc = new StateMachine {

    val WAIT_RESP = new State with EntryPoint
    val LOCAL_RD_REQ = new State

    val rLkResp = RegNextWhen(io.lkRespLoc, io.lkRespLoc.fire)
    val curTxnId = io.lkRespLoc.txnId
    val txnOffs = curTxnId << conf.wMaxTxnLen

    val rCurTxnId = RegNextWhen(curTxnId, io.lkRespLoc.fire)
    val getAllRlse = (cntRlseRespLoc(rCurTxnId) === cntLkHoldLoc(rCurTxnId)) && (cntRlseRespRmt(rCurTxnId) === cntLkHoldRmt(rCurTxnId))
    val getAllLkResp = (cntLkReqLoc(rCurTxnId) === cntLkRespLoc(rCurTxnId)) && (cntLkReqRmt(rCurTxnId) === cntLkRespRmt(rCurTxnId))

    // Since rReqDone will have two cycles latency (io.lkResp (c0) -> R -> rAbort (c1) -> R -> rReqDone (c2)), the following logic occurs in c1, so use ~(xxx) as extra statements to avoid lkReq happens in c2.
    val firstReqAbt = rAbort(rCurTxnId) && ~(io.lkReqLoc.fire && io.lkReqLoc.txnId === rCurTxnId) && ~(io.lkReqRmt.fire && io.lkReqRmt.txnId === rCurTxnId)

    val rFire = RegNext(io.lkRespLoc.fire, False)

    // FIXME: may conflict with LkRespRmt
    // release after get all lkResp and rReqDone
    when(rFire && getAllRlse && getAllLkResp && (rReqDone(rCurTxnId) || firstReqAbt)) {
      rRlseDone(rCurTxnId).set()
      when(rAbort(rCurTxnId))(io.cntTxnAbt := io.cntTxnAbt + 1) otherwise (io.cntTxnCmt := io.cntTxnCmt + 1)
    }


    io.lkRespLoc.ready := isActive(WAIT_RESP)

    WAIT_RESP.whenIsActive {
      when(io.lkRespLoc.fire) {
        // lock req
        when(io.lkRespLoc.respType === LockRespType.grant) {
          // note: ooo arrive
          // should use lkHold as wr address
          lkMemLoc.write(txnOffs + cntLkHoldLoc(curTxnId), io.lkRespLoc.payload)

          // NOTE:
          cntLkRespLoc(curTxnId) := cntLkRespLoc(curTxnId) + 1
          cntLkHoldLoc(curTxnId) := cntLkHoldLoc(curTxnId) + 1

          switch(io.lkRespLoc.lkType) {
            is(LkT.rd) (goto(LOCAL_RD_REQ))
            is(LkT.wr) (cntLkHoldWrLoc(curTxnId) := cntLkHoldWrLoc(curTxnId) + 1)
            is(LkT.raw) {
              cntLkHoldWrLoc(curTxnId) := cntLkHoldWrLoc(curTxnId) + 1
              goto(LOCAL_RD_REQ) // issue local rd req once get the lock
            }
            is(LkT.instTab) ()
          }
        }

        when(io.lkRespLoc.respType === LockRespType.abort) {
          // FIXME: rAbort set conflict
          rAbort(curTxnId) := True
          cntLkRespLoc(curTxnId) := cntLkRespLoc(curTxnId) + 1
        }

        when(io.lkRespLoc.respType === LockRespType.release) {
          cntRlseRespLoc(curTxnId) := cntRlseRespLoc(curTxnId) + 1
          //          when(cntRlseRespLoc(curTxnId) === cntLkHoldLoc(curTxnId) - 1){
          //            rRlseDone(curTxnId) := True
          //          }
        }
      }
    }

    // TODO: data path
    // FIXME: tId -> addr translation logic
//    io.axi.ar.addr := (((rLkResp.tId << rLkResp.wLen) << 6) + (rLkResp.cId << conf.wChSize)).resized
    io.axi.ar.addr := ((rLkResp.tId << 6) + (rLkResp.cId << conf.wChSize)).resized
    io.axi.ar.id := rLkResp.txnId
    io.axi.ar.len := (U(1) << rLkResp.wLen) - 1
    io.axi.ar.size := log2Up(512 / 8)
    io.axi.ar.setBurstINCR()
    io.axi.ar.valid := isActive(LOCAL_RD_REQ)

    LOCAL_RD_REQ.whenIsActive {
      when(io.axi.ar.fire)(goto(WAIT_RESP))
    }

  }

  val compLkRespRmt = new StateMachine {

    val WAIT_RESP = new State with EntryPoint
    val RMT_RD_CONSUME = new State

    val rLkResp = RegNextWhen(io.lkRespRmt, io.lkRespRmt.fire)
    val nBeat = Reg(UInt(8 bits)).init(0)
    val curTxnId = io.lkRespRmt.txnId
    val txnOffs = curTxnId << conf.wMaxTxnLen

    val rCurTxnId = RegNextWhen(curTxnId, io.lkRespRmt.fire)
    val getAllRlse = (cntRlseRespLoc(rCurTxnId) === cntLkHoldLoc(rCurTxnId)) && (cntRlseRespRmt(rCurTxnId) === cntLkHoldRmt(rCurTxnId))
    val getAllLkResp = (cntLkReqLoc(rCurTxnId) === cntLkRespLoc(rCurTxnId)) && (cntLkReqRmt(rCurTxnId) === cntLkRespRmt(rCurTxnId))

    // io.lkResp -> R -> rAbort -> R -> rReqDone
    val firstReqAbt = rAbort(rCurTxnId) && ~(io.lkReqLoc.fire && io.lkReqLoc.txnId === rCurTxnId) && ~(io.lkReqRmt.fire && io.lkReqRmt.txnId === rCurTxnId)

    val rFire = RegNext(io.lkRespRmt.fire, False)

    // release after get all lkResp and rReqDone
    when(rFire && getAllRlse && getAllLkResp && (rReqDone(rCurTxnId) || firstReqAbt)) {
      rRlseDone(rCurTxnId).set()
      when(rAbort(rCurTxnId))(io.cntTxnAbt := io.cntTxnAbt + 1) otherwise (io.cntTxnCmt := io.cntTxnCmt + 1)
    }


    io.lkRespRmt.ready := isActive(WAIT_RESP)
    WAIT_RESP.whenIsActive {

      when(io.lkRespRmt.fire) {

        when(io.lkRespRmt.respType === LockRespType.grant) {
          // note: ooo arrive
          lkMemRmt.write(txnOffs + cntLkHoldRmt(curTxnId), io.lkRespRmt.payload)

          cntLkRespRmt(curTxnId) := cntLkRespRmt(curTxnId) + 1
          cntLkHoldRmt(curTxnId) := cntLkHoldRmt(curTxnId) + 1

          switch(io.lkRespRmt.lkType) {
            is(LkT.rd) (goto(RMT_RD_CONSUME))
            is(LkT.wr) (cntLkHoldWrRmt(curTxnId) := cntLkHoldWrRmt(curTxnId) + 1)
            is(LkT.raw) {
              cntLkHoldWrRmt(curTxnId) := cntLkHoldWrRmt(curTxnId) + 1
              goto(RMT_RD_CONSUME) // issue local rd req once get the lock
            }
            is(LkT.instTab) ()
          }
        }

        when(io.lkRespRmt.respType === LockRespType.abort) {
          // FIXME: rAbort set conflict
          rAbort(curTxnId) := True
          cntLkRespRmt(curTxnId) := cntLkRespRmt(curTxnId) + 1
        }

        when(io.lkRespRmt.respType === LockRespType.release) {
          cntRlseRespRmt(curTxnId) := cntRlseRespRmt(curTxnId) + 1
        }
      }
    }

    // REMOTE_RD data come with the lkResp, consume it!
    io.rdRmt.ready := isActive(RMT_RD_CONSUME)
    RMT_RD_CONSUME.whenIsActive {
      when(io.rdRmt.fire) {
        nBeat := nBeat + 1
        when(nBeat === (U(1) << rLkResp.wLen) - 1) {
          nBeat.clearAll()
          goto(WAIT_RESP)
        }
      }
    }
  }


  /**
   * component3: axi response
   * */

  val compAxiResp = new Area {
    // rd resp
    io.axi.r.ready := True
    // write resp
    io.axi.b.ready := True

    val rAxiBFire = RegNext(io.axi.b.fire)
    val rAxiBId = RegNext(io.axi.b.id)
    when(rAxiBFire) {
      cntCmtRespLoc(rAxiBId) := cntCmtRespLoc(rAxiBId) + 1
    }
  }


  /**
   * component4: txnCommit
   * */

  val compTxnCmtLoc = new StateMachine {

    val CS_TXN = new State with EntryPoint
    val LOCAL_AW, LOCAL_W = new State

    val curTxnId = Reg(UInt(conf.wTxnId bits)).init(0)
    val txnOffs = curTxnId << conf.wMaxTxnLen

    val cmtTxn = txnWrMemLoc.readSync(txnOffs + cntCmtReqLoc(curTxnId)) //
    val rCmtTxn = RegNext(cmtTxn)

    val nBeat = Reg(UInt(8 bits)).init(0)

    val getAllLkResp = (cntLkReqLoc(curTxnId) === cntLkRespLoc(curTxnId)) && (cntLkReqRmt(curTxnId) === cntLkRespRmt(curTxnId))

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
    // io.axi.aw.addr := (((cmtTxn.tId << cmtTxn.wLen) << 6) + (cmtTxn.cId << conf.wChSize)).resized
    io.axi.aw.addr := ((cmtTxn.tId << 6) + (cmtTxn.cId << conf.wChSize)).resized
    io.axi.aw.id := curTxnId
    io.axi.aw.len := (U(1) << cmtTxn.wLen) - 1
    io.axi.aw.size := log2Up(512 / 8)
    io.axi.aw.setBurstINCR()
    io.axi.aw.valid := isActive(LOCAL_AW)

    LOCAL_AW.whenIsActive {
      when(io.axi.aw.fire) {
        goto(LOCAL_W)
      }
    }


    io.axi.w.data.setAll()
    io.axi.w.last := (nBeat === (U(1) << rCmtTxn.wLen) - 1)
    io.axi.w.valid := isActive(LOCAL_W)

    LOCAL_W.whenIsActive {

      when(io.axi.w.fire) {
        nBeat := nBeat + 1
        when(io.axi.w.last) {
          cntCmtReqLoc(curTxnId) := cntCmtReqLoc(curTxnId) + 1
          nBeat.clearAll()
          goto(CS_TXN)
        }
      }
      // io.axi.b will be tackled in component3
    }
  }


  /**
   * component5: lkRelease
   *
   * */

  val compLkRlseLoc = new StateMachine {

    val CS_TXN = new State with EntryPoint
    val LK_RLSE = new State

    val curTxnId = Reg(UInt(conf.wTxnId bits)).init(0)
    val txnOffs = curTxnId << conf.wMaxTxnLen
    val lkItem = lkMemLoc.readSync(txnOffs + cntRlseReqLoc(curTxnId))
    val getAllLkResp = (cntLkReqLoc(curTxnId) === cntLkRespLoc(curTxnId)) && (cntLkReqRmt(curTxnId) === cntLkRespRmt(curTxnId))

    CS_TXN.whenIsActive {

      /**
       * 1. get all lk resp
       * 2. rAbort || rReqDone
       * 3. (rAbort || WrLoc <= CmtRespLoc )
       * 4. send #RlseReq loc === #LkHold local
       * */
      val rlseCret = getAllLkResp && (rAbort(curTxnId) || rReqDone(curTxnId)) && (rAbort(curTxnId) || cntRlseReqWrLoc(curTxnId) <= cntCmtRespLoc(curTxnId)) && cntRlseReqLoc(curTxnId) < cntLkHoldLoc(curTxnId)
      when(rlseCret) {
        goto(LK_RLSE)
      } otherwise {
        curTxnId := curTxnId + 1
      }
    }

    lkReqRlseLoc.payload := lkItem.toLkRlseReq(rAbort(curTxnId), cntRlseReqLoc(curTxnId))

    LK_RLSE.whenIsActive {
      lkReqRlseLoc.valid := True
      when(lkReqRlseLoc.fire) {
        cntRlseReqLoc(curTxnId) := cntRlseReqLoc(curTxnId) + 1
        goto(CS_TXN)
      }
      when(lkReqRlseLoc.fire && (lkItem.lkType === LkT.wr || lkItem.lkType === LkT.raw)) {
        cntRlseReqWrLoc(curTxnId) := cntRlseReqWrLoc(curTxnId) + 1
      }
    }

  }


  val compLkRlseRmt = new StateMachine {

    val CS_TXN = new State with EntryPoint
    val RMT_LK_RLSE, RMT_WR = new State

    val curTxnId = Reg(UInt(conf.wTxnId bits)).init(0)
    val nBeat = Reg(UInt(8 bits)).init(0)

    val txnOffs = curTxnId << conf.wMaxTxnLen
    val lkItem = lkMemRmt.readSync(txnOffs + cntRlseReqRmt(curTxnId))
    val getAllLkResp = (cntLkReqLoc(curTxnId) === cntLkRespLoc(curTxnId)) && (cntLkReqRmt(curTxnId) === cntLkRespRmt(curTxnId))

    CS_TXN.whenIsActive {

      /**
       * 1. get all lk resp
       * 2. rAbort || rReqDone
       * 3. send #RlseReq loc === #LkHold local
       * */

      val rlseCret = getAllLkResp && (rAbort(curTxnId) || rReqDone(curTxnId)) && cntRlseReqRmt(curTxnId) < cntLkHoldRmt(curTxnId)

      when(rlseCret) {
        goto(RMT_LK_RLSE)
      } otherwise {
        curTxnId := curTxnId + 1
      }
    }

    lkReqRlseRmt.payload := lkItem.toLkRlseReq(rAbort(curTxnId), cntRlseReqRmt(curTxnId))

    RMT_LK_RLSE.whenIsActive {
      lkReqRlseRmt.valid := True
      when(lkReqRlseRmt.fire) {
        // NOTE: for rmt case, cntRlseReq++ after RMT_WR
        // cntRlseReqRmt(curTxnId) := cntRlseReqRmt(curTxnId) + 1

        // when wr lock & ~rAbort
        when((lkItem.lkType === LkT.wr || lkItem.lkType === LkT.raw) && ~rAbort(curTxnId)) {
          cntRlseReqWrRmt(curTxnId) := cntRlseReqWrRmt(curTxnId) + 1 // no use
          goto(RMT_WR)
        } otherwise {
          cntRlseReqRmt(curTxnId) := cntRlseReqRmt(curTxnId) + 1
          goto(CS_TXN)
        }
      }
    }

    io.wrRmt.valid := isActive(RMT_WR)
    io.wrRmt.payload.setAll()

    RMT_WR.whenIsActive {
      when(io.wrRmt.fire) {
        nBeat := nBeat + 1
        when(nBeat === (U(1) << lkItem.wLen) - 1) {
          cntRlseReqRmt(curTxnId) := cntRlseReqRmt(curTxnId) + 1
          nBeat.clearAll()
          goto(CS_TXN)
        }
      }
    }

  }

  /**
   * component6: loadTxnMem
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


    val rCmdAxiData = RegNextWhen(io.cmdAxi.r.data, io.cmdAxi.r.fire)
    val rCmdAxiFire = RegNext(io.cmdAxi.r.fire, False)
    // 512 / 64
    val cmdAxiDataSlice = rCmdAxiData.subdivideIn(8 slices)

    val rTxnMemLd = RegInit(False)
    val cntTxnWordInLine = Counter(8, rTxnMemLd)
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

    //
    CS_TXN.whenIsActive {
      //NOTE: use rlseDone as flag of empty txn slot
      when(rRlseDone(curTxnId)) {
        goto(RD_CMDAXI)
      } otherwise {
        curTxnId := curTxnId + 1
      }
    }

    // rd on cmdAxi
    io.cmdAxi.ar.valid := isActive(RD_CMDAXI)

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
      txnSt.assignFromBits(cmdAxiDataSlice(cntTxnWordInLine))
      txnMem.write(txnOffs + cntTxnWord, txnSt, rTxnMemLd)

      when(cntTxnWordInLine.willOverflow)(rTxnMemLd.clear())
      when(cntTxnWord.willOverflow) {
        // clear all cnt register
        val zero = UInt(conf.wMaxTxnLen bits).default(0)
        for (e <- Seq(cntLkReqLoc, cntLkReqRmt, cntLkRespLoc, cntLkRespRmt, cntLkHoldLoc, cntLkHoldRmt, cntLkReqWrLoc, cntLkReqWrRmt, cntLkHoldWrLoc, cntLkHoldWrRmt, cntCmtReqLoc, cntCmtReqRmt, cntCmtRespLoc, cntCmtRespRmt, cntRlseReqLoc, cntRlseReqRmt, cntRlseReqWrLoc, cntRlseReqWrRmt, cntRlseRespLoc, cntRlseRespRmt))
          e(curTxnId) := zero // why the clearAll() DOES NOT work?
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