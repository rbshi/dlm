//-----------------------------------------------------------------------------
// Project       : fpga-hash-table
//-----------------------------------------------------------------------------
// Author        : Ivan Shevchuk (github/johan92)
//-----------------------------------------------------------------------------

import linked_list::*;

interface ll_ht_res_if( 
  input clk 
);

ll_ht_result_t                 result;
logic                       valid;
logic                       ready;

modport master(
  output result,
  output valid,
  input  ready
);

modport slave(
  input  result,
  input  valid,
  output ready
);

endinterface
