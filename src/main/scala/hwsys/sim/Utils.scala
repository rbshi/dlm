// Functions used for simulation

package hwsys.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.Axi4
import spinal.lib.Stream

import scala.math._
import spinal.lib.bus.amba4.axilite.AxiLite4


/** Behavior primitives in sim */
object Utils{

  // Axi4Lite
  def setAxi4LiteReg(dut: Component, bus: AxiLite4, addr: Int, data: Int): Unit ={
    val awa = fork {
      bus.aw.addr #= addr
      bus.w.data #= data
      bus.w.strb #= 0xF // strb for 4 Bytes
      bus.aw.valid #= true
      bus.w.valid #= true
      dut.clockDomain.waitSamplingWhere(bus.aw.ready.toBoolean && bus.w.ready.toBoolean)
      bus.aw.valid #= false
      bus.w.valid #= false
    }

    val b = fork {
      bus.b.ready #= true
      dut.clockDomain.waitSamplingWhere(bus.b.valid.toBoolean)
      bus.b.ready #= false
    }
    awa.join()
    b.join()
  }

  def readAxi4LiteReg(dut: Component, bus: AxiLite4, addr: Int): BigInt ={
    var data: BigInt = 1
    val ar = fork{
      bus.ar.addr #= addr
      bus.ar.valid #= true
      dut.clockDomain.waitSamplingWhere(bus.ar.ready.toBoolean)
      bus.ar.valid #= false
    }

    val r = fork{
      bus.r.ready #= true
      dut.clockDomain.waitSamplingWhere(bus.r.valid.toBoolean)
      data = bus.r.data.toBigInt
    }
    ar.join()
    r.join()
    return data
  }

  // Axi4
  def axiMonitor(dut: Component, bus: Axi4): Unit = {
    fork{while(true){
      dut.clockDomain.waitSamplingWhere(isFire(bus.readCmd))
      println(s"[AXI RdCmd]: ReadAddr: ${bus.readCmd.addr.toBigInt}")}}

    fork{while(true){
      dut.clockDomain.waitSamplingWhere(isFire(bus.readRsp))
      println(s"[AXI RdResp]: ReadData: ${bus.readRsp.data.toBigInt}")}}

    fork{while(true){
      dut.clockDomain.waitSamplingWhere(isFire(bus.writeCmd))
      println(s"[AXI WrCmd]: WrAddr: ${bus.writeCmd.addr.toBigInt}")}}

    fork{while(true){
      dut.clockDomain.waitSamplingWhere(isFire(bus.writeData))
      println(s"[AXI WrData]: WrData: ${bus.writeData.data.toBigInt}")}}
  }

  // Stream
  def isFire[T <: Data](bus: Stream[T]): Boolean = {
    return bus.valid.toBoolean && bus.ready.toBoolean
  }

//  def printSig(sig: Seq[String], bundle: Bundle): Unit = {
//    for (e <- sig) {
//      val v = bundle.find(e)
//      println(s"Reg[e] = $v")
//    }
//  }


}





