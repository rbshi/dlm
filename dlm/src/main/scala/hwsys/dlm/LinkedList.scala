package hwsys.dlm

import spinal.core._
import spinal.core.sim._
import spinal.lib._

import hwsys.util._

object LLOp extends SpinalEnum {
  val ins, del, deq = newElement()
}

object LLRet extends SpinalEnum {
  val ins_success, ins_exist, ins_fail, del_success, del_fail, deq_success, deq_fail = newElement()
}

// io
class LinkedListIO(keyWidth:Int, tableAddrWidth:Int) extends Bundle{
  val clk_i = in Bool()
  val rst_i = in Bool()

  val ll_cmd_if = slave Stream(new Bundle{
    val key = UInt(keyWidth bits)
    val opcode = LLOp() // opcode: OP_INSERT, OP_DELETE, OP_DEQUEUE
    val head_ptr = UInt(tableAddrWidth bits)
    val head_ptr_val = Bool()
  })

  val ll_res_if = master Stream(new Bundle{
    // cmd
    val key = UInt(keyWidth bits)
    val opcode = UInt(2 bits)

    val rescode = LLRet() // INSERT_SUCCESS, INSERT_SUCCESS_SAME_KEY, INSERT_NOT_SUCCESS_TABLE_IS_FULL, DELETE_SUCCESS, DELETE_NOT_SUCCESS_NO_ENTRY, DEQUEUE_SUCCESS, DEQUEUE_NOT_SUCCESS_NO_ENTRY
    val chain_state = UInt(3 bits) // NO_CHAIN, IN_HEAD, IN_MIDDLE, IN_TAIL, IN_TAIL_NO_MATCH
  })

  val head_table_if = new Bundle{
    val wr_data_ptr = out UInt(tableAddrWidth bits)
    val wr_data_ptr_val = out Bool()
    val wr_en = out Bool()
  }

  val clear_ram_run_i = in Bool()
  val clear_ram_done_o = out Bool()

  def setDefault() = {
    this.ll_cmd_if.valid := False
    this.ll_cmd_if.key := 0
    this.ll_cmd_if.opcode := LLOp.ins
    this.ll_cmd_if.head_ptr := 0
    this.ll_cmd_if.head_ptr_val := False
    this.ll_res_if.ready := True
    this.clear_ram_run_i := False
  }

  def setCmd(key:UInt, opcode:SpinalEnumElement[LLOp.type], head_ptr:UInt, head_ptr_val:Bool): Unit = {
    ll_cmd_if.key := key
    ll_cmd_if.opcode := opcode
    ll_cmd_if.head_ptr := head_ptr
    ll_cmd_if.head_ptr_val := head_ptr_val
  }

  def printCmd() = {
    if(ll_cmd_if.valid.toBoolean && ll_cmd_if.ready.toBoolean){
      println("[LL] key:" + ll_cmd_if.key.toInt + "\topcode:" + ll_cmd_if.opcode.toBigInt +
                "\thead_ptr:" + ll_cmd_if.head_ptr.toBigInt + "\thead_ptr_val:" + ll_cmd_if.head_ptr_val.toBoolean)
    }
  }

  def printResp() = {
    if(ll_res_if.valid.toBoolean && ll_res_if.ready.toBoolean){
      println("[LL] key:" + ll_res_if.key.toInt + "\topcode:" + ll_res_if.opcode.toBigInt + "\trescode:" + ll_res_if.rescode.toBigInt)
    }
  }

}

// parameters of blockbox is in sv package
class linked_list_top(keyWidth:Int, tableAddrWidth:Int, pkgSurfix:String) extends BlackBox with RenameIO{

  val io = new LinkedListIO(keyWidth, tableAddrWidth)

  mapCurrentClockDomain(io.clk_i, io.rst_i)

  noIoPrefix()
  addPrePopTask(renameIO)

