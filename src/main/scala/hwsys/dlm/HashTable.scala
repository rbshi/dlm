package hwsys.dlm

import spinal.core.{UInt, _}
import spinal.core.sim._
import spinal.lib._

import scala.language.postfixOps
import scala.util.Random
import scala.math._
import hwsys.util._

import os._

// ins: if key exists, will update the value and return ins_exist; otherwise, ins_success
// ins2: if key exists, will return ins_exist but NOT update the value (try insert); Meanwhile,
// return ram_data & find_addr on ht_res_if for a follow-up quick update.

object HashTableOpCode extends SpinalEnum() {
  val sea, ins, del, ins2 = newElement()
}

object HashTableRetCode extends SpinalEnum() {
  type HashTableRetCode = UInt
  val sea_success, sea_fail, ins_success, ins_exist, ins_fail, del_success, del_fail = newElement()
}

class HashTableIO(keyWidth:Int, valWidth:Int, bucketWidth:Int, tableAddrWidth:Int) extends Bundle{

  val clk_i = in Bool()
  val rst_i = in Bool()

  val ht_cmd_if = slave Stream(new Bundle{
    val key = UInt(keyWidth bits)
    val value = UInt(valWidth bits)
    val opcode = HashTableOpCode() // opcode: OP_SEARCH, OP_INSERT, OP_DELETE
  })

  val ht_res_if = master Stream(new Bundle{

    // for insert2 function: insert_find_samekey
    val ram_data = UInt(keyWidth+valWidth+tableAddrWidth+1 bits)
    val find_addr = UInt(tableAddrWidth bits)

    val chain_state = UInt(3 bits) // NO_CHAIN, IN_HEAD, IN_MIDDLE, IN_TAIL, IN_TAIL_NO_MATCH
    val found_value = UInt(valWidth bits)
    val bucket = UInt(bucketWidth bits)
    val rescode = HashTableRetCode() // SEARCH_FOUND, SEARCH_NOT_SUCCESS_NO_ENTRY, INSERT_SUCCESS, INSERT_SUCCESS_SAME_KEY, INSERT_NOT_SUCCESS_TABLE_IS_FULL, DELETE_SUCCESS, DELETE_NOT_SUCCESS_NO_ENTRY

    // cmd
    val opcode = UInt(2 bits)
    val value = UInt(valWidth bits)
    val key = UInt(keyWidth bits)

  })

  val ht_clear_ram_run = in Bool() // head table
  val ht_clear_ram_done = out Bool()
  val dt_clear_ram_run = in Bool() // data table
  val dt_clear_ram_done = out Bool()

  // for insert2 function: insert_find_samekey
  val update_en = in Bool()
  val update_data = in UInt(keyWidth+valWidth+tableAddrWidth+1 bits)
  val update_addr = in UInt(tableAddrWidth bits)

  def setDefault() = {
    this.ht_cmd_if.valid := False
    this.ht_cmd_if.key := 0
    this.ht_cmd_if.value := 0
    this.ht_cmd_if.opcode := HashTableOpCode.sea
    // this.ht_res_if.ready := False
    this.ht_clear_ram_run := False
    this.dt_clear_ram_run := False
    this.update_en := False
    this.update_addr := 0
    this.update_data := 0
  }

  def setCmd(key:UInt, value:UInt, opcode:HashTableOpCode.E): Unit ={
    // this.ht_cmd_if.valid := True
    this.ht_cmd_if.key := key
    this.ht_cmd_if.value := value
    this.ht_cmd_if.opcode := opcode
  }

  def printCmd() = {
    if(ht_cmd_if.valid.toBoolean && ht_cmd_if.ready.toBoolean){
      println("[HT] key:" + ht_cmd_if.key.toBigInt + "\tvalue:" + ht_cmd_if.value.toBigInt + "\topcode:" + ht_cmd_if.opcode.toBigInt)
    }
  }

  def printResp() = {
    if(ht_res_if.valid.toBoolean && ht_res_if.ready.toBoolean){
      println("[HT] key:" + ht_res_if.key.toBigInt + "\tvalue:" + ht_res_if.value.toBigInt + "\topcode:" + ht_res_if.opcode.toBigInt + "\tbucket:" + ht_res_if.bucket.toBigInt + "\trescode:" + ht_res_if.rescode.toBigInt)
    }
  }

}

// parameters of blockbox is in sv package FIXME: MUST be modified manually
class hash_table_top(keyWidth:Int, valWidth:Int, bucketWidth:Int, tableAddrWidth:Int) extends BlackBox with RenameIO {

  val io = new HashTableIO(keyWidth, valWidth, bucketWidth, tableAddrWidth)
  mapCurrentClockDomain(io.clk_i, io.rst_i)

  noIoPrefix()
  addPrePopTask(renameIO)

  // dump pkg file input parameters

