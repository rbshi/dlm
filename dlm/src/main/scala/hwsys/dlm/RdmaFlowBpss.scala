package hwsys.dlm

import spinal.core._
import spinal.lib._

import hwsys.util._
import hwsys.util.Helpers._
import hwsys.coyote._


class RdmaFlowBpss(isMstr : Boolean)(implicit sysConf: SysConfig) extends Component with RenameIO {

  val io = new Bundle {
    val rdma = new RdmaIO

    // interface user logic
    val q_sink = slave Stream Bits(512 bits)
    val q_src = master Stream Bits(512 bits)

    // ctrl
    val ctrl = new RdmaCtrlIO()

  }

  // default
  io.rdma.axis_src.tlast.clear()
  io.rdma.axis_src.tkeep.setAll()

  // ready of rd/wr req
  io.rdma.rd_req.ready.set()
  io.rdma.wr_req.ready.set()

  // val incCntToSend = io.rdma.rd_req.fire // should NOT use rd_req to trigger the incCntToSend, it has a delay to the sq.fire, and will underflow to fireSq criteria to minus ??
  val incCntToSend = io.rdma.sq.fire
  val decCntToSend = io.rdma.axis_src.fire && io.rdma.axis_src.tlast
  val cntAxiToSend = CntIncDec(8 bits, incCntToSend, decCntToSend)

  // NOTE: using timeOut may have unknown affect on flow control
  // rst the timer with sq.fire to avoid over issue sq
  val timeOutInc = Bool()
  val timeOut = pauseTimeOut(9 bits, timeOutInc, io.rdma.sq.fire) // 512 cycles
  // buf the to status and refresh with sq.fire or axi.last
  val rTimeOut = RegNextWhen(timeOut.isTimeOut, io.rdma.sq.fire || decCntToSend)

  val cntBeat = CntDynmicBound(Mux(rTimeOut, U(1), io.ctrl.len>>6),  io.rdma.axis_src.fire) // each axi beat is with 64 B
  when(cntBeat.willOverflowIfInc) (io.rdma.axis_src.tlast.set())

  // sq settings
  val rdma_base = RdmaBaseT()
  rdma_base.lvaddr := 0
  rdma_base.rvaddr := io.ctrl.flowId.resized // for rdma wr to different resource, use flowId as rvadd to identify
  rdma_base.len := Mux(rTimeOut, U(64), io.ctrl.len)
  rdma_base.params := 0

  val sq = RdmaReqT()
  // sq.opcode := 1 // APP_WRITE -> move to RC_RDMA_WRITE_ONLY after `rdma_req_parser`
  sq.opcode := 0xa // RDMA_WRITE_ONLY
  sq.qpn := io.ctrl.qpn
  sq.host := False
  // sq.mode := False // with RDMA parser
  sq.mode := True // RDMA_MODE_RAW, bypass the RDMA parser
  sq.last := True
  sq.msg.assignFromBits(rdma_base.asBits)
  sq.rsrvd := 0
  io.rdma.sq.data.assignFromBits(sq.asBits)


  if(isMstr){
    // mstr hw & behavior

    val sendQ = StreamFifo(Bits(512 bits), 512)
    val recvQ = StreamFifo(Bits(512 bits), 512)

    sendQ.flushWhen(~io.ctrl.en)
    recvQ.flushWhen(~io.ctrl.en)

    timeOutInc := sendQ.io.occupancy > 0

    // sendQ
    sendQ.io.push << io.q_sink

    // have packet to send
    io.rdma.axis_src.translateFrom(sendQ.io.pop.continueWhen(cntAxiToSend.cnt > 0))(_.tdata := _)

    // fire sq criteria: enough data in sendQ (fifo.occupancy > 16, for 1K packet)
    val fireSq : Bool = (sendQ.io.occupancy - (cntAxiToSend.cnt<<4)).asSInt >= 16

    io.rdma.sq.valid := fireSq || timeOut.isTimeOut

    // recvQ
    recvQ.io.pop >> io.q_src
    recvQ.io.push.translateFrom(io.rdma.axis_sink)(_ := _.tdata)

  } else {
    // slave hw & behavior

    val reqQ, respQ = StreamFifo(Bits(512 bits), 512)
    reqQ.flushWhen(~io.ctrl.en)
    respQ.flushWhen(~io.ctrl.en)

    // reqQ
    reqQ.io.pop >> io.q_src
    reqQ.io.push.translateFrom(io.rdma.axis_sink)(_ := _.tdata)

    // respQ
    io.q_sink >> respQ.io.push
    // have packet to send
    io.rdma.axis_src.translateFrom(respQ.io.pop.continueWhen(cntAxiToSend.cnt > 0))(_.tdata := _)

    // fire sq
    val fireSq = (respQ.io.occupancy - (cntAxiToSend.cnt<<4)).asSInt >= 16 // cast to SInt for comparison

    timeOutInc := respQ.io.occupancy > 0
    io.rdma.sq.valid := fireSq || timeOut.isTimeOut

  }

  when(~io.ctrl.en) {
    cntAxiToSend.clearAll()
    cntBeat.clearAll()
  }

}
