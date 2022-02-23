package hwsys.util

import spinal.core._
import spinal.lib._

object StreamCrossbarFactory {

  // instantiation
  def build[T1 <: Data, T2 <: Data](nIn: Int, nOut: Int, dInT: HardType[T1], dOutT: HardType[T2])(f: (T2, T1) => Unit): StreamCrossbar[T1, T2] = {
    new StreamCrossbar(nIn, nOut, dInT, dOutT)(f)
  }

  def on[T1 <: Data, T2 <: Data](inV: Seq[Stream[T1]], outV: Seq[Stream[T2]], inDemuxSel: Seq[UInt])(f: (T2, T1) => Unit) {
    val crossbar = build(inV.size, outV.size, inV.head.payloadType, outV.head.payloadType)(f)
    (crossbar.io.inV, inV).zipped.foreach(_ << _)
    (crossbar.io.outV, outV).zipped.foreach(_ >> _)
    (crossbar.io.inDemuxSel, inDemuxSel).zipped.foreach(_ <> _)
  }
}

// with similar coding style as StreamArbiter
// bypass demuxSel for each StreamDemux
class StreamCrossbar[T1 <: Data, T2 <: Data](nIn: Int, nOut: Int, dInT: HardType[T1], dOutT: HardType[T2])(f: (T2, T1) => Unit) extends Component {
  val io = new Bundle {
    val inV = Vec(slave Stream(dInT), nIn)
    val outV = Vec(master Stream(dOutT), nOut)
    val inDemuxSel = in Vec(UInt(log2Up(nOut) bits), nIn)
  }

  // inV Demux
  val inDemuxAry = Array.fill(nIn)(new StreamDemux2(dInT, nOut))
  (io.inV, inDemuxAry).zipped.foreach(_ >> _.io.input)
  (io.inDemuxSel, inDemuxAry).zipped.foreach(_ <> _.io.select)

  // out Arbiter
  val outArbAry = Array.fill(nOut)(StreamArbiterFactory.roundRobin.build(dInT, nIn))
  for (i <- 0 until nOut)
    (inDemuxAry.map(_.io.outputs(i)) , outArbAry(i).io.inputs).zipped.foreach(_ >/-> _) // pipelined

  // apply f on arb out
  (io.outV, outArbAry.map(_.io.output)).zipped.foreach(_.translateFrom(_)(f))
}

// change dataType : T in StreamDemux -> dataType : HardType[T]
class StreamDemux2[T <: Data](dataType: HardType[T], portCount: Int) extends Component {
  val io = new Bundle {
    val select = in UInt (log2Up(portCount) bit)
    val input = slave Stream (dataType)
    val outputs = Vec(master Stream (dataType),portCount)
  }
  io.input.ready := False
  for (i <- 0 until portCount) {
    io.outputs(i).payload := io.input.payload
    when(i =/= io.select) {
      io.outputs(i).valid := False
    } otherwise {
      io.outputs(i).valid := io.input.valid
      io.input.ready := io.outputs(i).ready
    }
  }
}