  val rtlDir = os.pwd/"generated_rtl"
  val pkgFile =
    s"""
       |// This is an auto-generated file.
       |package linked_list;
       |
       |  parameter LL_KEY_WIDTH        = $keyWidth;
       |  parameter LL_TABLE_ADDR_WIDTH = $tableAddrWidth;
       |  parameter LL_HEAD_PTR_WIDTH   = LL_TABLE_ADDR_WIDTH;
       |
       |  typedef enum {
       |    LL_OP_INSERT,
       |    LL_OP_DELETE,
       |    LL_OP_DEQ
       |  } ll_ht_opcode_t;
       |
       |  typedef enum {
       |    LL_INSERT_SUCCESS,
       |    LL_INSERT_SUCCESS_SAME_KEY,
       |    LL_INSERT_NOT_SUCCESS_TABLE_IS_FULL,
       |
       |    LL_DELETE_SUCCESS,
       |    LL_DELETE_NOT_SUCCESS_NO_ENTRY,
       |
       |    LL_DEQUEUE_SUCCESS,
       |    LL_DEQUEUE_NOT_SUCCESS_NO_ENTRY
       |  } ll_ht_rescode_t;
       |
       |  typedef enum {
       |    LL_READ_NO_HEAD,
       |    LL_KEY_MATCH,
       |    LL_KEY_NO_MATCH_HAVE_NEXT_PTR,
       |    LL_GOT_TAIL
       |  } ll_ht_data_table_state_t;
       |
       |  typedef enum {
       |    LL_NO_CHAIN,
       |
       |    LL_IN_HEAD,
       |    LL_IN_MIDDLE,
       |    LL_IN_TAIL,
       |
       |    LL_IN_TAIL_NO_MATCH
       |  } ll_ht_chain_state_t;
       |
       |  typedef struct packed {
       |    logic [LL_HEAD_PTR_WIDTH-1:0] ptr;
       |    logic                      ptr_val;
       |  } ll_head_ll_ram_data_t;
       |
       |  typedef struct packed {
       |    logic [LL_KEY_WIDTH-1:0]      key;
       |    logic [LL_HEAD_PTR_WIDTH-1:0] next_ptr;
       |    logic                      next_ptr_val;
       |  } ll_ram_data_t;
       |
       |  typedef struct packed {
       |    logic        [LL_KEY_WIDTH-1:0]    key;
       |    ll_ht_opcode_t                     opcode;
       |  } ll_ht_command_t;
       |
       |  // pdata - data to pipeline/proccessing
       |  typedef struct packed {
       |    ll_ht_command_t                cmd;
       |    logic  [LL_HEAD_PTR_WIDTH-1:0] head_ptr;
       |    logic                       head_ptr_val;
       |  } ll_ht_pdata_t;
       |
       |  typedef struct packed {
       |    ll_ht_command_t                cmd;
       |    ll_ht_rescode_t                rescode;
       |
       |    // only for verification
       |    ll_ht_chain_state_t            chain_state;
       |  } ll_ht_result_t;
       |
       |endpackage
       |
       |""".stripMargin
  os.write.over(rtlDir/s"linked_list_pkg_$pkgSurfix.sv", pkgFile)

  addRTLPath(s"generated_rtl/linked_list_pkg_$pkgSurfix.sv")
  addRTLPath("dlm/src/main/lib/LinkedList/data_table_delete.sv")
  addRTLPath("dlm/src/main/lib/LinkedList/data_table_insert.sv")
  addRTLPath("dlm/src/main/lib/LinkedList/data_table_dequeue.sv")
  addRTLPath("dlm/src/main/lib/LinkedList/empty_ptr_storage.sv")
  addRTLPath("dlm/src/main/lib/LinkedList/head_table_if.sv")
  addRTLPath("dlm/src/main/lib/LinkedList/ht_res_if.sv")
  addRTLPath("dlm/src/main/lib/LinkedList/ht_res_mux.sv")
  addRTLPath("dlm/src/main/lib/LinkedList/linked_list.sv")
  addRTLPath("dlm/src/main/lib/LinkedList/rd_data_val_helper.sv")
  addRTLPath("dlm/src/main/lib/LinkedList/true_dual_port_ram_single_clock.sv")
  addRTLPath("dlm/src/main/lib/LinkedList/syncfifo.sv")
}

// blackbox needs a wrapper before being tested
class LinkedListBB(conf: SysConfig, keyWidth:Int, tableAddrWidth:Int) extends Component {
  val io = new LinkedListIO(keyWidth, tableAddrWidth)
  val pkgSurfix = s"${conf.nTxnMan}t${conf.nNode}n${conf.nCh}c${conf.nLtPart}p"
  val ll = new linked_list_top(keyWidth, tableAddrWidth, pkgSurfix)
  io.ll_cmd_if <> ll.io.ll_cmd_if
  io.ll_res_if <> ll.io.ll_res_if
  io.head_table_if <> ll.io.head_table_if
  io.clear_ram_done_o <> ll.io.clear_ram_done_o
  // FIXME
  ll.io.clear_ram_run_i := False
}