package hwsys.coyote

import spinal.core._
import spinal.lib._

// TODO: abstract to ArbwihMultiStreams
class RdmaArb(cnt: Int) extends Component {

  val io = new Bundle {
    // input
    val rdmaV = Vec(new RdmaIO, cnt)
    // output
    val rdmaio = new RdmaIO
  }

  // rdmaV is a slave hub
  io.rdmaV.foreach(_.flipDir())

  // block the rq
  io.rdmaio.rq.setBlocked()

  // mstr interface arb
  // fire sq => fire rd_req => fire axis_src with .last => demux to next
  val strmFifo1, strmFifo2 = StreamFifo(UInt(log2Up(cnt) bits), 32)

  val mskSqVld = Vec(Bool(),cnt)
  (mskSqVld, io.rdmaV).zipped.foreach(_ := _.sq.valid)

  // round-robin
  val mskSqSel = cloneOf(mskSqVld)
  val mskLocked = RegNextWhen(mskSqSel, io.rdmaio.sq.fire)
  mskSqSel := OHMasking.roundRobin(mskSqVld, Vec(mskLocked.last +: mskLocked.take(mskLocked.length-1)))

  val sqSel = OHToUInt(mskSqSel)

  // fire when strmFifo is not full
  io.rdmaio.sq << StreamMux(sqSel, io.rdmaV.map(_.sq)).continueWhen(strmFifo1.io.availability > 0)

  strmFifo1.io.push.payload := sqSel
  strmFifo1.io.push.valid := io.rdmaio.sq.fire

  val rdSel = strmFifo1.io.pop.payload
  (io.rdmaV, StreamDemux(io.rdmaio.rd_req.continueWhen(strmFifo2.io.availability > 0), rdSel, cnt)).zipped.foreach(_.rd_req << _)
  strmFifo2.io.push << strmFifo1.io.pop.continueWhen(io.rdmaio.rd_req.fire)

  val axiSrcSel = strmFifo2.io.pop.payload
  io.rdmaio.axis_src << StreamMux(axiSrcSel, io.rdmaV.map(_.axis_src))

  // throwFireWhen
  strmFifo2.io.pop.ready := io.rdmaio.axis_src.fire ? True | False

  // slve interface arb
  // fire wr_req => get wr_req .params (get which flow demux to) => fire axis_sink .last => demux
  val strmFifo3 = StreamFifo(UInt(log2Up(cnt) bits), 32)
  val wrReq = ReqT()
  wrReq.assignFromBits(io.rdmaio.wr_req.data)
  val wrSel = wrReq.vaddr.resize(log2Up(cnt) bits)

  (io.rdmaV, StreamDemux(io.rdmaio.wr_req.continueWhen(strmFifo3.io.availability > 0), wrSel, cnt)).zipped.foreach(_.wr_req << _)
  strmFifo3.io.push.payload := wrSel
  strmFifo3.io.push.valid := io.rdmaio.wr_req.fire

  val axiSinkSel = strmFifo3.io.pop.payload

  (io.rdmaV, StreamDemux(io.rdmaio.axis_sink, axiSinkSel, cnt)).zipped.foreach(_.axis_sink << _)
  strmFifo3.io.pop.ready := (io.rdmaio.axis_sink.fire && io.rdmaio.axis_sink.tlast) ? True | False

}
