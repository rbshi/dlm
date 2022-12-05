package hwsys.coyote

import spinal.core._
import spinal.lib._

import hwsys.util._
import hwsys.util.Helpers._

// FIXME: NOT tested after simplification
case class RdmaFlow(isMstr : Boolean) extends Component with RenameIO {

  val io = new Bundle {
    val rdma = new RdmaIO

    // interface user logic
    val q_sink = slave Stream Bits(512 bits)
    val q_src = master Stream Bits(512 bits)

    // input
    val en = in Bool()
    val len = in UInt(16 bits)
    val qpn = in UInt(10 bits)
    val nOnFly = in UInt(32 bits)
//    val flowId = in UInt(4 bits)

    // output
    val cntSent = out(Reg(UInt(32 bits))).init(0)
    val cntRecv = out(Reg(UInt(32 bits))).init(0)

    val dbg = out Bits(64 bits);

  }

  val rdma_base = RdmaBaseT()
  rdma_base.lvaddr := 0
  rdma_base.rvaddr := 0 // for rdma wr to different resource, use flowId as rvadd to identify
  rdma_base.len := io.len.resized
  rdma_base.params := 0

  val sq = RdmaReqT()
  sq.opcode := 0xa // RDMA_WRITE_ONLY
  sq.qpn := io.qpn
  sq.host := False
  sq.mode := True // RDMA_MODE_RAW, bypass the RDMA parser
  sq.last := True
  sq.msg.assignFromBits(rdma_base.asBits)
  sq.rsrvd := 0
  io.rdma.sq.data.assignFromBits(sq.asBits)

  // default
  io.rdma.axis_src.tkeep.setAll()

  // throw away the ack
  io.rdma.ack.ready.set()

  // ready of rd/wr req
  io.rdma.rd_req.ready.set()
  io.rdma.wr_req.ready.set()

  // val incCntToSend = io.rdma.rd_req.fire // should NOT use rd_req to trigger the incCntToSend, it has a delay to the sq.fire, and will underflow to fireSq criteria to minus ??
  val incPkgToSend = io.rdma.sq.fire
  val decPkgToSend = io.rdma.axis_src.fire && io.rdma.axis_src.tlast
  val cntPkgToSend = CntIncDec(8 bits, incPkgToSend, decPkgToSend)

  val incOnFly = io.rdma.sq.fire
  val decOnFly = io.rdma.axis_sink.fire && io.rdma.axis_sink.tlast
  val cntOnFly = CntIncDec(8 bits, incOnFly, decOnFly)

  io.cntRecv := io.cntRecv + decOnFly.asUInt(1 bit)
  io.cntSent := io.cntSent + decPkgToSend.asUInt(1 bit)

  val cntBeat = CntDynmicBound(io.len>>6, io.rdma.axis_src.fire) // each axi beat is with 64 B
  io.rdma.axis_src.tlast := cntBeat.willOverflowIfInc

  io.dbg := 0
  io.dbg.allowOverride

  if(isMstr){
    // mstr hw & behavior

    val sendQ, recvQ = StreamFifo(Bits(512 bits), 512)

    sendQ.flushWhen(~io.en)
    recvQ.flushWhen(~io.en)

    // sendQ
    io.q_sink >> sendQ.io.push
    // have packet to send
    io.rdma.axis_src.translateFrom(sendQ.io.pop.continueWhen(cntPkgToSend.cnt > 0))(_.tdata := _)

    // fire sq
    // the max net package size here is 4KB (one max send len = 4KB -> 64 << 6)
    val fireSq = ((sendQ.io.occupancy - (cntPkgToSend.cnt * (io.len>>6))).asSInt >= (io.len>>6).asSInt ) && (recvQ.io.availability >= ((cntOnFly.cnt+1)<<4)) && (cntOnFly.cnt < io.nOnFly)
    io.rdma.sq.valid := fireSq

    // recvQ
    recvQ.io.pop >> io.q_src
    recvQ.io.push.translateFrom(io.rdma.axis_sink)(_ := _.tdata)

    io.dbg(0) := ((sendQ.io.occupancy - (cntPkgToSend.cnt * (io.len>>6))).asSInt >= (io.len>>6).asSInt )
    io.dbg(1) := (recvQ.io.availability >= ((cntOnFly.cnt+1)<<4))
    io.dbg(2) := (cntOnFly.cnt < io.nOnFly)
    io.dbg(12 downto 3) := sendQ.io.occupancy.asBits
    io.dbg(20 downto 13) := cntPkgToSend.cnt.asBits

  } else {
    // slave hw & behavior (no onfly control)

    val reqQ, respQ = StreamFifo(Bits(512 bits), 512)
    reqQ.flushWhen(~io.en)
    respQ.flushWhen(~io.en)

    // reqQ
    reqQ.io.pop >> io.q_src
    reqQ.io.push.translateFrom(io.rdma.axis_sink)(_ := _.tdata)

    // respQ
    io.q_sink >> respQ.io.push
    // have packet to send
    io.rdma.axis_src.translateFrom(respQ.io.pop.continueWhen(cntPkgToSend.cnt > 0))(_.tdata := _)

    // fire sq
    val fireSq = (respQ.io.occupancy - (cntPkgToSend.cnt * (io.len>>6))).asSInt >= (io.len>>6).asSInt // cast to SInt for comparison
    io.rdma.sq.valid := fireSq

  }

  when(~io.en) {
    cntPkgToSend.clearAll()
    cntOnFly.clearAll()
    cntBeat.clearAll()
    io.cntSent.clearAll()
    io.cntRecv.clearAll()
  }

}