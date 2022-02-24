package hwsys.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axilite._

import scala.collection.mutable
import scala.collection._
import scala.math.BigInt


/** Types in sim */
trait MemStructSim {
  def asBytes : Seq[Byte]
}

/** Helper function with MemStruct */
object MemStructSim {
  def bigIntToBytes(v: BigInt, byteLen: Int) : Seq[Byte] = {
    v.toByteArray.reverse.padTo(byteLen, 0.toByte)
  }
}

/** Driver components in sim */
object SimDriver {

  val axiMemSimConf = AxiMemorySimConfig(
    maxOutstandingReads = 128,
    maxOutstandingWrites = 128,
    readResponseDelay = 3,
    writeResponseDelay = 3
  )

  def instAxiMemSim(axi: Axi4, clockDomain: ClockDomain, memCtx: Option[Array[Byte]]) : AxiMemorySim = {
    val mem = AxiMemorySim(axi, clockDomain, axiMemSimConf)
    mem.start()
    memCtx match {
      case Some(ctx) => {
        mem.memory.writeArray(0, ctx)
      }
      case None => mem.memory.writeArray(0, Array.fill[Byte](1<<22)(0.toByte))
    }
    mem
  }

  // Axi4Lite
  def setAxi4LiteReg(cd: ClockDomain, bus: AxiLite4, addr: Int, data: Int): Unit ={
    val awa = fork {
      bus.aw.addr #= addr
      bus.w.data #= data
      bus.w.strb #= 0xF // strb for 4 Bytes
      bus.aw.valid #= true
      bus.w.valid #= true
      cd.waitSamplingWhere(bus.aw.ready.toBoolean && bus.w.ready.toBoolean)
      bus.aw.valid #= false
      bus.w.valid #= false
    }

    val b = fork {
      bus.b.ready #= true
      cd.waitSamplingWhere(bus.b.valid.toBoolean)
      bus.b.ready #= false
    }
    awa.join()
    b.join()
  }

  def readAxi4LiteReg(cd: ClockDomain, bus: AxiLite4, addr: Int): BigInt ={
    var data: BigInt = 1
    val ar = fork{
      bus.ar.addr #= addr
      bus.ar.valid #= true
      cd.waitSamplingWhere(bus.ar.ready.toBoolean)
      bus.ar.valid #= false
    }

    val r = fork{
      bus.r.ready #= true
      cd.waitSamplingWhere(bus.r.valid.toBoolean)
      data = bus.r.data.toBigInt
    }
    ar.join()
    r.join()
    data
  }

  // Axi4
  def axiMonitor(cd: ClockDomain, bus: Axi4): Unit = {
    fork{while(true){
      cd.waitSamplingWhere(bus.readCmd.isFire)
      println(s"[AXI RdCmd]: ReadAddr: ${bus.readCmd.addr.toBigInt}")}}

    fork{while(true){
      cd.waitSamplingWhere(bus.readRsp.isFire)
      println(s"[AXI RdResp]: ReadData: ${bus.readRsp.data.toBigInt}")}}

    fork{while(true){
      cd.waitSamplingWhere(bus.writeCmd.isFire)
      println(s"[AXI WrCmd]: WrAddr: ${bus.writeCmd.addr.toBigInt}")}}

    fork{while(true){
      cd.waitSamplingWhere(bus.writeData.isFire)
      println(s"[AXI WrData]: WrData: ${bus.writeData.data.toBigInt}")}}
  }

  implicit class StreamUtils[T <: Data](stream: Stream[T]) {
    def isFire: Boolean = {
      stream.valid.toBoolean && stream.ready.toBoolean
    }

    def simIdle(): Unit = {
      stream.valid #= false
    }

    def simBlocked(): Unit = {
      stream.ready #= true
    }
  }

  // TODO: how to constraint the type scope for specific method in the class? Then I can combine these above.
  implicit class StreamUtilsBits[T <: Bits](stream: Stream[T]) {
    
    def sendData[T1 <: BigInt](cd: ClockDomain, data: T1): Unit = {
      stream.valid #= true
      stream.payload #= data
      cd.waitSamplingWhere(stream.ready.toBoolean)
      stream.valid #= false
    }

    def recvData(cd: ClockDomain): BigInt = {
      stream.ready #= true
      cd.waitSamplingWhere(stream.valid.toBoolean)
      stream.payload.toBigInt
    }

    def <<#(that: Stream[T]): Unit = {
      stream.payload #= that.payload.toBigInt
      stream.valid #= that.valid.toBoolean
      that.ready #= stream.ready.toBoolean
    }

    def #>>(that: Stream[T]) = {
      that <<# stream
    }
  }
  

  /** Pipe stream in sim with given latency
   * Stream[T] is hardware may not be used in sim env
   */
  def streamDelayPipe[T <: Bits](cd: ClockDomain, streamIn: Stream[T], streamOut: Stream[T], lat: Int) = {

    var cycle = 0
    val payloadQ, tsQ = mutable.Queue[BigInt]()

    // clk counter
    fork {
      while(true){
        cd.waitSampling()
        cycle += 1
      }
    }

    fork {
      while(true){
        payloadQ.enqueue(streamIn.recvData(cd))
        tsQ.enqueue(cycle)
        // send monitor can be put here
      }
    }

    fork {
      while(true){
        if(tsQ.nonEmpty) {
          if(cycle > (tsQ.front + lat)){
            streamOut.sendData(cd, payloadQ.dequeue())
            tsQ.dequeue()
          } else {
            streamOut.simIdle()
            cd.waitSampling()
          }
        } else {
          streamOut.simIdle()
          cd.waitSampling()
        }
      }
    }

  }
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  

  

}