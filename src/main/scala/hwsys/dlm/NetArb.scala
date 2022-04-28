package hwsys.dlm

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

// local node
class SendArbiter(cntTxnMan: Int)(implicit sysConf: SysConfig) extends Component {
  val io = new Bundle {
    val lkReqV = Vec(slave Stream LkReq(sysConf, false), cntTxnMan)
    val wrDataV = Vec(slave Stream Bits(512 bits), cntTxnMan)
    val sendQ = master Stream Bits(512 bits)
    // status
    val statusVld = out(Bool()).default(False)
    val nReq, nWrCmtReq, nRdGetReq = out(UInt(4 bits)).default(0)
  }

  // #txnMan <= 8
  require(cntTxnMan<=8, "Only support #txnMan <= 8 now!")

  val lkReqJoin = Stream(Bits(512 bits))
  val tmpJoin = StreamJoin.vec(io.lkReqV)

  // gather the payload of lkReqV and resize
  lkReqJoin.arbitrationFrom(tmpJoin)
  lkReqJoin.payload := tmpJoin.payload.asBits.resized

  val rWrLen = Vec(Reg(UInt(3 bits)), cntTxnMan) // maxLen = 64B << 7 = 8192 B
  val cntBeat = Reg(UInt(8 bits)).init(0)

  val mskWr, mskRdGet = Bits(cntTxnMan bits)
  for (i <- mskWr.bitsRange){
    mskWr(i) := io.lkReqV(i).lkRelease && (io.lkReqV(i).lkType===LkT.wr || io.lkReqV(i).lkType===LkT.raw) && ~io.lkReqV(i).txnAbt // NOTE: if txn not abt -> wrData
    mskRdGet(i) := ~io.lkReqV(i).lkRelease && (io.lkReqV(i).lkType===LkT.rd || io.lkReqV(i).lkType===LkT.raw)
  }

  val fsm = new StateMachine {
    val LKREQ = new State with EntryPoint
    val WRDATA = new State

    val rMskWr = Reg(Bits(cntTxnMan bits)).init(0)

    // def
    io.wrDataV.map(_.setBlocked())

    io.sendQ << lkReqJoin.continueWhen(isActive(LKREQ)) // why must have isActive??

    // status parse
    io.statusVld := isActive(LKREQ)
    io.nReq := U(cntTxnMan).resized
    io.nWrCmtReq := CountOne(mskWr).resized
    io.nRdGetReq := CountOne(mskRdGet).resized

    LKREQ.whenIsActive{

      when(io.sendQ.fire){
        rMskWr := mskWr
        (rWrLen, io.lkReqV).zipped.foreach(_ := _.wLen)
        when(mskWr.orR) (goto(WRDATA))
      }
    }

    WRDATA.whenIsActive{
      // find the first txnMan that needs wr
      val ohTxnMan = OHMasking.first(rMskWr)
      val idTxnMan = OHToUInt(ohTxnMan)
      val nBeat: UInt = U(1) << rWrLen(idTxnMan)

      val wireMskWr = cloneOf(rMskWr).setAll()

      // connect wrData to the target txnMan and cnt
      io.sendQ << io.wrDataV(idTxnMan)
      when(io.sendQ.fire){
        cntBeat := cntBeat + 1
        when(cntBeat === (nBeat-1)) {
          // clear the bit
          rMskWr(idTxnMan).clear()
          cntBeat := 0
          wireMskWr(idTxnMan) := False // one cycle earlier, to avoid over wr
          when(~((rMskWr & wireMskWr).orR))(goto(LKREQ))
        }
      }
    }

  }
}

class RecvDispatcher(cntTxnMan: Int)(implicit sysConf: SysConfig) extends Component {

  val io = new Bundle {
    val recvQ = slave Stream Bits(512 bits)
    val lkRespV = Vec(master Stream LkResp(sysConf, false), cntTxnMan)
    val rdDataV = Vec(master Stream Bits(512 bits), cntTxnMan)

    // status
    val statusVld = out(Bool()).default(False)
    val nResp, nWrCmtResp, nRdGetResp = out(UInt(4 bits)).default(0)
  }

  val cntBeat = Reg(UInt(8 bits)).init(0)

