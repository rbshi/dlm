package hwsys.dlm

import spinal.core._
import spinal.lib.fsm._
import spinal.lib.fsm.StateMachine

object LockRespType extends SpinalEnum{
  val grant, abort, waiting, release = newElement()
}

// value of ht
case class LockEntry(conf: SysConfig) extends Bundle{
  val lock_status = Bool() // sh, ex
  val owner_cnt = UInt(conf.wOwnerCnt bits)

  def toUInt : UInt = {
    this.asBits.asUInt
  }
}

case class RamEntry(conf: SysConfig) extends Bundle{

  val net_ptr_val = Bool()
  val next_ptr = UInt(conf.wHtTable-log2Up(conf.nLtPart) bits)
  val owner_cnt = UInt(conf.wOwnerCnt bits)
  val lock_status = Bool() // sh, ex
  val key = UInt(conf.wTId-log2Up(conf.nLtPart) bits)

  def toUInt : UInt = {
    this.asBits.asUInt
  }
}

class LockTable(conf: SysConfig) extends Component {

  val io = new LockTableIO(conf, true)
  val ht = new HashTableDUT(conf.wTId-log2Up(conf.nLtPart), conf.wHtValNW, conf.wHtBucket, conf.wHtTable-log2Up(conf.nLtPart))

  ht.io.setDefault()

  val fsm = new StateMachine {

    val INSERT_TRY = new State with EntryPoint
    val INSET_RESP, DEL_CMD, DEL_RESP, LK_RESP = new State

    // stage lock_req
    val req = RegNextWhen(io.lkReq.payload, io.lkReq.fire)
    // stage ht out
    val ht_lock_entry_cast = LockEntry(conf)
    ht_lock_entry_cast.assignFromBits(ht.io.ht_res_if.found_value.asBits) // wire: cast the value of ht to lock_entry

    val ht_ram_entry_cast = RamEntry(conf)
    ht_ram_entry_cast.assignFromBits(ht.io.ht_res_if.ram_data.asBits) // BUG, MSB order

    val r_lock_resp = Reg(LockRespType())

    ht.io.ht_res_if.ready := True
    io.lkReq.ready := False

    INSERT_TRY.whenIsActive{
      val try_onwer_cnt = UInt(conf.wOwnerCnt bits)
      try_onwer_cnt := 1
      io.lkReq.ready := ht.io.ht_cmd_if.ready
      when(io.lkReq.valid){
        ht.io.sendCmd(io.lkReq.tId, (io.lkReq.lkType ## try_onwer_cnt).asUInt, HashTableOpCode.ins2)
      }

      when(io.lkReq.fire){
        goto(INSET_RESP)
      }
    }

    // HT req -> resp is in sequential
    INSET_RESP.whenIsActive {
      ht.io.update_addr := ht.io.ht_res_if.find_addr
      when(ht.io.ht_res_if.fire) {

        switch(req.lkRelease){
          is(False) {
            ht.io.update_data := (ht_ram_entry_cast.key ## req.lkType ## (ht_ram_entry_cast.owner_cnt+1) ## ht_ram_entry_cast.next_ptr ## ht_ram_entry_cast.net_ptr_val).asUInt

            switch(ht.io.ht_res_if.rescode) {
              is(HashTableRetCode.ins_exist) {
                // lock exist
                when((!req.lkUpgrade && (ht_lock_entry_cast.lock_status | req.lkType)) || (req.lkUpgrade && ht_lock_entry_cast.owner_cnt > 1)) {
                  r_lock_resp := LockRespType.abort // no wait
                  goto(LK_RESP)
                } otherwise {
                  r_lock_resp := LockRespType.grant
                  // write back to ht data ram
                  ht.io.update_en := True
                  goto(LK_RESP)
                }
              }
              // no space
              is(HashTableRetCode.ins_fail) {
                r_lock_resp := LockRespType.abort // no wait
                goto(LK_RESP)
              }
              // insert_success
              default {
                r_lock_resp := LockRespType.grant
                goto(LK_RESP)
              }
            }
          }

          is(True){
            ht.io.update_data := (ht_ram_entry_cast.key ## req.lkType ## (ht_ram_entry_cast.owner_cnt-1) ## ht_ram_entry_cast.next_ptr ## ht_ram_entry_cast.net_ptr_val).asUInt

            // lock release, ht.io.ht_res_if.rescode must be ins_exist. 2 cases: cnt-- or del entry (cost a few cycles)
            when(ht_ram_entry_cast.owner_cnt===1){
              // ht must be ready, del the entry: BUG
              goto(DEL_CMD)
            } otherwise {
              ht.io.update_en := True
              goto(LK_RESP)
            }
            r_lock_resp := LockRespType.release
          }
        }

      }
    }

    DEL_CMD.whenIsActive{
      ht.io.sendCmd(req.tId, 0, HashTableOpCode.del)
      when(ht.io.ht_cmd_if.fire){goto(DEL_RESP)}
    }

    DEL_RESP.whenIsActive{
      when(ht.io.ht_res_if.fire){goto(LK_RESP)}
    }

    io.lkResp.payload.assignSomeByName(req)
    io.lkResp.respType := r_lock_resp
    io.lkResp.valid := isActive(LK_RESP)

    LK_RESP.whenIsActive{
      when(io.lkResp.fire){goto(INSERT_TRY)}
    }
  }

}
