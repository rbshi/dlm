//-----------------------------------------------------------------------------
// Project       : fpga-hash-table
//-----------------------------------------------------------------------------
// Author        : Ivan Shevchuk (github/johan92)
//-----------------------------------------------------------------------------

import linked_list::*;

module linked_list_top(
  input                                clk_i,
  input                                rst_i,


  input ll_cmd_if_valid,
  output logic ll_cmd_if_ready,
  input [LL_KEY_WIDTH-1:0] ll_cmd_if_key,
  input [2-1:0] ll_cmd_if_opcode,
  input [LL_HEAD_PTR_WIDTH-1:0] ll_cmd_if_head_ptr,
  input ll_cmd_if_head_ptr_val,
  
  input ll_res_if_ready,
  output logic ll_res_if_valid,
  output [LL_KEY_WIDTH-1:0] ll_res_if_key,
  output logic [2-1:0] ll_res_if_opcode,
  output [2:0] ll_res_if_rescode,
  output [2:0] ll_res_if_chain_state,

  output [LL_HEAD_PTR_WIDTH-1:0] head_table_if_wr_data_ptr,
  output logic head_table_if_wr_data_ptr_val,
  output logic head_table_if_wr_en,

  // interface to clear [fill with zero] all ram content
  input                                clear_ram_run_i,
  output logic                         clear_ram_done_o

);
  
  ll_ht_pdata_t         ll_cmd_if;
  ll_ht_res_if          ll_res_if(.clk (clk_i));
  ll_head_table_if      ll_head_table_if(.clk (clk_i));
  ll_head_table_if      ll_head_table_if_d(.clk (clk_i));

  assign ll_cmd_if.cmd.key = ll_cmd_if_key;
  assign ll_cmd_if.cmd.opcode = ll_ht_opcode_t'(ll_cmd_if_opcode);
  assign ll_cmd_if.head_ptr = ll_cmd_if_head_ptr;
  assign ll_cmd_if.head_ptr_val = ll_cmd_if_head_ptr_val;


  assign ll_res_if.ready = ll_res_if_ready;
  assign ll_res_if_valid = ll_res_if.valid;
  assign ll_res_if_key = ll_res_if.result.cmd.key;
  assign ll_res_if_opcode = ll_res_if.result.cmd.opcode;
  assign ll_res_if_rescode = ll_res_if.result.rescode;
  assign ll_res_if_chain_state = ll_res_if.result.chain_state;

  assign head_table_if_wr_data_ptr = ll_head_table_if_d.wr_data_ptr;
  assign head_table_if_wr_data_ptr_val = ll_head_table_if_d.wr_data_ptr_val;
  assign head_table_if_wr_en = ll_head_table_if_d.wr_en;


// delay the ll_head_table_if 1 clk to keep it synchronized with ll_res_if
always_ff @( posedge clk_i or posedge rst_i ) begin
  if( rst_i ) begin
    ll_head_table_if_d.wr_data_ptr <= '0;
    ll_head_table_if_d.wr_data_ptr_val <= '0;
    ll_head_table_if_d.wr_en <= '0;
  end else begin
    ll_head_table_if_d.wr_data_ptr <= ll_head_table_if.wr_data_ptr;
    ll_head_table_if_d.wr_data_ptr_val <= ll_head_table_if.wr_data_ptr_val;
    ll_head_table_if_d.wr_en <= ll_head_table_if.wr_en;
  end
end


localparam D_WIDTH     = $bits( ll_ram_data_t );
localparam A_WIDTH     = LL_HEAD_PTR_WIDTH;
localparam RAM_LATENCY = 2;

localparam INSERT_ = 0;
localparam DELETE_ = 1;
localparam DEQUEUE_ = 2;

localparam DIR_CNT = 3; // INSERT + DELETE + DEQUEUE
localparam DIR_CNT_WIDTH = $clog2( DIR_CNT );

ll_ram_data_t                ram_rd_data;

logic      [A_WIDTH-1:0]  ram_rd_addr;
logic                     ram_rd_en;

logic      [A_WIDTH-1:0]  ram_wr_addr;
ll_ram_data_t                ram_wr_data;
logic                     ram_wr_en;

logic       [A_WIDTH-1:0] rd_addr_w [DIR_CNT-1:0];
logic                     rd_en_w   [DIR_CNT-1:0];