  val fsm = new StateMachine {
    val LKRESP = new State with EntryPoint
    val LKDISPATCH, RDDATA = new State

    val cntDisp = Counter(cntTxnMan)

    // cast to bit vectors
    val rLkResp = Vec(Reg(LkResp(sysConf, false)), cntTxnMan)

    val lkRespBitV = io.recvQ.payload(widthOf(rLkResp) - 1 downto 0).subdivideIn(SlicesCount(cntTxnMan))
    val lkRespV = Vec(LkResp(sysConf, false), cntTxnMan)
    (lkRespV, lkRespBitV).zipped.foreach(_.assignFromBits(_))

    val rMskRd = Reg(Bits(cntTxnMan bits)).init(0)
    val mskRd, mskRdResp, mskWrCmt = Bits(cntTxnMan bits)
    for (i <- mskRd.bitsRange) {
      // if lockReq of Rd is granted, consume the followup read data
      mskRd(i) := ~lkRespV(i).lkRelease && (lkRespV(i).lkType===LkT.rd || lkRespV(i).lkType===LkT.raw) && (lkRespV(i).respType === LockRespType.grant)
      mskRdResp(i) := ~lkRespV(i).lkRelease && (lkRespV(i).lkType===LkT.rd || lkRespV(i).lkType===LkT.raw)
      mskWrCmt(i) := lkRespV(i).lkRelease && (lkRespV(i).lkType===LkT.wr || lkRespV(i).lkType===LkT.raw) && ~lkRespV(i).txnAbt
    }

    io.recvQ.ready := isActive(LKRESP)

    io.lkRespV.map(_.setIdle())
    io.rdDataV.map(_.setIdle())

    // status parse
    io.statusVld := isActive(LKRESP)
    io.nResp := U(cntTxnMan).resized
    io.nWrCmtResp := CountOne(mskWrCmt).resized
    io.nRdGetResp := CountOne(mskRdResp).resized

    LKRESP.whenIsActive {
      when(io.recvQ.fire) {
        rMskRd := mskRd
        // cast to LkResp entry
        rLkResp := lkRespV
        goto(LKDISPATCH)
      }
    }

    LKDISPATCH.whenIsActive {
      val idTxnMan = rLkResp(cntDisp).txnManId
      io.lkRespV(idTxnMan).valid := True
      io.lkRespV(idTxnMan).payload := rLkResp(cntDisp)

      when(io.lkRespV(idTxnMan).fire) {
        cntDisp.increment()

        when(cntDisp.willOverflow) {
          switch(rMskRd.orR) {
            is(True)(goto(RDDATA))
            is(False)(goto(LKRESP))
          }
        }
      }
    }

    RDDATA.whenIsActive {

      // find the first txnMan that needs wr
      val ohTxnMan = OHMasking.first(rMskRd)
      val idTxnMan = OHToUInt(ohTxnMan)
      val nBeat: UInt = U(1) << rLkResp(idTxnMan).wLen
      val wireMskRd = cloneOf(rMskRd).setAll()

      io.rdDataV(idTxnMan) << io.recvQ

      when(io.recvQ.fire) {
        cntBeat := cntBeat + 1
        when(cntBeat === (nBeat - 1)) {
          // clear the bit
          rMskRd(idTxnMan).clear()
          wireMskRd(idTxnMan) := False // one cycle earlier, to avoid over rd
          cntBeat := 0
          when(~((rMskRd & wireMskRd).orR))(goto(LKRESP))
        }
      }
    }
  }

}

// remote node
class ReqDispatcher(cntTxnMan: Int)(implicit sysConf: SysConfig) extends Component {

  val io = new Bundle {
    val reqQ = slave Stream Bits(512 bits)
    val lkReq = master Stream LkReq(sysConf, false)
    val wrData = master Stream Bits(512 bits)
  }

  val cntBeat = Reg(UInt(8 bits)).init(0)

