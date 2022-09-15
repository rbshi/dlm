package hwsys.dlm

import spinal.core._
import spinal.lib.fsm._
import spinal.lib.fsm.StateMachine

// value of HT
case class LockEntryTSO(conf: SysConfig) extends Bundle{
  val ownerCnt = UInt(conf.wOwnerCnt bits)
  val wrReqFlag = Bool()
  val rdReqTS = UInt(conf.wTimeStamp bits)
  val wrReqTS = UInt(conf.wTimeStamp bits)

  def toUInt : UInt = {
    this.asBits.asUInt
  }
}

// data table RAM entry of HT
case class RamEntryTSO(conf: SysConfig) extends Bundle{
  val nextPtrVld = Bool()
  val nextPtr = UInt(conf.wHtTable bits)
  val ownerCnt = UInt(conf.wOwnerCnt bits)
  val wrReqFlag = Bool()
  val rdReqTS = UInt(conf.wTimeStamp bits)
  val wrReqTS = UInt(conf.wTimeStamp bits)

  val key = UInt(conf.wTId-log2Up(conf.nLtPart) bits)

  def toUInt : UInt = {
    this.asBits.asUInt
  }
}


class LockTableTSO(conf: SysConfig) extends Component {

  val io = new LockTableIO(conf, true)
  val ht = new HashTableBB(conf, conf.wTId-log2Up(conf.nLtPart), conf.wHtValNW, conf.wHtBucket, conf.wHtTable)

  ht.io.setDefault()

  val fsm = new StateMachine {

    val INSERT_TRY = new State with EntryPoint
    val INSET_RESP, DEL_CMD, DEL_RESP, LK_RESP = new State

    // stage lkReq
    val rLkReq = RegNextWhen(io.lkReq.payload, io.lkReq.fire)

    val htLkEntry = LockEntryTSO(conf)
    htLkEntry.assignFromBits(ht.io.ht_res_if.found_value.asBits) // cast the value of ht to lock_entry

    val htRamEntry = RamEntryTSO(conf)
    htRamEntry.assignFromBits(ht.io.ht_res_if.ram_data.asBits) // BUG, MSB order

    val rLkResp = Reg(LockRespType())

    // default io
    io.lkReq.setBlocked()
    io.lkResp.payload.assignSomeByName(rLkReq)
    io.lkResp.respType := rLkResp
    io.lkResp.lkWaited := False
    io.lkResp.valid := isActive(LK_RESP)

    INSERT_TRY.whenIsActive{
      val tryLkEntry = LockEntryTSO(conf)
      tryLkEntry.ownerCnt := 1
      tryLkEntry.wrReqFlag := io.lkReq.isWr
      tryLkEntry.rdReqTS := io.lkReq.isWr ? U(0) | io.lkReq.tsTxn
      tryLkEntry.wrReqTS := io.lkReq.isWr ? io.lkReq.tsTxn | U(0)
      ht.io.setCmd(io.lkReq.tId, tryLkEntry.toUInt, HTOp.ins2)
      ht.io.ht_cmd_if.arbitrationFrom(io.lkReq)
      when(io.lkReq.fire)(goto(INSET_RESP))
    }

    // HT req -> resp is in sequential
    INSET_RESP.whenIsActive {
      ht.io.update_addr := ht.io.ht_res_if.find_addr

      // RamEntry to be updated when lk grant
      val htNewRamEntry = RamEntryTSO(conf)
      htNewRamEntry.nextPtrVld := htRamEntry.nextPtrVld
      htNewRamEntry.nextPtr := htRamEntry.nextPtr
      htNewRamEntry.wrReqFlag := rLkReq.isWr && ~rLkReq.lkRelease
      htNewRamEntry.rdReqTS := (~rLkReq.isWr && ~rLkReq.lkRelease && (htLkEntry.rdReqTS <= rLkReq.tsTxn)) ? rLkReq.tsTxn | htLkEntry.rdReqTS
      htNewRamEntry.wrReqTS := (rLkReq.isWr && ~rLkReq.lkRelease) ? rLkReq.tsTxn | htLkEntry.wrReqTS
      htNewRamEntry.wrReqFlag := rLkReq.isWr && ~rLkReq.lkRelease

      htNewRamEntry.key := htRamEntry.key
      htNewRamEntry.ownerCnt := rLkReq.isWr ? htRamEntry.ownerCnt | (rLkReq.lkRelease ? (htRamEntry.ownerCnt - 1) | (htRamEntry.ownerCnt + 1))
      ht.io.update_data := htNewRamEntry.asBits.asUInt

      when(ht.io.ht_res_if.fire) {
        switch(rLkReq.lkRelease) {
          // lock request
          is(False) {
            is(HTRet.ins_exist) {
              // wr operation
              when(rLkReq.isWr) {
                when(~htLkEntry.wrReqFlag && (htLkEntry.ownerCnt === 0 || htLkEntry.rdReqTS <= rLkReq.tsTxn)) {
                  rLkResp := LockRespType.grant
                  // write back to ht data ram
                  ht.io.update_en := True
                } otherwise {
                  rLkResp := LockRespType.abort
                }
              } otherwise {
                // rd operation
                when(htLkEntry.wrReqTS > rLkReq.tsTxn)(rLkResp := LockRespType.abort) otherwise {
                  rLkResp := LockRespType.grant
                  ht.io.update_en := True
                }
              }
            }
            // no space
            is(HTRet.ins_fail) {
              rLkResp := LockRespType.abort // no wait
            }
            // insert_success
            default {
              rLkResp := LockRespType.grant
            }
            goto(LK_RESP)
          }

          // lock release, ht.io.ht_res_if.rescode must be ins_exist. 2 cases: cnt-- or del entry (cost a few cycles)
          is(True) {
            // wr release
            when(rLkReq.isWr) {
              when(htLkEntry.ownerCnt === 0) {
                goto(DEL_CMD)
              } otherwise {
                // only update the wrReqFlag
                ht.io.update_en := True
                goto(INSERT_TRY)
              }
            } otherwise {
              // rd release
              when(htLkEntry.ownerCnt === 1) {
                goto(DEL_CMD)
              } otherwise {
                ht.io.update_en := True
                goto(INSERT_TRY)
              }
            }
            rLkResp := LockRespType.release
          }
        }
      }
    }

    DEL_CMD.whenIsActive {
      ht.io.setCmd(rLkReq.tId, 0, HTOp.del)
      ht.io.ht_cmd_if.valid.set()
      when(ht.io.ht_cmd_if.fire){goto(DEL_RESP)}
    }

    DEL_RESP.whenIsActive {
      when(ht.io.ht_res_if.fire){goto(INSERT_TRY)}
    }

    LK_RESP.whenIsActive {
      when(io.lkResp.fire){goto(INSERT_TRY)}
    }
  }

}