logic       [A_WIDTH-1:0] wr_addr_w [DIR_CNT-1:0];
ll_ram_data_t                wr_data_w [DIR_CNT-1:0];
logic                     wr_en_w   [DIR_CNT-1:0];


logic       [A_WIDTH-1:0] empty_addr;
logic                     empty_addr_val;
logic                     empty_addr_rd_ack;

logic       [A_WIDTH-1:0] add_empty_ptr [DIR_CNT-1:0];
logic                     add_empty_ptr_en [DIR_CNT-1:0];

logic       [A_WIDTH-1:0] add_empty_ptr_o;
logic                     add_empty_ptr_en_o;

ll_ht_pdata_t           task_w;
logic                task_valid       [DIR_CNT-1:0];
logic                task_ready       [DIR_CNT-1:0];
logic                task_proccessing [DIR_CNT-1:0];


ll_ht_res_if ht_eng_res[DIR_CNT-1:0]( 
  .clk ( clk_i )
);

ll_head_table_if head_table_insert_if( 
  .clk( clk_i )
);

ll_head_table_if head_table_delete_if( 
  .clk( clk_i )
);

ll_head_table_if head_table_dequeue_if( 
  .clk( clk_i )
);


ll_data_table_insert #(
  .RAM_LATENCY                            ( RAM_LATENCY                 )
) ins_eng (
  .clk_i                                  ( clk_i                       ),
  .rst_i                                  ( rst_i                       ),
    
  .task_i                                 ( task_w                      ),
  .task_valid_i                           ( task_valid       [INSERT_]  ),
  .task_ready_o                           ( task_ready       [INSERT_]  ),
    
    // to data RAM
  .rd_data_i                              ( ram_rd_data                 ),
  .rd_addr_o                              ( rd_addr_w        [INSERT_]  ),
  .rd_en_o                                ( rd_en_w          [INSERT_]  ),

  .wr_addr_o                              ( wr_addr_w        [INSERT_]  ),
  .wr_data_o                              ( wr_data_w        [INSERT_]  ),
  .wr_en_o                                ( wr_en_w          [INSERT_]  ),
    
    // to empty pointer storage
  .empty_addr_i                           ( empty_addr        ),
  .empty_addr_val_i                       ( empty_addr_val    ),
  .empty_addr_rd_ack_o                    ( empty_addr_rd_ack ),

  .ll_head_table_if                          ( head_table_insert_if         ),

    // output interface with result
  .result_o                               ( ht_eng_res[INSERT_].result ),
  .result_valid_o                         ( ht_eng_res[INSERT_].valid  ),
  .result_ready_i                         ( ht_eng_res[INSERT_].ready  )
);

ll_data_table_delete #(
  .RAM_LATENCY                            ( RAM_LATENCY                     )
) del_eng ( 
  .clk_i                                  ( clk_i                           ),
  .rst_i                                  ( rst_i                           ),
    
  .task_i                                 ( task_w                          ),
  .task_valid_i                           ( task_valid       [DELETE_]      ),
  .task_ready_o                           ( task_ready       [DELETE_]      ),

    // to data RAM                                            
  .rd_data_i                              ( ram_rd_data                     ),
  .rd_addr_o                              ( rd_addr_w        [DELETE_]      ),
  .rd_en_o                                ( rd_en_w          [DELETE_]      ),
  
  .wr_addr_o                              ( wr_addr_w        [DELETE_]      ),
  .wr_data_o                              ( wr_data_w        [DELETE_]      ),
  .wr_en_o                                ( wr_en_w          [DELETE_]      ),
    
    // to empty pointer storage
  .add_empty_ptr_o                        ( add_empty_ptr    [DELETE_]      ),
  .add_empty_ptr_en_o                     ( add_empty_ptr_en [DELETE_]      ),

  .ll_head_table_if                          ( head_table_delete_if            ),

    // output interface with search result
  .result_o                               ( ht_eng_res[DELETE_].result ),
  .result_valid_o                         ( ht_eng_res[DELETE_].valid  ),
  .result_ready_i                         ( ht_eng_res[DELETE_].ready  )
);