  val fsm = new StateMachine {
    val LKREQ = new State with EntryPoint
    val LKREQFIRE, WRDATA = new State
    
    val cntFire = Counter(cntTxnMan)

    // cast to bit vectors
    val rLkReq = Vec(Reg(LkReq(sysConf, false)), cntTxnMan)
    val lkReqBitV = io.reqQ.payload(widthOf(rLkReq) - 1 downto 0).subdivideIn(SlicesCount(cntTxnMan))
    val lkReqV = Vec(LkReq(sysConf, false), cntTxnMan)
    (lkReqV, lkReqBitV).zipped.foreach(_.assignFromBits(_))

    val rMskWr = Reg(Bits(cntTxnMan bits)).init(0)
    val MskWr = Bits(cntTxnMan bits)
    for (i <- MskWr.bitsRange) {
      // if lockRlse and not abt, consume the followup read data
      MskWr(i) := lkReqV(i).lkRelease && (lkReqV(i).lkType===LkT.wr || lkReqV(i).lkType===LkT.raw) && ~lkReqV(i).txnAbt
    }

    io.lkReq.valid := False
    io.lkReq.payload := rLkReq(cntFire)

    // io.wrData.valid := False
    // io.wrData.payload := io.reqQ.payload

    io.wrData << io.reqQ.continueWhen(isActive(WRDATA))


    LKREQ.whenIsActive {
      io.reqQ.ready := True
      when(io.reqQ.fire) {
        rMskWr := MskWr
        // cast to LkResp entry
        rLkReq := lkReqV
        goto(LKREQFIRE)
      }
    }

    LKREQFIRE.whenIsActive {
      io.lkReq.valid := True
      
      when(io.lkReq.fire) {
        cntFire.increment()
        when(cntFire.willOverflow) {
          switch(rMskWr.orR) {
            is(True)(goto(WRDATA))
            is(False)(goto(LKREQ))
          }
        }
      }
    }

    WRDATA.whenIsActive {

      // find the first txnMan that needs wr
      val ohTxnMan = OHMasking.first(rMskWr)
      val idTxnMan = OHToUInt(ohTxnMan)
      val nBeat: UInt = U(1) << rLkReq(idTxnMan).wLen
      val wireMskWr = cloneOf(rMskWr).setAll()

      io.wrData << io.reqQ

      when(io.reqQ.fire) {
        cntBeat := cntBeat + 1
        when(cntBeat === (nBeat - 1)) {
          // clear the bit
          rMskWr(idTxnMan).clear()
          wireMskWr(idTxnMan) := False // one cycle earlier, to avoid over write
          when(~((rMskWr & wireMskWr).orR))(goto(LKREQ))
          cntBeat := 0
        }
      }
    }
  }

}


// TODO: RespArbiter & RecvDispatcher should pack the lock with 8, non-related to cntTxnMan. (Here just in case the #remote_lk is not multiple of 8)
class RespArbiter(cntTxnMan: Int)(implicit sysConf: SysConfig) extends Component {
  val io = new Bundle {
    val lkResp = slave Stream LkResp(sysConf, false)
    val rdData = slave Stream Bits(512 bits)
    val respQ = master Stream Bits(512 bits)
  }

  val cntBeat = Reg(UInt(12 bits)).init(0)

  // def
  io.rdData.setBlocked()

  val fsm = new StateMachine {
    val LKRESP = new State with EntryPoint
    val RDDATA = new State

    val lkRespSlowDown = io.lkResp.slowdown(cntTxnMan).continueWhen(isActive(LKRESP))

    io.respQ.arbitrationFrom(lkRespSlowDown)
    io.respQ.payload := lkRespSlowDown.payload.asBits.resized

    LKRESP.whenIsActive {
      // record cntBeat
      val isRdRespGrant = ~io.lkResp.lkRelease && (io.lkResp.lkType===LkT.rd || io.lkResp.lkType===LkT.raw) && (io.lkResp.respType === LockRespType.grant)
      when(io.lkResp.fire && isRdRespGrant) {
        cntBeat := cntBeat + (U(1) << io.lkResp.wLen)
      }

      when(io.respQ.fire) {
        // take care of the last word
        when(cntBeat =/= 0 || isRdRespGrant) {
          goto(RDDATA)
        }
      }
    }

    RDDATA.whenIsActive {
      io.respQ << io.rdData
      when(io.respQ.fire) {
        cntBeat := cntBeat - 1
        when(cntBeat === 1){
          cntBeat := 0
          goto(LKRESP)
        }
      }
    }
  }
}

class ArbDataFlow(cntTxnMan: Int)(implicit sysConf: SysConfig) extends Component {
  val sendArb = new SendArbiter(cntTxnMan)
  val reqDisp = new ReqDispatcher(cntTxnMan)
  val respArb = new RespArbiter(cntTxnMan)
  val recvDisp = new RecvDispatcher(cntTxnMan)
}












































