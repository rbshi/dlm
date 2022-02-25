package hwsys.dlm.test

import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.Axi4
import spinal.lib.{master, slave}
import hwsys.dlm._
import hwsys.sim._
import hwsys.util.Helpers._

class TwoNodeTop(implicit sysConf: SysConfig) extends Component {
  val n0, n1 = new NodeWrap()
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
        n.arbFlow.sendArb.io.simPublic()
        n.arbFlow.respArb.io.simPublic()
        n.arbFlow.reqDisp.io.simPublic()
        n.arbFlow.recvDisp.io.simPublic()
      }
      dut
    }.doSim("nodewrapsim", 99) { dut =>

      dut.clockDomain.forkStimulus(period = 10)

      // params
      val txnLen = 16
      val txnCnt = 256
      val txnMaxLen = sysConf.maxTxnLen-1

      Seq(dut.n0, dut.n1).zipWithIndex.foreach {case (n, idx) =>
        for (iTxnMan <- 0 until sysConf.nTxnMan) {

          // cmd memory
          val fNId = (i: Int, j: Int) => idx
          val fCId = (i: Int, j: Int) => 0
          // for different txnMan, there'll be a tIdOffs in txnEntrySimInt
          val fTId = (i: Int, j: Int) => i*txnLen + j
          val fLkAttr = (i: Int, j: Int) => 0
          val fWLen = (i: Int, j: Int) => 0

          val txnCtx = SimInit.txnEntrySimInt(txnCnt, txnLen, txnMaxLen, 0)(fNId, fCId, fTId, fLkAttr, fWLen).toArray
          SimDriver.instAxiMemSim(n.io.cmdAxi(iTxnMan), dut.clockDomain, Some(txnCtx))

          // data memory
          n.io.axi.foreach(SimDriver.instAxiMemSim(_, dut.clockDomain, None))
        }
      }

      val l2rSendNet = SimDriver.streamDelayPipe(dut.clockDomain, dut.n0.arbFlow.sendArb.io.sendQ, dut.n1.arbFlow.reqDisp.io.reqQ, 1000)
      val l2rRecvNet = SimDriver.streamDelayPipe(dut.clockDomain, dut.n1.arbFlow.respArb.io.respQ, dut.n0.arbFlow.recvDisp.io.recvQ, 1000)

      val r2lSendNet = SimDriver.streamDelayPipe(dut.clockDomain, dut.n1.arbFlow.sendArb.io.sendQ, dut.n0.arbFlow.reqDisp.io.reqQ, 1000)
      val r2lRecvNet = SimDriver.streamDelayPipe(dut.clockDomain, dut.n0.arbFlow.respArb.io.respQ, dut.n1.arbFlow.recvDisp.io.recvQ, 1000)

      Seq(dut.n0, dut.n1).zipWithIndex.foreach {case (n, idx) =>
        n.io.nodeId #= idx
        n.io.start #= false
        n.io.txnNumTotal #= txnCnt
        n.io.cmdAddrOffs.foreach(_ #= 0)
      }

      // wait the fifo (empty_ptr) to reset
      dut.clockDomain.waitSampling(sysConf.nLock/sysConf.nLtPart+1000)

      // start
      dut.n0.io.start #= true
      dut.n1.io.start #= true
      dut.clockDomain.waitSampling()
      dut.n0.io.start #= false
      dut.n1.io.start #= false

      dut.n0.io.done.foreach(a => dut.clockDomain.waitSamplingWhere(a.toBoolean))
      dut.n1.io.done.foreach(a => dut.clockDomain.waitSamplingWhere(a.toBoolean))

      Seq(dut.n0, dut.n1).foreach { n =>
        Seq(n.io.cntTxnLd, n.io.cntTxnCmt, n.io.cntTxnAbt, n.io.cntClk).foreach { sigV =>
          sigV.foreach { sig =>
            println(s"${sig.getName()} = ${sig.toBigInt}")
          }
        }
      }

    }
  }
}