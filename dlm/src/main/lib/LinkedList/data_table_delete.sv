//-----------------------------------------------------------------------------
// Project       : fpga-hash-table
//-----------------------------------------------------------------------------
// Author        : Ivan Shevchuk (github/johan92)
//-----------------------------------------------------------------------------
// Delete algo:
// 
//   if( no valid head_ptr )
//     LL_DELETE_NOT_SUCCESS_NO_ENTRY
//   else
//     if( key matched )
//       begin
//         
//         clear data in addr
//         put addr to empty list 
//   
//         if( it's first data in chain ) 
//           begin
//             // update head ptr in head_table 
//             if( next_ptr is NULL )
//               head_ptr = NULL
//             else
//               head_ptr = next_ptr
//           end
//        else
//          if( it's last data in chain )
//            begin
//              set in previous chain addr next_ptr is NULL
//            end
//          else
//            // we got data in the middle of chain
//            begin
//              set in previous chain addr next_ptr is ptr of next data
//            end
//   
//         LL_DELETE_SUCCESS
//       end
//     else
//       begin
//         LL_DELETE_NOT_SUCCESS_NO_ENTRY
//       end


import linked_list::*;

module ll_data_table_delete #(
  parameter RAM_LATENCY = 2,

  parameter A_WIDTH     = LL_TABLE_ADDR_WIDTH
) ( 
  input                       clk_i,
  input                       rst_i,
  
  input  ll_ht_pdata_t           task_i,
  input                       task_valid_i,
  output                      task_ready_o,
  
  // to data RAM
  input  ll_ram_data_t           rd_data_i,
  output logic [A_WIDTH-1:0]  rd_addr_o,
  output logic                rd_en_o,

  output logic [A_WIDTH-1:0]  wr_addr_o,
  output ll_ram_data_t           wr_data_o,
  output logic                wr_en_o,
  
  // to empty pointer storage
  output  [A_WIDTH-1:0]       add_empty_ptr_o,
  output                      add_empty_ptr_en_o,

  ll_head_table_if.master        ll_head_table_if,

  // output interface with search result
  output ll_ht_result_t          result_o,
  output logic                result_valid_o,
  input                       result_ready_i
);

enum int unsigned {
  IDLE_S,

  NO_VALID_HEAD_PTR_S,

  READ_HEAD_S,
  GO_ON_CHAIN_S,

  LL_IN_TAIL_WITHOUT_MATCH_S,

  LL_KEY_MATCH_LL_IN_HEAD_S,
  LL_KEY_MATCH_LL_IN_MIDDLE_S,
  LL_KEY_MATCH_LL_IN_TAIL_S,

  CLEAR_RAM_AND_PTR_S

} state, next_state, state_d1;

ll_ht_pdata_t              task_locked;
logic                   key_match;
logic                   got_tail;
logic [A_WIDTH-1:0]     rd_addr;
ll_ram_data_t              prev_rd_data;
ll_ram_data_t              prev_prev_rd_data;
logic [A_WIDTH-1:0]     prev_rd_addr;

logic                   rd_data_val;
logic                   rd_data_val_d1;
logic                   state_first_tick;

ll_rd_data_val_helper #( 
  .RAM_LATENCY                          ( RAM_LATENCY  ) 
) rd_data_val_helper (
  .clk_i                                ( clk_i        ),
  .rst_i                                ( rst_i        ),

  .rd_en_i                              ( rd_en_o      ),
  .rd_data_val_o                        ( rd_data_val  )

);

always_ff @( posedge clk_i or posedge rst_i )
  if( rst_i )
    state <= IDLE_S;
  else
    state <= next_state;

always_ff @( posedge clk_i or posedge rst_i )
  if( rst_i )
    state_d1 <= IDLE_S;
  else
    state_d1 <= state;

assign state_first_tick = ( state != state_d1 );

// we need to do search, so this FSM will be similar 
// with search FSM