ll_data_table_dequeue #(
  .RAM_LATENCY                            ( RAM_LATENCY                     )
) deq_eng ( 
  .clk_i                                  ( clk_i                           ),
  .rst_i                                  ( rst_i                           ),
    
  .task_i                                 ( task_w                          ),
  .task_valid_i                           ( task_valid       [DEQUEUE_]      ),
  .task_ready_o                           ( task_ready       [DEQUEUE_]      ),

    // to data RAM                                            
  .rd_data_i                              ( ram_rd_data                     ),
  .rd_addr_o                              ( rd_addr_w        [DEQUEUE_]      ),
  .rd_en_o                                ( rd_en_w          [DEQUEUE_]      ),
  
  .wr_addr_o                              ( wr_addr_w        [DEQUEUE_]      ),
  .wr_data_o                              ( wr_data_w        [DEQUEUE_]      ),
  .wr_en_o                                ( wr_en_w          [DEQUEUE_]      ),
    
    // to empty pointer storage
  .add_empty_ptr_o                        ( add_empty_ptr    [DEQUEUE_]      ),
  .add_empty_ptr_en_o                     ( add_empty_ptr_en [DEQUEUE_]      ),

  .ll_head_table_if                          ( head_table_dequeue_if            ),

    // output interface with search result
  .result_o                               ( ht_eng_res[DEQUEUE_].result ),
  .result_valid_o                         ( ht_eng_res[DEQUEUE_].valid  ),
  .result_ready_i                         ( ht_eng_res[DEQUEUE_].ready  )
);


assign task_w = ll_cmd_if;

assign task_proccessing[ INSERT_ ] = !task_ready[ INSERT_ ];
assign task_proccessing[ DELETE_ ] = !task_ready[ DELETE_ ];
assign task_proccessing[ DEQUEUE_ ] = !task_ready[ DEQUEUE_ ];

always_comb
  begin
    ll_cmd_if_ready = 1'b1;

    task_valid[ INSERT_ ] = ll_cmd_if_valid && ( task_w.cmd.opcode == LL_OP_INSERT );
    task_valid[ DELETE_ ] = ll_cmd_if_valid && ( task_w.cmd.opcode == LL_OP_DELETE );
    task_valid[ DEQUEUE_ ] = ll_cmd_if_valid && ( task_w.cmd.opcode == LL_OP_DEQ );
    
    case( task_w.cmd.opcode )

      LL_OP_INSERT:
        begin
          if( task_proccessing[ DELETE_ ] || task_proccessing[ DEQUEUE_ ] )
            begin
              ll_cmd_if_ready      = 1'b0;
              task_valid[ INSERT_ ] = 1'b0;
            end
          else
            ll_cmd_if_ready = task_ready[ INSERT_ ];
        end

      LL_OP_DELETE:
        begin
          if( task_proccessing[ INSERT_ ] || task_proccessing[ DEQUEUE_ ] )
            begin
              ll_cmd_if_ready      = 1'b0;
              task_valid[ DELETE_ ] = 1'b0;
            end
          else
            ll_cmd_if_ready = task_ready[ DELETE_ ];
        end

      LL_OP_DEQ:
        begin
          if( task_proccessing[ INSERT_ ] || task_proccessing[ DELETE_ ] )
            begin
              ll_cmd_if_ready      = 1'b0;
              task_valid[ DEQUEUE_ ] = 1'b0;
            end
          else
            ll_cmd_if_ready = task_ready[ DEQUEUE_ ];
        end        

      default: 
        begin
          ll_cmd_if_ready = 1'b1;
        end
    endcase
  end


// ******* MUX to RAM *******
logic [DIR_CNT_WIDTH-1:0] rd_sel;
logic [DIR_CNT_WIDTH-1:0] wr_sel;
logic [DIR_CNT_WIDTH-1:0] add_empty_sel;

always_comb
  begin
    rd_sel = '0;

    for( int i = 0; i < DIR_CNT; i++ )
      begin
        if( rd_en_w[i] )
          rd_sel = i[DIR_CNT_WIDTH-1:0];
      end
  end

always_comb
  begin
    wr_sel = '0;

    for( int i = 0; i < DIR_CNT; i++ )
      begin
        if( wr_en_w[i] )
          wr_sel = i[DIR_CNT_WIDTH-1:0];
      end
  end

assign add_empty_ptr_en [INSERT_] = '0;

always_comb
  begin
    add_empty_sel = '0;

    for( int i = 0; i < DIR_CNT; i++ )
      begin
        if( add_empty_ptr_en[i] )
          add_empty_sel = i[DIR_CNT_WIDTH-1:0];
      end
  end

assign ram_rd_addr = rd_addr_w [ rd_sel ];
assign ram_rd_en   = rd_en_w   [ rd_sel ];

