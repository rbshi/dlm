package hwsys.dlm.test

import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.Axi4
import spinal.lib.{master, slave}
import hwsys.dlm._
import hwsys.sim._
import hwsys.util.Helpers._

class NodeIO(implicit sysConf: SysConfig) extends Bundle {

  val nodeId = in UInt(sysConf.wNId bits)
  val axi = Vec(master(Axi4(sysConf.axiConf)), sysConf.nTxnMan + sysConf.nNode -1)
  val cmdAxi = Vec(master(Axi4(sysConf.axiConf)), sysConf.nTxnMan)
  val start = in Bool()
  val txnNumTotal = in UInt(32 bits)
  val cmdAddrOffs = in Vec(UInt(32 bits), sysConf.nTxnMan) //NOTE: unit size 64B

  val sendQ = master Stream Bits(512 bits)
  val respQ = master Stream Bits(512 bits)
  val reqQ = slave Stream Bits(512 bits)
  val recvQ = slave Stream Bits(512 bits)
}


class TwoNodeTop(implicit sysConf: SysConfig) extends Component {


  val io = Array.fill(2)(new NodeIO())
  val n0, n1 = new NodeWrap()

  Seq(n0, n1).zipWithIndex.foreach { case(n, idx) =>
    n.io.nodeId <> io(idx).nodeId
    n.io.axi <> io(idx).axi
    n.io.cmdAxi <> io(idx).cmdAxi
    n.io.start <> io(idx).start
    n.io.txnNumTotal <> io(idx).txnNumTotal
    n.io.cmdAddrOffs <> io(idx).cmdAddrOffs

    n.io.sendQ <> io(idx).sendQ
    n.io.respQ <> io(idx).respQ
    n.io.reqQ  <> io(idx).reqQ
    n.io.recvQ <> io(idx).recvQ
  }

}

object NodeWrapSim {

  def main(args: Array[String]): Unit = {

    implicit val sysConf = new SysConfig {
      override val nNode: Int = 2
      override val nCh: Int = 1
      override val nTxnMan: Int = 1
      override val nLtPart: Int = 1
      override val nLock: Int = 4096 * nLtPart
    }

    SimConfig.withWave.compile {
      val dut = new TwoNodeTop()

      Seq(dut.n0, dut.n1).foreach { n =>
        n.io.simPublic()
      }
      dut
    }.doSim("nodewrapsim", 99) { dut =>

      dut.clockDomain.forkStimulus(period = 10)

      // params
      val txnLen = 16
      val txnCnt = 256
      val txnMaxLen = sysConf.maxTxnLen-1

      for (idx <- 0 until 2) {
        for (iTxnMan <- 0 until sysConf.nTxnMan) {
          // cmd memory
          val fNId = (i: Int, j: Int) => 0
          val fCId = (i: Int, j: Int) => 0
          // for different txnMan, there'll be a tIdOffs in txnEntrySimInt
          val fTId = (i: Int, j: Int) => j
          val fLkAttr = (i: Int, j: Int) => 1
          val fWLen = (i: Int, j: Int) => 0
          val txnCtx = SimInit.txnEntrySimInt(txnCnt, txnLen, txnMaxLen, 0)(fNId, fCId, fTId, fLkAttr, fWLen).toArray
          SimDriver.instAxiMemSim(dut.io(idx).cmdAxi(iTxnMan), dut.clockDomain, Some(txnCtx))
          // data memory
          SimDriver.instAxiMemSim(dut.io(idx).axi(iTxnMan), dut.clockDomain, None)
        }
        for (iTxnAgent <- sysConf.nTxnMan until sysConf.nTxnMan + sysConf.nNode -1 ) {
          // data memory
          SimDriver.instAxiMemSim(dut.io(idx).axi(iTxnAgent), dut.clockDomain, None)
        }
      }

      val l2rSendNet = SimDriver.streamDelayPipe(dut.clockDomain, dut.io(0).sendQ, dut.io(1).reqQ, 1000)
      val l2rRecvNet = SimDriver.streamDelayPipe(dut.clockDomain, dut.io(1).respQ, dut.io(0).recvQ, 1000)

      val r2lSendNet = SimDriver.streamDelayPipe(dut.clockDomain, dut.io(1).sendQ, dut.io(0).reqQ, 1000)
      val r2lRecvNet = SimDriver.streamDelayPipe(dut.clockDomain, dut.io(0).respQ, dut.io(1).recvQ, 1000)

      for (idx <- 0 until 2) {
        dut.io(idx).nodeId #= idx
        dut.io(idx).start #= false
        dut.io(idx).txnNumTotal #= txnCnt
        dut.io(idx).cmdAddrOffs.foreach(_ #= 0)
      }

      // wait the fifo (empty_ptr) to reset
      dut.clockDomain.waitSampling(sysConf.nLock/sysConf.nLtPart+1000)

      // start
      for (idx <- 0 until 2) {
        dut.io(idx).start #= true
        dut.clockDomain.waitSampling()
        dut.io(idx).start #= false
      }

      dut.n0.io.done.foreach(a => dut.clockDomain.waitSamplingWhere(a.toBoolean))
      dut.n1.io.done.foreach(a => dut.clockDomain.waitSamplingWhere(a.toBoolean))

      Seq(dut.n0, dut.n1).zipWithIndex.foreach { case(n, idx) =>
        Seq(n.io.cntTxnLd, n.io.cntTxnCmt, n.io.cntTxnAbt, n.io.cntClk).foreach { sigV =>
          sigV.foreach { sig =>
            println(s"Node[$idx]  ${sig.getName()} = ${sig.toBigInt}")
          }
        }
      }

    }
  }
}