package hwsys.dlm

import spinal.core._
import spinal.lib.fsm._
import spinal.lib.fsm.StateMachine

object LockRespType extends SpinalEnum{
  val grant, abort, waiting, release = newElement()
}

// value of ht
case class LockEntry(conf: SysConfig) extends Bundle{
  val lock_status = Bool() // sh,ex
  val owner_cnt = UInt(conf.wOwnerCnt bits)

  def toUInt : UInt = {
    this.asBits.asUInt
  }
}

case class RamEntry(conf: SysConfig) extends Bundle{

  val net_ptr_val = Bool()
  val next_ptr = UInt(conf.wHtTable bits)
  val lock_status = Bool() // sh,ex
  val owner_cnt = UInt(conf.wOwnerCnt bits)
  val key = UInt(conf.wTId-log2Up(conf.nLtPart) bits)

  def toUInt : UInt = {
    this.asBits.asUInt
  }
}


class LockTableNW(conf: SysConfig) extends Component {

  val io = new LockTableIO(conf, true)
  val ht = new HashTableDUT(conf.wTId-log2Up(conf.nLtPart), conf.wHtValNW, conf.wHtBucket, conf.wHtTable)

  ht.io.setDefault()

  val fsm = new StateMachine {

    val INSERT_TRY = new State with EntryPoint
    val INSET_RESP, DEL_CMD, DEL_RESP, LK_RESP = new State

    // stage lkReq
    val rLkReq = RegNextWhen(io.lkReq.payload, io.lkReq.fire)

    val htLkEntry = LockEntry(conf)
    htLkEntry.assignFromBits(ht.io.ht_res_if.found_value.asBits) // cast the value of ht to lock_entry

    val htRamEntry = RamEntry(conf)
    htRamEntry.assignFromBits(ht.io.ht_res_if.ram_data.asBits) // BUG, MSB order

    val rLkResp = Reg(LockRespType())

    // ht.io.ht_res_if.freeRun()
    io.lkReq.setBlocked()

    io.lkResp.payload.assignSomeByName(rLkReq)
    io.lkResp.respType := rLkResp
    io.lkResp.valid := isActive(LK_RESP)

    INSERT_TRY.whenIsActive{
      val tryLkEntry = LockEntry(conf)
      tryLkEntry.owner_cnt := 1
      // exclusive lock if wr/raw
      tryLkEntry.lock_status := io.lkReq.lkType===LkT.wr || io.lkReq.lkType===LkT.raw
      ht.io.setCmd(io.lkReq.tId,tryLkEntry.toUInt, HTOp.ins2)
      ht.io.ht_cmd_if.arbitrationFrom(io.lkReq)
      when(io.lkReq.fire)(goto(INSET_RESP))
    }

    // HT req -> resp is in sequential
    INSET_RESP.whenIsActive {
      ht.io.update_addr := ht.io.ht_res_if.find_addr
      val htNewRamEntry = RamEntry(conf)
      htNewRamEntry.net_ptr_val := htRamEntry.net_ptr_val
      htNewRamEntry.next_ptr := htRamEntry.next_ptr
      htNewRamEntry.lock_status := htRamEntry.lock_status
      htNewRamEntry.key := htRamEntry.key
      htNewRamEntry.owner_cnt := rLkReq.lkRelease ? (htRamEntry.owner_cnt-1) | (htRamEntry.owner_cnt+1)
      ht.io.update_data := htNewRamEntry.asBits.asUInt

      when(ht.io.ht_res_if.fire) {
        switch(rLkReq.lkRelease){
          is(False) {
            switch(ht.io.ht_res_if.rescode) {
              is(HTRet.ins_exist) {
                // lock exist
                when(htLkEntry.lock_status || (rLkReq.lkType === LkT.wr || rLkReq.lkType === LkT.raw)) {
                  rLkResp := LockRespType.abort
                } otherwise {
                  rLkResp := LockRespType.grant
                  // write back to ht data ram
                  ht.io.update_en := True
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
            }
            goto(LK_RESP)
          }

          is(True){
            // lock release, ht.io.ht_res_if.rescode must be ins_exist. 2 cases: cnt-- or del entry (cost a few cycles)
            when(htRamEntry.owner_cnt===1){
              // ht must be ready, del the entry: BUG
              goto(DEL_CMD)
            } otherwise {
              ht.io.update_en := True
              goto(LK_RESP)
            }
            rLkResp := LockRespType.release
          }
        }
      }
    }

    DEL_CMD.whenIsActive{
      ht.io.setCmd(rLkReq.tId, 0, HTOp.del)
      ht.io.ht_cmd_if.valid.set()
      when(ht.io.ht_cmd_if.fire){goto(DEL_RESP)}
    }

    DEL_RESP.whenIsActive{
      when(ht.io.ht_res_if.fire){goto(LK_RESP)}
    }

    LK_RESP.whenIsActive{
      when(io.lkResp.fire){goto(INSERT_TRY)}
    }
  }

}
