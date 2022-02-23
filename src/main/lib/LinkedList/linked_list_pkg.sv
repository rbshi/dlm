//-----------------------------------------------------------------------------
// Project       : fpga-hash-table
//-----------------------------------------------------------------------------
// Author        : Ivan Shevchuk (github/johan92)
//-----------------------------------------------------------------------------

package linked_list;
  
  parameter LL_KEY_WIDTH        = 9;
  parameter LL_TABLE_ADDR_WIDTH = 10;
  parameter LL_HEAD_PTR_WIDTH   = LL_TABLE_ADDR_WIDTH;

  typedef enum {
    LL_OP_INSERT,
    LL_OP_DELETE,
    LL_OP_DEQ
  } ll_ht_opcode_t;

  typedef enum {
    LL_INSERT_SUCCESS,
    LL_INSERT_SUCCESS_SAME_KEY, 
    LL_INSERT_NOT_SUCCESS_TABLE_IS_FULL,

    LL_DELETE_SUCCESS,
    LL_DELETE_NOT_SUCCESS_NO_ENTRY, 

    LL_DEQUEUE_SUCCESS,
    LL_DEQUEUE_NOT_SUCCESS_NO_ENTRY
  } ll_ht_rescode_t;
  
  typedef enum {
    LL_READ_NO_HEAD,
    LL_KEY_MATCH,
    LL_KEY_NO_MATCH_HAVE_NEXT_PTR,
    LL_GOT_TAIL
  } ll_ht_data_table_state_t;
  
  typedef enum {
    LL_NO_CHAIN,

    LL_IN_HEAD,
    LL_IN_MIDDLE,
    LL_IN_TAIL,

    LL_IN_TAIL_NO_MATCH
  } ll_ht_chain_state_t;

  typedef struct packed {
    logic [LL_HEAD_PTR_WIDTH-1:0] ptr;
    logic                      ptr_val;
  } ll_head_ll_ram_data_t;

  typedef struct packed {
    logic [LL_KEY_WIDTH-1:0]      key;
    logic [LL_HEAD_PTR_WIDTH-1:0] next_ptr;
    logic                      next_ptr_val;
  } ll_ram_data_t; 
  
  typedef struct packed {
    logic        [LL_KEY_WIDTH-1:0]    key;
    ll_ht_opcode_t                     opcode;
  } ll_ht_command_t;
  
  // pdata - data to pipeline/proccessing
  typedef struct packed {
    ll_ht_command_t                cmd;
    logic  [LL_HEAD_PTR_WIDTH-1:0] head_ptr;
    logic                       head_ptr_val;
  } ll_ht_pdata_t;

  typedef struct packed {
    ll_ht_command_t                cmd;
    ll_ht_rescode_t                rescode;
    
    // only for verification
    ll_ht_chain_state_t            chain_state;
  } ll_ht_result_t;

endpackage
