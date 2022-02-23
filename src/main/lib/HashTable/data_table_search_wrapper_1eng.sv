//-----------------------------------------------------------------------------
// Project       : fpga-hash-table
//-----------------------------------------------------------------------------
// Author        : Ivan Shevchuk (github/johan92)
//-----------------------------------------------------------------------------

import hash_table::*;

module data_table_search_wrapper_1eng #( 
  parameter RAM_LATENCY = 2,
  parameter A_WIDTH     = TABLE_ADDR_WIDTH
)(

  input                       clk_i,
  input                       rst_i,
  
  input  ht_pdata_t           task_i,
  input                       task_valid_i,
  output                      task_ready_o,

  // at least one task in proccess
  output logic                task_in_proccess_o, 
  
  // reading from data RAM
  input  ram_data_t           rd_data_i, 

  output logic [A_WIDTH-1:0]  rd_addr_o,
  output logic                rd_en_o,
  
  input                       result_ready_i,
  // output interface with search result
  output ht_result_t          result_o,
  output logic                result_valid_o 

);

assign task_in_proccess_o = !task_ready_o;

logic rd_data_val;
logic rd_en;
logic rd_avail = 'd1;

    rd_data_val_helper #( 
    .RAM_LATENCY                          ( RAM_LATENCY     ) 
    ) rd_data_val_helper (
    .clk_i                                ( clk_i           ),
    .rst_i                                ( rst_i           ),

    .rd_en_i                              ( rd_en_o        ),
    .rd_data_val_o                        ( rd_data_val  )

    );

    data_table_search search(
    .clk_i                                  ( clk_i             ),
    .rst_i                                  ( rst_i             ),
        
    .task_i                                 ( task_i            ),
    .task_valid_i                           ( task_valid_i     ),
    .task_ready_o                           ( task_ready_o   ),

    .rd_avail_i                             ( rd_avail       ),
    .rd_data_i                              ( rd_data_i         ),
    .rd_data_val_i                          ( rd_data_val    ),

    .rd_addr_o                              ( rd_addr_o        ),
    .rd_en_o                                ( rd_en_o          ),

    .result_o                               ( result_o         ),
    .result_valid_o                         ( result_valid_o   ),
    .result_ready_i                         ( result_ready_i   )
    );

endmodule
