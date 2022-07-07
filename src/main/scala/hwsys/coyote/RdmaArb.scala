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

  // pipe the sq
  val sqV = Vec(Stream(StreamData(560)), cnt)
  (sqV, io.rdmaV).zipped.foreach(_ <-/< _.sq)

  // mstr interface arb
  // fire sq => fire rd_req => fire axis_src with .last => demux to next
  val strmFifo1, strmFifo2 = StreamFifo(UInt(log2Up(cnt) bits), 32)

  // val mskSqVld = Vec(Bool(),cnt)
  val mskSqVld = Bits(cnt bits)
  for (i <- mskSqVld.bitsRange)
    mskSqVld(i) := sqV(i).valid

  // round-robin
  val mskSqSel = cloneOf(mskSqVld)
  // LSB should be initialized to 1
  val mskLocked = RegNextWhen(mskSqSel, io.rdmaio.sq.fire).init(1)
  mskSqSel := OHMasking.roundRobin(mskSqVld, mskLocked(0) ## mskLocked(mskLocked.high downto 1))

  val sqSel = OHToUInt(mskSqSel)

  // fire when strmFifo is not full
  io.rdmaio.sq << StreamMux(sqSel, sqV).continueWhen(strmFifo1.io.availability > 0)

  strmFifo1.io.push.payload := sqSel
  strmFifo1.io.push.valid := io.rdmaio.sq.fire

  val rdSel = strmFifo1.io.pop.payload
  (io.rdmaV, StreamDemux(io.rdmaio.rd_req.continueWhen(strmFifo1.io.pop.valid), rdSel, cnt)).zipped.foreach(_.rd_req << _)
  // avoid 1 cycle of .occupancy in io.pop
  // val rRdReqFire = RegNext(io.rdmaio.rd_req.fire)
  strmFifo2.io.push << strmFifo1.io.pop.continueWhen(io.rdmaio.rd_req.fire)

  val axiSrcSel = strmFifo2.io.pop.payload
  io.rdmaio.axis_src << StreamMux(axiSrcSel, io.rdmaV.map(_.axis_src)).continueWhen(strmFifo2.io.pop.valid)
  // throwFireWhen
  strmFifo2.io.pop.ready := (io.rdmaio.axis_src.fire && io.rdmaio.axis_src.tlast) // pop after an axis fragment

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

  // strmFifo3.io.pop.valid to address the latency between push and pop io
  (io.rdmaV, StreamDemux(io.rdmaio.axis_sink.continueWhen(strmFifo3.io.pop.valid) , axiSinkSel, cnt)).zipped.foreach(_.axis_sink << _)
  strmFifo3.io.pop.ready := (io.rdmaio.axis_sink.fire && io.rdmaio.axis_sink.tlast) // pop after an axis fragment

}
