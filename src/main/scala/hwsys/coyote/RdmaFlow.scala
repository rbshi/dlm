package hwsys.coyote

import spinal.core._
import spinal.lib._

import hwsys.util._
import hwsys.util.Helpers._

class RdamIO extends Bundle {
  // rd/wr cmd
  val rd_req = slave Stream StreamData(96)
  val wr_req = slave Stream StreamData(96)
  val rq = slave Stream StreamData(256)
  val sq = master Stream StreamData(256)

  val axis_sink = slave Stream Axi4StreamData(512)
  val axis_src =  master Stream Axi4StreamData(512)
}


case class RdmaFlow(isMstr : Boolean) extends Component with RenameIO {

  val io = new Bundle {
    val rdma_1 = new RdamIO

    // interface user logic
    val q_sink = slave Stream Bits(512 bits)
    val q_src = master Stream Bits(512 bits)

    // input
    val en = in Bool()
    val len = in UInt(32 bits)
    val qpn = in UInt(24 bits)
    val nOnFly = in UInt(32 bits)
    val flowId = in UInt(8 bits)

    // output
    val cntSent = out(Reg(UInt(32 bits))).init(0)
    val cntRecv = out(Reg(UInt(32 bits))).init(0)

  }

  io.rdma_1.rq.setBlocked()

  val rdma_base = RdmaBaseT()
  rdma_base.lvaddr := 0
  rdma_base.rvaddr := 0
  rdma_base.len := io.len
  rdma_base.params := io.flowId.resized // for rdma wr to different resource, use flowId as rvadd to identify

  val sq = RdmaReqT()
  sq.opcode := 1 // write
  sq.qpn := io.qpn
  sq.id := 0
  sq.host := False
  sq.mode := False
  sq.pkg.assignFromBits(rdma_base.asBits)
  sq.rsrvd := 0
  io.rdma_1.sq.data.assignFromBits(sq.asBits)

  // default
  io.rdma_1.sq.valid.clear()
  io.rdma_1.axis_src.valid.clear()
  io.rdma_1.axis_src.tlast.clear()
  io.rdma_1.axis_src.tkeep.setAll()

  // ready of rd/wr req
  io.rdma_1.rd_req.ready.set()
  io.rdma_1.wr_req.ready.set()

  // val incCntToSend = io.rdma_1.rd_req.fire // should NOT use rd_req to trigger the incCntToSend, it has a delay to the sq.fire, and will underflow to fireSq criteria to minus ??
  val incCntToSend = io.rdma_1.sq.fire
  val decCntToSend = io.rdma_1.axis_src.fire && io.rdma_1.axis_src.tlast
  val cntAxiToSend = CntIncDec(8 bits, incCntToSend, decCntToSend)

  val incOnFly = io.rdma_1.sq.fire
  val decOnFly = io.rdma_1.axis_sink.fire && io.rdma_1.axis_sink.tlast
  val cntOnFly = CntIncDec(8 bits, incOnFly, decOnFly)

  io.cntRecv := io.cntRecv + decOnFly.asUInt(1 bit)
  io.cntSent := io.cntSent + decCntToSend.asUInt(1 bit)

  val cntBeat = CntDynmicBound(io.len>>6, io.rdma_1.axis_src.fire) // each axi beat is with 64 B
  when(cntBeat.willOverflow) (io.rdma_1.axis_src.tlast.set())

  if(isMstr){
    // mstr hw & behavior

    val sendQ, recvQ = StreamFifo(Bits(512 bits), 512)

    sendQ.flushWhen(~io.en)
    recvQ.flushWhen(~io.en)

    // sendQ
    io.q_sink >> sendQ.io.push
    // have packet to send
    io.rdma_1.axis_src.translateFrom(sendQ.io.pop.continueWhen(cntAxiToSend.cnt > 0))(_.tdata := _)

    // fire sq
    // the max net package size here is 1KB (one max send len = 1KB -> 64<<4)
    val fireSq = ((sendQ.io.occupancy - (cntAxiToSend.cnt<<4)).asSInt >= 16) && (recvQ.io.availability >= ((cntOnFly.cnt+1)<<4)) && (cntOnFly.cnt < io.nOnFly)
    when(fireSq)(io.rdma_1.sq.valid := True)

    // recvQ
    recvQ.io.pop >> io.q_src
    recvQ.io.push.translateFrom(io.rdma_1.axis_sink)(_ := _.tdata)

  } else {
    // slave hw & behavior (no onfly control)

    val reqQ, respQ = StreamFifo(Bits(512 bits), 512)
    reqQ.flushWhen(~io.en)
    respQ.flushWhen(~io.en)

    // reqQ
    reqQ.io.pop >> io.q_src
    reqQ.io.push.translateFrom(io.rdma_1.axis_sink)(_ := _.tdata)

    // respQ
    io.q_sink >> respQ.io.push
    // have packet to send
    io.rdma_1.axis_src.translateFrom(respQ.io.pop.continueWhen(cntAxiToSend.cnt > 0))(_.tdata := _)

    // fire sq
    val fireSq = (respQ.io.occupancy - (cntAxiToSend.cnt<<4)).asSInt >= 16 // cast to SInt for comparison
    when(fireSq)(io.rdma_1.sq.valid := True)

  }

  when(~io.en) {
    cntAxiToSend.clearAll()
    cntOnFly.clearAll()
    cntBeat.clearAll()
    io.cntSent.clearAll()
    io.cntRecv.clearAll()
  }

}