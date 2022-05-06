package hwsys.dlm

import spinal.core._
import spinal.lib.fsm._
import spinal.lib.fsm.StateMachine

// value of HT
case class LockEntryBW(conf: SysConfig) extends Bundle{
  val lkMode = Bool() // sh,ex
  val ownerCnt = UInt(conf.wOwnerCnt bits)
  val waitQPtr = UInt(conf.wLlTable bits)
  val waitQPtrVld = Bool() // if the waitQ ptr valid, also used to indicate if there's lkReq in waiting queue

  def toUInt : UInt = {
    this.asBits.asUInt
  }
}

// data table RAM entry of HT
case class RamEntryBW(conf: SysConfig) extends Bundle{
  val nextPtrVld = Bool()
  val nextPtr = UInt(conf.wHtTable bits)
  val lkMode = Bool() // sh,ex
  val ownerCnt = UInt(conf.wOwnerCnt bits)
  val waitQPtr = UInt(conf.wLlTable bits)
  val waitQPtrVld = Bool() // if the waitQ ptr valid

  val key = UInt(conf.wTId-log2Up(conf.nLtPart) bits)

  def toUInt : UInt = {
    this.asBits.asUInt
  }
}


class LockTableBW(conf: SysConfig) extends Component {

  val io = new LockTableIO(conf, true)
  val ht = new HashTableDUT(conf.wTId-log2Up(conf.nLtPart), conf.wHtValBW, conf.wHtBucket, conf.wHtTable)
  val ll = new LinkedListDut(LkReq(conf, isTIdTrunc = true).getBitsWidth, conf.wLlTable)

  ht.io.setDefault()
  ll.io.setDefault()
  io.lkReq.setBlocked()

