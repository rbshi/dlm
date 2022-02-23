//-----------------------------------------------------------------------------
// Project       : fpga-hash-table
//-----------------------------------------------------------------------------
// Author        : Ivan Shevchuk (github/johan92)
//-----------------------------------------------------------------------------

import linked_list::*;

interface ll_head_table_if (
  input clk
);

logic [LL_HEAD_PTR_WIDTH-1:0] wr_data_ptr;
logic                      wr_data_ptr_val;
logic                      wr_en;

modport master(
  output wr_data_ptr,
         wr_data_ptr_val,
         wr_en
);

modport slave(
  input  wr_data_ptr,
         wr_data_ptr_val,
         wr_en
);

endinterface