  val rtlDir = os.pwd/"src"/"main"/"lib"/"HashTable"
  val pkgFile =
    s"""
      |// This is an auto-generated file.
      |package hash_table;
      |
      |  parameter KEY_WIDTH        = $keyWidth;
      |  parameter VALUE_WIDTH      = $valWidth;
      |  parameter BUCKET_WIDTH     = $bucketWidth;
      |  parameter HASH_TYPE        = "dummy";
      |  parameter TABLE_ADDR_WIDTH = $tableAddrWidth;
      |  parameter HEAD_PTR_WIDTH   = TABLE_ADDR_WIDTH;
      |
      |  typedef enum logic [1:0] {
      |    OP_SEARCH,
      |    OP_INSERT,
      |    OP_DELETE,
      |    OP_INSERT2
      |  } ht_opcode_t;
      |
      |  typedef enum int unsigned {
      |    SEARCH_FOUND,
      |    SEARCH_NOT_SUCCESS_NO_ENTRY,
      |
      |    INSERT_SUCCESS,
      |    INSERT_FIND_SAME_KEY,
      |    INSERT_NOT_SUCCESS_TABLE_IS_FULL,
      |
      |    DELETE_SUCCESS,
      |    DELETE_NOT_SUCCESS_NO_ENTRY
      |  } ht_rescode_t;
      |
      |  typedef enum int unsigned {
      |    READ_NO_HEAD,
      |    KEY_MATCH,
      |    KEY_NO_MATCH_HAVE_NEXT_PTR,
      |    GOT_TAIL
      |  } ht_data_table_state_t;
      |
      |  typedef enum int unsigned {
      |    NO_CHAIN,
      |
      |    IN_HEAD,
      |    IN_MIDDLE,
      |    IN_TAIL,
      |
      |    IN_TAIL_NO_MATCH
      |  } ht_chain_state_t;
      |
      |  typedef struct packed {
      |    logic [HEAD_PTR_WIDTH-1:0] ptr;
      |    logic                      ptr_val;
      |  } head_ram_data_t;
      |
      |  typedef struct packed {
      |    logic [KEY_WIDTH-1:0]      key;
      |    logic [VALUE_WIDTH-1:0]    value;
      |    logic [HEAD_PTR_WIDTH-1:0] next_ptr;
      |    logic                      next_ptr_val;
      |  } ram_data_t;
      |
      |  typedef struct packed {
      |    logic        [KEY_WIDTH-1:0]    key;
      |    logic        [VALUE_WIDTH-1:0]  value;
      |    ht_opcode_t                     opcode;
      |  } ht_command_t;
      |
      |  // pdata - data to pipeline/proccessing
      |  typedef struct packed {
      |    ht_command_t                cmd;
      |
      |    logic  [BUCKET_WIDTH-1:0]   bucket;
      |
      |    logic  [HEAD_PTR_WIDTH-1:0] head_ptr;
      |    logic                       head_ptr_val;
      |  } ht_pdata_t;
      |
      |  typedef struct packed {
      |    ht_command_t                cmd;
      |    ht_rescode_t                rescode;
      |
      |    logic  [BUCKET_WIDTH-1:0]   bucket;
      |
      |    // valid only for opcode = OP_SEARCH
      |    logic [VALUE_WIDTH-1:0]     found_value;
      |
      |    // only for verification
      |    ht_chain_state_t            chain_state;
      |
      |    // for INSERT_FIND_SAMEKEY
      |    logic [TABLE_ADDR_WIDTH-1:0] find_addr;
      |    logic [KEY_WIDTH+VALUE_WIDTH+HEAD_PTR_WIDTH+1-1:0] ram_data;
      |
      |  } ht_result_t;
      |
      |endpackage
      |
      |""".stripMargin
  os.write.over(rtlDir/"hash_table_pkg.sv", pkgFile)

  addRTLPath("src/main/lib/HashTable/hash_table_pkg.sv")
  addRTLPath("src/main/lib/HashTable/CRC32_D32.sv")
  addRTLPath("src/main/lib/HashTable/altera_avalon_st_pipeline_base.v")
  addRTLPath("src/main/lib/HashTable/calc_hash.sv")
  addRTLPath("src/main/lib/HashTable/data_table.sv")
  addRTLPath("src/main/lib/HashTable/data_table_delete.sv")
  addRTLPath("src/main/lib/HashTable/data_table_insert.sv")
  addRTLPath("src/main/lib/HashTable/data_table_insert2.sv")
  addRTLPath("src/main/lib/HashTable/data_table_search.sv")
  addRTLPath("src/main/lib/HashTable/data_table_search_wrapper.sv")
  addRTLPath("src/main/lib/HashTable/data_table_search_wrapper_1eng.sv")
  addRTLPath("src/main/lib/HashTable/empty_ptr_storage.sv")
  addRTLPath("src/main/lib/HashTable/synfifo.v")
  addRTLPath("src/main/lib/HashTable/hash_table_top.sv")
  addRTLPath("src/main/lib/HashTable/head_table.sv")
  addRTLPath("src/main/lib/HashTable/head_table_if.sv")
  addRTLPath("src/main/lib/HashTable/ht_cmd_if.sv")
  addRTLPath("src/main/lib/HashTable/ht_delay.sv")
  addRTLPath("src/main/lib/HashTable/ht_res_if.sv")
  addRTLPath("src/main/lib/HashTable/ht_res_mux.sv")
  addRTLPath("src/main/lib/HashTable/rd_data_val_helper.sv")
  addRTLPath("src/main/lib/HashTable/true_dual_port_ram_single_clock.sv")

}

class HashTableDUT(keyWidth:Int, valWidth:Int, bucketWidth:Int, tableAddrWidth:Int) extends Component {
  val io = new HashTableIO(keyWidth, valWidth, bucketWidth, tableAddrWidth)
  val ht = new hash_table_top(keyWidth, valWidth, bucketWidth, tableAddrWidth)
  io.ht_cmd_if <> ht.io.ht_cmd_if
  io.ht_res_if <> ht.io.ht_res_if
  ht.io.ht_clear_ram_run <> io.ht_clear_ram_run
  ht.io.dt_clear_ram_run <> io.dt_clear_ram_run
  ht.io.ht_clear_ram_done <> io.ht_clear_ram_done
  ht.io.dt_clear_ram_done <> io.dt_clear_ram_done
  ht.io.update_en <> io.update_en
  ht.io.update_addr <> io.update_addr
  ht.io.update_data <> io.update_data
}
