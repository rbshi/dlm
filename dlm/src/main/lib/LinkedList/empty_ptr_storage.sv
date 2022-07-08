//-----------------------------------------------------------------------------
// Project       : fpga-hash-table
//-----------------------------------------------------------------------------
// Author        : Ivan Shevchuk (github/johan92)
//-----------------------------------------------------------------------------

module ll_empty_ptr_storage #(
  parameter A_WIDTH = 8
)(

  input                clk_i,
  input                rst_i,
  
  // interface to add empty pointers
  input  [A_WIDTH-1:0] add_empty_ptr_i,
  input                add_empty_ptr_en_i,
  
  // interface to read empty pointers,
  // if val is zero - there is no more empty pointers
  input                      next_empty_ptr_rd_ack_i,
  output logic [A_WIDTH-1:0] next_empty_ptr_o,
  output logic               next_empty_ptr_val_o

);

logic fifo_wr_en, fifo_rd_en, fifo_empty, fifo_full;
logic [A_WIDTH-1:0] fifo_buf_in, fifo_buf_out;

ll_synfifo #(
  .A_WIDTH(A_WIDTH),
  .D_WIDTH(A_WIDTH)
) u_fifo(
    .clk(clk_i),
    .rst(rst_i),
    .wr_en(fifo_wr_en),
    .rd_en(fifo_rd_en),
    .buf_in(fifo_buf_in),
    .buf_out(fifo_buf_out),
    .buf_empty(fifo_empty),
    .buf_full(fifo_full),
    .fifo_counter()
);

logic cur_state, n_state;
logic [A_WIDTH-1:0] cnt_init;
  
always_ff @( posedge clk_i or posedge rst_i )
  if(rst_i) cur_state <= 0;
  else cur_state <= n_state;

always_ff @( posedge clk_i or posedge rst_i )
  if(rst_i) cnt_init <= 0;
  else if(cnt_init<(2**A_WIDTH-1)) cnt_init <= cnt_init + 1; // ++ in initialization state

always_comb
  n_state = (cnt_init<(2**A_WIDTH-1))? 0 : 1;

always_comb
  // init state
  if(!cur_state) begin
    fifo_wr_en = 1;
    fifo_rd_en = 0;
    fifo_buf_in = cnt_init;
    next_empty_ptr_o = 0;
    next_empty_ptr_val_o = 0;
  end else begin
    // normal state
    fifo_wr_en = add_empty_ptr_en_i;
    fifo_rd_en = next_empty_ptr_rd_ack_i;
    fifo_buf_in = add_empty_ptr_i;
    next_empty_ptr_o = fifo_buf_out;
    next_empty_ptr_val_o = !fifo_empty;
  end

endmodule