  val htFsm = new StateMachine {

    val HTINSCMD = new State with EntryPoint
    val HTINSRESP, HTDELCMD, HTDELRESP, LLPUSHCMD, LLPUSHRESP, LLPOPCMD, LLPOPRESP, LLDELCMD, LLDELRESP, LKRESPPOP, LKRESP = new State

    // stage lkReq
    val rLkReq = Reg(cloneOf(io.lkReq.payload))

    val htLkEntry = LockEntryBW(conf)
    htLkEntry.assignFromBits(ht.io.ht_res_if.found_value.asBits) // cast the value of ht to lock_entry

    val htRamEntry, htNewRamEntry = RamEntryBW(conf)
    htRamEntry.assignFromBits(ht.io.ht_res_if.ram_data.asBits)
    val rHtRamEntry = RegNextWhen(htRamEntry, ht.io.ht_res_if.fire)
    val rHtRamAddr = RegNextWhen(ht.io.update_addr, ht.io.ht_res_if.fire)

    // TODO: check
    htNewRamEntry.assignSomeByName( isActive(HTINSRESP) ?  htRamEntry | rHtRamEntry)

    val rLkResp = Reg(LockRespType())
    io.lkResp.payload.assignSomeByName(rLkReq)
    io.lkResp.respType := rLkResp
    io.lkResp.valid := isActive(LKRESP) || isActive(LKRESPPOP)

    val tryLkEntry = LockEntryBW(conf)
    tryLkEntry.ownerCnt := 1
    tryLkEntry.waitQPtr := 0
    tryLkEntry.waitQPtrVld := False
    // exclusive lock if wr/raw
    tryLkEntry.lkMode := io.lkReq.lkType===LkT.wr || io.lkReq.lkType===LkT.raw


    // try insert; do not over write if key exists; can replace `search`
    HTINSCMD.whenIsActive{
      ht.io.setCmd(io.lkReq.tId, tryLkEntry.toUInt, HTOp.ins2)
      ht.io.ht_cmd_if.arbitrationFrom(io.lkReq)
      when(io.lkReq.fire) {
        rLkReq := io.lkReq.payload
        goto(HTINSRESP)
      }
    }

    // HT req -> resp is in sequential
    HTINSRESP.whenIsActive {
      ht.io.update_addr := ht.io.ht_res_if.find_addr

      // htNewRamEntry.assignSomeByName(htRamEntry)
      htNewRamEntry.ownerCnt.allowOverride
      htNewRamEntry.ownerCnt := rLkReq.lkRelease ? (htRamEntry.ownerCnt-1) | (htRamEntry.ownerCnt+1)
      ht.io.update_data := htNewRamEntry.asBits.asUInt


      when(ht.io.ht_res_if.fire) {
        // lkGet
        when(~rLkReq.lkRelease) {
          switch(ht.io.ht_res_if.rescode) {
            // lock exist
            is(HTRet.ins_exist) {
              // conflict
              when(htLkEntry.lkMode || (rLkReq.lkType === LkT.wr || rLkReq.lkType === LkT.raw)) {
                // push waitQ -> update htRamEntry
                goto(LLPUSHCMD)
              } otherwise {
                rLkResp := LockRespType.grant // not conflict
                ht.io.update_en := True // write back to ht data ram
                goto(LKRESP)
              }
            }
            // no space in ht
            is(HTRet.ins_fail) {
              rLkResp := LockRespType.abort
              goto(LKRESP)
            }
            // insert_success
            default {
              rLkResp := LockRespType.grant
              goto(LKRESP)
            }
          }
        }

        // normal release
        when(rLkReq.lkRelease && ~rLkReq.txnTimeOut) {
          rLkResp := LockRespType.release
          // case1: cnt--; case2: cnt-- & write to htRamEntry
          when(htRamEntry.ownerCnt===1){
            // if there is lkReq in waitQ
            when(htRamEntry.waitQPtrVld) {
              goto(LKRESPPOP)
            } otherwise {
              goto(HTDELCMD) // del the lkEntry in ht
            }
          } otherwise {
            ht.io.update_en := True
            goto(LKRESP)
          }
        }

        // timeout release
        when(rLkReq.lkRelease && rLkReq.txnTimeOut) {
          rLkResp := LockRespType.release
          goto(LLDELCMD)
        }

      }
    }

    HTDELCMD.whenIsActive{
      ht.io.setCmd(rLkReq.tId, 0, HTOp.del)
      ht.io.ht_cmd_if.valid.set()
      when(ht.io.ht_cmd_if.fire){goto(HTDELRESP)}
    }

    HTDELRESP.whenIsActive{
      when(ht.io.ht_res_if.fire){goto(LKRESP)}
    }

    // LL related
    LLPUSHCMD.whenIsActive {
      ll.io.setCmd(rLkReq.asBits.asUInt, LLOp.ins, rHtRamEntry.waitQPtr, rHtRamEntry.waitQPtrVld)
      ll.io.ll_cmd_if.valid := True
      when(ll.io.ll_cmd_if.fire) (goto(LLPUSHRESP))
    }

    LLPUSHRESP.whenIsActive {
      // htNewRamEntry.assignSomeByName(rHtRamEntry)
      htNewRamEntry.waitQPtrVld.allowOverride
      htNewRamEntry.waitQPtr.allowOverride
      htNewRamEntry.waitQPtrVld := ll.io.head_table_if.wr_data_ptr_val
      htNewRamEntry.waitQPtr := ll.io.head_table_if.wr_data_ptr
      ht.io.update_data := htNewRamEntry.asBits.asUInt
      ht.io.update_addr := rHtRamAddr

      when(ll.io.ll_res_if.fire) {
        when(ll.io.ll_res_if.rescode === LLRet.ins_success) {
          ht.io.update_en := ll.io.head_table_if.wr_en  // update htRamEntry
          goto(HTINSCMD) // no lkResp
        } otherwise {
          // if ll.ins failed (not enough space)
          rLkResp := LockRespType.abort
          goto(LKRESP)
        }
      }
    }

    LLPOPCMD.whenIsActive {
      ll.io.setCmd(rLkReq.asBits.asUInt, LLOp.deq, rHtRamEntry.waitQPtr, rHtRamEntry.waitQPtrVld)
      ll.io.ll_cmd_if.valid := True
      when(ll.io.ll_cmd_if.fire) (goto(LLPOPRESP))
    }

    LLPOPRESP.whenIsActive {
      val popLkReq = cloneOf(io.lkReq.payload)
      popLkReq.assignFromBits(ll.io.ll_res_if.key.asBits)
      // htNewRamEntry.assignSomeByName(rHtRamEntry)
      htNewRamEntry.lkMode.allowOverride
      htNewRamEntry.waitQPtrVld.allowOverride
      htNewRamEntry.waitQPtr.allowOverride
      htNewRamEntry.lkMode := popLkReq.lkType === LkT.wr || popLkReq.lkType === LkT.raw
      htNewRamEntry.waitQPtrVld := ll.io.head_table_if.wr_data_ptr_val
      htNewRamEntry.waitQPtr := ll.io.head_table_if.wr_data_ptr

      ht.io.update_data := htNewRamEntry.asBits.asUInt
      ht.io.update_addr := rHtRamAddr

      when(ll.io.ll_res_if.fire) {
        // res_if must be success
        rLkReq := popLkReq
        ht.io.update_en := ll.io.head_table_if.wr_en  // update htRamEntry
        // lkResp
        rLkResp := LockRespType.grant
        goto(LKRESP)
      }
    }

    LLDELCMD.whenIsActive {
      val oriLkReq = cloneOf(rLkReq)
      oriLkReq.assignSomeByName(rLkReq)
      Seq(oriLkReq.lkRelease, oriLkReq.txnTimeOut, oriLkReq.txnAbt).foreach { i =>
        i.allowOverride
        i := False
      }

      ll.io.setCmd(oriLkReq.asBits.asUInt, LLOp.del, rHtRamEntry.waitQPtr, rHtRamEntry.waitQPtrVld)
      ll.io.ll_cmd_if.valid := True
      when(ll.io.ll_cmd_if.fire) (goto(LLDELRESP))
    }

    LLDELRESP.whenIsActive {
      // htNewRamEntry.assignSomeByName(rHtRamEntry)
      htNewRamEntry.waitQPtrVld.allowOverride
      htNewRamEntry.waitQPtr.allowOverride
      htNewRamEntry.waitQPtrVld := ll.io.head_table_if.wr_data_ptr_val
      htNewRamEntry.waitQPtr := ll.io.head_table_if.wr_data_ptr
      ht.io.update_data := htNewRamEntry.asBits.asUInt
      ht.io.update_addr := rHtRamAddr

      when(ll.io.ll_res_if.fire) {
        // case: LLDEL success
        // update htRamEntry
        ht.io.update_en := ll.io.head_table_if.wr_en
        // lkResp
        rLkResp := LockRespType.release
        goto(LKRESP)

        // TODO: LLDEL fail (already poped to current lock)
      }
    }

    LKRESPPOP.whenIsActive {
      when(io.lkResp.fire){goto(LLPOPCMD)}
    }

    LKRESP.whenIsActive {
      when(io.lkResp.fire){goto(HTINSCMD)}
    }
  }

}