always_comb
  begin
    next_state = state;

    case( state )
      IDLE_S:
        begin
          if( task_valid_i && task_ready_o )
            begin
              if( task_i.head_ptr_val == 1'b0 )
                next_state = NO_VALID_HEAD_PTR_S;
              else
                next_state = READ_HEAD_S;
            end
        end

      READ_HEAD_S, GO_ON_CHAIN_S:
        begin
          if( rd_data_val )
            begin
              if( key_match )
                begin
                  if( state == READ_HEAD_S )
                    next_state = LL_KEY_MATCH_LL_IN_HEAD_S;
                  else
                    if( got_tail )
                      next_state = LL_KEY_MATCH_LL_IN_TAIL_S;
                    else
                      next_state = LL_KEY_MATCH_LL_IN_MIDDLE_S;
                end
              else
                if( got_tail )
                  next_state = LL_IN_TAIL_WITHOUT_MATCH_S;
                else
                  next_state = GO_ON_CHAIN_S;
            end
        end
      
      LL_KEY_MATCH_LL_IN_HEAD_S, LL_KEY_MATCH_LL_IN_MIDDLE_S, LL_KEY_MATCH_LL_IN_TAIL_S:
        begin
          next_state = CLEAR_RAM_AND_PTR_S; 
        end

      CLEAR_RAM_AND_PTR_S, NO_VALID_HEAD_PTR_S, LL_IN_TAIL_WITHOUT_MATCH_S:
        begin
          // waiting for accepting report 
          if( result_valid_o && result_ready_i )
            next_state = IDLE_S;
        end

      default:
        begin
          next_state = IDLE_S;
        end
    endcase
  end

always_ff @( posedge clk_i or posedge rst_i )
  if( rst_i )
    task_locked <= '0;
  else
    if( task_ready_o && task_valid_i )
      task_locked <= task_i;

assign key_match = ( task_locked.cmd.key == rd_data_i.key );
assign got_tail  = ( rd_data_i.next_ptr_val == 1'b0  );

always_ff @( posedge clk_i or posedge rst_i )
  if( rst_i )
    begin
      rd_addr      <= '0;
      prev_rd_addr <= '0;
    end
  else
    if( ( state == IDLE_S ) && ( next_state == READ_HEAD_S ) )
      begin
        rd_addr      <= task_i.head_ptr;
        prev_rd_addr <= rd_addr;
      end
    else
      if( rd_data_val && ( next_state == GO_ON_CHAIN_S ) )
        begin
          rd_addr      <= rd_data_i.next_ptr;
          prev_rd_addr <= rd_addr;
        end

always_ff @( posedge clk_i or posedge rst_i )
  if( rst_i )
    rd_data_val_d1 <= 1'b0;
  else
    rd_data_val_d1 <= rd_data_val;

always_ff @( posedge clk_i or posedge rst_i )
  if( rst_i )
    begin
      prev_rd_data <= '0;
      prev_prev_rd_data <= '0;
    end
  else
    if( rd_data_val )
      begin
        prev_rd_data      <= rd_data_i;
        prev_prev_rd_data <= prev_rd_data;
      end


assign task_ready_o = ( state == IDLE_S );

assign rd_en_o      = ( state_first_tick || rd_data_val_d1 ) && ( ( state == READ_HEAD_S   ) || 
                                                                  ( state == GO_ON_CHAIN_S ) );   

assign rd_addr_o    = rd_addr; 

assign wr_en_o      = state_first_tick && ( ( state == LL_KEY_MATCH_LL_IN_MIDDLE_S  ) ||
                                            ( state == LL_KEY_MATCH_LL_IN_TAIL_S    ) || 
                                            ( state == CLEAR_RAM_AND_PTR_S    ) );

ll_ram_data_t rd_data_locked;

always_ff @( posedge clk_i )
  if( rd_data_val )
    rd_data_locked <= rd_data_i;

always_comb
  begin
    wr_data_o = prev_prev_rd_data;
    wr_addr_o = 'x;

    case( state )

      CLEAR_RAM_AND_PTR_S:
        begin
          wr_data_o = '0; 

          wr_addr_o = rd_addr;
        end

      LL_KEY_MATCH_LL_IN_MIDDLE_S:
        begin
          wr_data_o.next_ptr     = rd_data_locked.next_ptr;
          wr_data_o.next_ptr_val = rd_data_locked.next_ptr_val;

          wr_addr_o              = prev_rd_addr;
        end
      
      LL_KEY_MATCH_LL_IN_TAIL_S:
        begin
          wr_data_o.next_ptr     = '0;
          wr_data_o.next_ptr_val = 1'b0;

          wr_addr_o              = prev_rd_addr;
        end

      default:
        begin
          // do nothing
          wr_data_o = prev_prev_rd_data;
          wr_addr_o = 'x;
        end
    endcase
  end

// ******* Head Ptr table magic *******
assign ll_head_table_if.wr_data_ptr      = rd_data_locked.next_ptr;
assign ll_head_table_if.wr_data_ptr_val  = rd_data_locked.next_ptr_val;
assign ll_head_table_if.wr_en            = state_first_tick && ( state == LL_KEY_MATCH_LL_IN_HEAD_S );

// ******* Empty ptr storage ******

assign add_empty_ptr_o     = rd_addr;
assign add_empty_ptr_en_o  = state_first_tick && ( state == CLEAR_RAM_AND_PTR_S );

// ******* Result calculation *******
assign result_o.cmd         = task_locked.cmd;
assign result_o.rescode     = ( ( state == NO_VALID_HEAD_PTR_S     ) ||
                                ( state == LL_IN_TAIL_WITHOUT_MATCH_S ) ) ? ( LL_DELETE_NOT_SUCCESS_NO_ENTRY ):
                                                                         ( LL_DELETE_SUCCESS              );

ll_ht_chain_state_t chain_state;

always_ff @( posedge clk_i or posedge rst_i )
  if( rst_i )
    chain_state <= LL_NO_CHAIN;
  else
    if( state != next_state )
      begin
        case( next_state )
          NO_VALID_HEAD_PTR_S     : chain_state <= LL_NO_CHAIN;
          LL_IN_TAIL_WITHOUT_MATCH_S : chain_state <= LL_IN_TAIL_NO_MATCH;
          LL_KEY_MATCH_LL_IN_HEAD_S     : chain_state <= LL_IN_HEAD;
          LL_KEY_MATCH_LL_IN_MIDDLE_S   : chain_state <= LL_IN_MIDDLE;
          LL_KEY_MATCH_LL_IN_TAIL_S     : chain_state <= LL_IN_TAIL;
          // no default: just keep old value
        endcase
      end

assign result_o.chain_state = chain_state; 

assign result_valid_o = ( state == CLEAR_RAM_AND_PTR_S      ) ||
                        ( state == NO_VALID_HEAD_PTR_S      ) ||
                        ( state == LL_IN_TAIL_WITHOUT_MATCH_S  );
                      
endmodule
