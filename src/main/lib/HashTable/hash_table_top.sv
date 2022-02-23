//-----------------------------------------------------------------------------
// Project       : fpga-hash-table
//-----------------------------------------------------------------------------
// Author        : Ivan Shevchuk (github/johan92)
//-----------------------------------------------------------------------------

import hash_table::*;

module hash_table_top( 

  input                    clk_i,
  input                    rst_i,

  input               ht_cmd_if_valid,
  output              ht_cmd_if_ready,
  input      [KEY_WIDTH-1:0]   ht_cmd_if_key,
  input      [VALUE_WIDTH-1:0]   ht_cmd_if_value,
  input      [1:0]    ht_cmd_if_opcode,

  output              ht_res_if_valid,
  input               ht_res_if_ready,
  output     [KEY_WIDTH-1:0]   ht_res_if_key,
  output     [VALUE_WIDTH-1:0]   ht_res_if_value,
  output     [1:0]    ht_res_if_opcode,
  output     [2:0]    ht_res_if_rescode,
  output     [BUCKET_WIDTH-1:0]    ht_res_if_bucket,
  output     [VALUE_WIDTH-1:0]   ht_res_if_found_value,
  output     [2:0]    ht_res_if_chain_state,

  output  [TABLE_ADDR_WIDTH-1:0] ht_res_if_find_addr,
  output  [KEY_WIDTH+VALUE_WIDTH+HEAD_PTR_WIDTH+1-1:0] ht_res_if_ram_data,


  input      ht_clear_ram_run,
  output logic     ht_clear_ram_done,
  input      dt_clear_ram_run,
  output logic     dt_clear_ram_done,

  input update_en,
  input [HEAD_PTR_WIDTH-1:0] update_addr,
  input [KEY_WIDTH+VALUE_WIDTH+HEAD_PTR_WIDTH+1-1:0] update_data

);

  
  ht_cmd_if          ht_cmd_in(.clk (clk_i));
  ht_res_if          ht_res_out(.clk (clk_i));

  assign ht_cmd_in.valid = ht_cmd_if_valid;
  assign ht_cmd_in.cmd.key = ht_cmd_if_key;
  assign ht_cmd_in.cmd.value = ht_cmd_if_value;
  assign ht_cmd_in.cmd.opcode = ht_cmd_if_opcode;
  assign ht_cmd_if_ready = ht_cmd_in.ready;

  assign ht_res_out.ready = ht_res_if_ready;
  assign ht_res_if_valid = ht_res_out.valid;
  assign ht_res_if_key = ht_res_out.result.cmd.key;
  assign ht_res_if_value = ht_res_out.result.cmd.value;
  assign ht_res_if_opcode = ht_res_out.result.cmd.opcode;
  assign ht_res_if_rescode = ht_res_out.result.rescode;
  assign ht_res_if_bucket = ht_res_out.result.bucket;
  assign ht_res_if_found_value = ht_res_out.result.found_value;
  assign ht_res_if_chain_state = ht_res_out.result.chain_state;

  assign ht_res_if_find_addr = ht_res_out.result.find_addr;
  assign ht_res_if_ram_data = ht_res_out.result.ram_data;


head_table_if head_table_if(
  .clk            ( clk_i            )
);

ht_pdata_t  pdata_in;
logic       pdata_in_valid;
logic       pdata_in_ready;

ht_pdata_t  pdata_calc_hash; 
logic       pdata_calc_hash_valid;
logic       pdata_calc_hash_ready;

ht_pdata_t  pdata_head_table; 
logic       pdata_head_table_valid;
logic       pdata_head_table_ready;

always_comb
  begin
    pdata_in     = '0;

    pdata_in.cmd = ht_cmd_in.cmd;
  end

assign pdata_in_valid  = ht_cmd_in.valid;
assign ht_cmd_in.ready = pdata_in_ready;

calc_hash calc_hash (
  .clk_i                                  ( clk_i                 ),
  .rst_i                                  ( rst_i                 ),

  .pdata_in_i                             ( pdata_in              ),
  .pdata_in_valid_i                       ( pdata_in_valid        ),
  .pdata_in_ready_o                       ( pdata_in_ready        ),

  .pdata_out_o                            ( pdata_calc_hash       ),
  .pdata_out_valid_o                      ( pdata_calc_hash_valid ),
  .pdata_out_ready_i                      ( pdata_calc_hash_ready )
);

head_table h_tbl (
  .clk_i                                  ( clk_i                      ),
  .rst_i                                  ( rst_i                      ),

  .pdata_in_i                             ( pdata_calc_hash            ),
  .pdata_in_valid_i                       ( pdata_calc_hash_valid      ),
  .pdata_in_ready_o                       ( pdata_calc_hash_ready      ),

  .pdata_out_o                            ( pdata_head_table           ),
  .pdata_out_valid_o                      ( pdata_head_table_valid     ),
  .pdata_out_ready_i                      ( pdata_head_table_ready     ),
    
  .head_table_if                          ( head_table_if              ),

  .clear_ram_run_i                        ( ht_clear_ram_run   ),
  .clear_ram_done_o                       ( ht_clear_ram_done  )

);

data_table d_tbl (
  .clk_i                                  ( clk_i                   ),
  .rst_i                                  ( rst_i                   ),

  .pdata_in_i                             ( pdata_head_table        ),
  .pdata_in_valid_i                       ( pdata_head_table_valid  ),
  .pdata_in_ready_o                       ( pdata_head_table_ready  ),

  .ht_res_out                             ( ht_res_out              ),
    
  .head_table_if                          ( head_table_if           ),

  // interface to clear [fill with zero] all ram content
  .clear_ram_run_i                        ( dt_clear_ram_run   ),
  .clear_ram_done_o                       ( dt_clear_ram_done  ),

  .update_en                              (update_en),
  .update_addr                            (update_addr),
  .update_data                            (update_data)
);

endmodule
