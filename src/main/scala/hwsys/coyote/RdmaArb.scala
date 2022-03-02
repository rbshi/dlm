package hwsys.coyote

import spinal.core._
import spinal.lib._

// TODO: abstract to ArbwihMultiStreams
class RdmaArb(cnt: Int) {

  val io = new Bundle {
    // input
    val rdmaV = Vec(new RdmaIO, cnt)
    // output
    val rdmaio = new RdmaIO
  }

  // mstr interface arb
  // fire sq => fire rd_req => fire axis_src with .last => demux to next
  val strmFifo1, strmFifo2 = StreamFifo(UInt(log2Up(cnt) bits), 32)

  val mskSqVld = io.rdmaV.map(_.sq.valid)
  // round-robin
  val mskLocked = RegNextWhen(mskSqSel, io.rdmaio.sq.fire)
  val mskSqSel = OHMasking.roundRobin(mskSqVld, Vec(mskLocked.last +: mskLocked.take(mskLocked.length-1)))
  val sqSel = OHToUInt(mskSqSel)

  // fire when strmFifo is not full
  io.rdmaV(sqSel).sq >> io.rdmaio.sq.continueWhen(strmFifo1.io.availability > 0)

  strmFifo1.io.push.payload := sqSel
  strmFifo1.io.push.valid := io.rdmaio.sq.fire

  val rdSel = strmFifo1.io.pop.payload
  io.rdmaV(rdSel).rd_req << io.rdmaio.rd_req.continueWhen(strmFifo2.io.availability > 0)
  strmFifo2.io.push << strmFifo1.io.pop.continueWhen(io.rdmaio.rd_req.fire)

  val axiSrcSel = strmFifo2.io.pop.payload
  io.rdmaV(axiSrcSel).axis_src >> io.rdmaio.axis_src
  strmFifo2.io.pop.throwWhen(io.rdmaio.axis_src.fire)

  // slve interface arb
  // fire wr_req => get wr_req .params (get which flow demux to) => fire axis_sink .last => demux
  val strmFifo3 = StreamFifo(UInt(log2Up(cnt) bits), 32)
  val wrReq = ReqT()
  wrReq.assignFromBits(io.rdmaio.wr_req.data)
  val wrSel = wrReq.vaddr.resize(log2Up(cnt) bits)

  io.rdmaV(wrSel).wr_req << io.rdmaio.wr_req.continueWhen(strmFifo3.io.availability > 0)
  strmFifo3.io.push.payload := wrSel
  strmFifo3.io.push.valid := io.rdmaio.wr_req.fire

  val axiSinkSel = strmFifo3.io.pop.payload
  io.rdmaV(axiSinkSel).axis_sink << io.rdmaio.axis_sink

  strmFifo3.io.pop.throwWhen(io.rdmaio.axis_sink.fire && io.rdmaio.axis_sink.tlast)

}
