package hwsys.coyote

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axilite.{AxiLite4, AxiLite4Config, AxiLite4SlaveFactory}
import spinal.lib.fsm.StateMachine
import util._

import hwsys.util._
import hwsys.util.Helpers._

import scala.language.postfixOps

/*
* Bypass
* */
case class WrapRdmaFlow() extends Component with RenameIO {

  val io = new Bundle {
    // axi-lite control
    val axi_ctrl = slave(AxiLite4(AxiLite4Config(64, 64)))
    // host data io
    val hostd = new HostDataIO
    // rdma
    val rdma_0 = new RdmaIO
  }

  io.hostd.tieOff()

  val q_sink = Stream(Bits(512 bits))
  val q_src = Stream(Bits(512 bits))
  q_sink.valid.clear()
  q_src.ready.clear()

  val rfMstr = RdmaFlow(true)
  val rfSlve = RdmaFlow(false)

  // axilite control registers
  val ctlReg = new AxiLite4SlaveFactory(io.axi_ctrl)

  val mode       = ctlReg.rwInPort(UInt(3 bits),  0<<3, 0).init(0)
  val pktLenPow  = ctlReg.rwInPort(UInt(4 bits), 1<<3, 0).init(0)
  val pktCntPow  = ctlReg.rwInPort(UInt(4 bits), 2<<3, 0).init(0)
  val itvlPush   = ctlReg.rwInPort(UInt(16 bits), 3<<3, 0).init(0)
  val itvlPop    = ctlReg.rwInPort(UInt(16 bits), 4<<3, 0).init(0)
  val nOnFly     = ctlReg.rwInPort(UInt(32 bits), 5<<3, 0).init(0)
  val clkTiO     = ctlReg.rwInPort(UInt(64 bits), 6<<3, 0).init(0)
  val qpn        = ctlReg.rwInPort(UInt(10 bits), 7<<3, 0).init(0)

  val clkTimer = Reg(UInt(64 bits)).init(0)
  ctlReg.read(clkTimer, 8<<3, 0)
  ctlReg.read(rfMstr.io.cntSent, 9<<3, 0)
  ctlReg.read(rfMstr.io.cntRecv, 10<<3, 0)
  ctlReg.read(rfSlve.io.cntSent, 11<<3, 0)
  ctlReg.read(rfSlve.io.cntRecv, 12<<3, 0)

  ctlReg.read(rfMstr.io.dbg, 13<<3, 0)
  ctlReg.read(rfSlve.io.dbg, 14<<3, 0)

  val cntWord    = ctlReg.rwInPort(UInt(64 bits), 15<<3, 0).init(0)


  val isMstr = mode(1)
  val isEn = mode(0)

  when(isMstr){
    io.rdma_0 <> rfMstr.io.rdma
    q_sink >> rfMstr.io.q_sink
    q_src << rfMstr.io.q_src
    rfSlve.io.rdma.tieOffFlip()
    rfSlve.io.q_sink.setIdle()
    rfSlve.io.q_src.setBlocked()
  } otherwise{
    io.rdma_0 <> rfSlve.io.rdma
    q_sink >> rfSlve.io.q_sink
    q_src << rfSlve.io.q_src
    rfMstr.io.rdma.tieOffFlip()
    rfMstr.io.q_sink.setIdle()
    rfMstr.io.q_src.setBlocked()
  }

  rfMstr.io.en := isMstr && isEn
  val one = UInt()
  one := 1
  rfMstr.io.len := (U(1) << pktLenPow)
  rfMstr.io.qpn := qpn
  rfMstr.io.nOnFly := nOnFly

  rfSlve.io.en := ~isMstr && isEn
  rfSlve.io.len := (U(1) << pktLenPow)
  rfSlve.io.qpn := qpn
  rfSlve.io.nOnFly := nOnFly

  val itvlPopCnt, itvlPushCnt = Reg(UInt(16 bits)).init(0)

  // wordCnt
  val cntPop, cntPush = Reg(UInt(64 bits)).init(0)
//  val cntWord = U(1) << (pktCntPow + pktLenPow - 6)

  when(isMstr){
    q_sink.payload := cntPush.asBits.resized
    when(itvlPushCnt >= itvlPush && cntPush < cntWord)(q_sink.valid.set())
    when(q_sink.fire)(itvlPushCnt.clearAll()) otherwise (itvlPushCnt := itvlPushCnt + 1)
    when(q_sink.fire)(cntPush := cntPush + 1)

    when(itvlPopCnt >= itvlPop && cntPop < cntWord)(q_src.ready.set())
    when(q_src.fire)(itvlPopCnt.clearAll()) otherwise (itvlPopCnt := itvlPopCnt + 1)
    when(q_src.fire)(cntPop := cntPop + 1)
  } otherwise{
    q_src.continueWhen(itvlPopCnt >= itvlPop && cntPop < cntWord) >> q_sink
    when(q_src.fire)(itvlPopCnt.clearAll()) otherwise (itvlPopCnt := itvlPopCnt + 1)
    when(q_src.fire)(cntPop := cntPop + 1)
  }

  when(isEn && ~mode(2))(clkTimer := clkTimer + 1)
  when(~isEn){
    for (e <- List(clkTimer, cntPush, cntPop))
      e.clearAll()
  }

  val cntSent, cntRecv = UInt(32 bits)
  when(isMstr){
    cntSent := rfMstr.io.cntSent
    cntRecv := rfMstr.io.cntRecv
  } otherwise{
    cntSent := rfSlve.io.cntSent
    cntRecv := rfSlve.io.cntRecv
  }

  when((cntSent===cntWord && cntRecv===cntWord) || clkTimer===clkTiO){
    mode(2) := True
  }

}


object GenWrapRdmaFlow {
  def main(args: Array[String]): Unit = {
    SpinalConfig(
      targetDirectory = "generated_rtl/",
      defaultConfigForClockDomains = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = LOW)
    ).generateVerilog{
      val top = WrapRdmaFlow()
      top.renameIO()
      top.setDefinitionName("rdmaflow_0")
      top
    }
  }
}