assign ram_wr_addr = wr_addr_w [ wr_sel ];
assign ram_wr_data = wr_data_w [ wr_sel ];
assign ram_wr_en   = wr_en_w   [ wr_sel ];  

assign add_empty_ptr_o = add_empty_ptr [ add_empty_sel ];
assign add_empty_ptr_en_o = add_empty_ptr_en [ add_empty_sel ];


// ******* MUX to head_table *******
always_comb
  begin
    if( head_table_insert_if.wr_en )
      begin      
        ll_head_table_if.wr_data_ptr     = head_table_insert_if.wr_data_ptr;     
        ll_head_table_if.wr_data_ptr_val = head_table_insert_if.wr_data_ptr_val;
        ll_head_table_if.wr_en           = head_table_insert_if.wr_en; 
      end
    else if( head_table_delete_if.wr_en )
      begin
        ll_head_table_if.wr_data_ptr     = head_table_delete_if.wr_data_ptr;     
        ll_head_table_if.wr_data_ptr_val = head_table_delete_if.wr_data_ptr_val;
        ll_head_table_if.wr_en           = head_table_delete_if.wr_en; 
      end
    else
      begin
        ll_head_table_if.wr_data_ptr     = head_table_dequeue_if.wr_data_ptr;     
        ll_head_table_if.wr_data_ptr_val = head_table_dequeue_if.wr_data_ptr_val;
        ll_head_table_if.wr_en           = head_table_dequeue_if.wr_en; 
      end
  end

// ******* Muxing cmd result *******

ll_ht_res_mux #(
  .DIR_CNT            ( DIR_CNT )
) res_mux (

  .ht_res_in                              ( ht_eng_res        ),
  .ht_res_out                             ( ll_res_if        )

);
// ******* Empty ptr store *******

ll_empty_ptr_storage #(
  .A_WIDTH                                ( LL_TABLE_ADDR_WIDTH  )
) empty_ptr_storage (

  .clk_i                                  ( clk_i             ),
  .rst_i                                  ( rst_i             ),
    
  .add_empty_ptr_i                        ( add_empty_ptr_o     ),
  .add_empty_ptr_en_i                     ( add_empty_ptr_en_o  ),
    
  .next_empty_ptr_rd_ack_i                ( empty_addr_rd_ack ),
  .next_empty_ptr_o                       ( empty_addr        ),
  .next_empty_ptr_val_o                   ( empty_addr_val    )

);

// ******* Clear RAM logic *******
logic               clear_ram_flag;
logic [A_WIDTH-1:0] clear_addr;

always_ff @( posedge clk_i or posedge rst_i )
  if( rst_i )
    clear_ram_flag <= 1'b0;
  else
    begin
      if( clear_ram_run_i )
        clear_ram_flag <= 1'b1;

      if( clear_ram_done_o )
        clear_ram_flag <= 1'b0;
    end
    
always_ff @( posedge clk_i or posedge rst_i )
  if( rst_i )
    clear_addr <= '0;
  else
    if( clear_ram_run_i )
      clear_addr <= '0;
    else
      if( clear_ram_flag )
        clear_addr <= clear_addr + 1'd1;

// assign wr_addr          = '0; // ( clear_ram_flag ) ? ( clear_addr ) : ( 'x ); //FIXME
// assign wr_data          = '0; // ( clear_ram_flag ) ? ( '0         ) : ( 'x );
// assign wr_en            = '0; // ( clear_ram_flag ) ? ( 1'b1       ) : ( 'x ); 
assign clear_ram_done_o = clear_ram_flag && ( clear_addr == '1 );

ll_true_dual_port_ram_single_clock #( 
  .DATA_WIDTH                             ( D_WIDTH           ), 
  .ADDR_WIDTH                             ( A_WIDTH           ), 
  .REGISTER_OUT                           ( RAM_LATENCY -1    )
) data_ram (
  .clk                                    ( clk_i             ),

  .addr_a                                 ( ram_rd_addr       ),
  .data_a                                 ( {D_WIDTH{1'b0}}   ),
  .we_a                                   ( 1'b0              ),
  .re_a                                   ( 1'b1              ),
  .q_a                                    ( ram_rd_data       ),

  .addr_b                                 ( ram_wr_addr       ),
  .data_b                                 ( ram_wr_data       ),
  .we_b                                   ( ram_wr_en         ),
  .re_b                                   ( 1'b0              ),
  .q_b                                    (                   )
);


endmodule
