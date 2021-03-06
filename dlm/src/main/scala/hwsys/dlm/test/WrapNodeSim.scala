package hwsys.dlm.test

import spinal.core._
import spinal.core.sim._
import hwsys.dlm._
import hwsys.sim._
import hwsys.util.Helpers._

// Two nodes + Q Flow
class TwoNodeTop(implicit sysConf: SysConfig) extends Component {
  val io = Array.fill(2)(new NodeFlowIO())
  val n0, n1 = new WrapNode()
  Seq(n0, n1).zipWithIndex.foreach { case(n, idx) =>
    n.io.connectAllByName(io(idx))
  }
}

object WrapNodeSim extends App {

  implicit val sysConf = new SysConfig {
    override val nNode: Int = 2
    override val nCh: Int = 1
    override val nTxnMan: Int = 1
    override val nLtPart: Int = 8
    override val nLock: Int = (((1<<10)<<10)<<8)>>6
}

  SimConfig.withWave.compile {
    val dut = new TwoNodeTop()

    Seq(dut.n0, dut.n1).foreach { n =>
      n.io.simPublic()
    }
    dut
  }.doSim("wrapnodesim", 99) {simulate}

  def simulate(dut: TwoNodeTop): Unit = {

    dut.clockDomain.forkStimulus(period = 10)

    // params
    val txnLen = 16
    val txnCnt = 64
    val txnMaxLen = sysConf.maxTxnLen - 1

    for (idx <- 0 until 2) {
      for (iTxnMan <- 0 until sysConf.nTxnMan) {
        // cmd memory
        val fNId = (i: Int, j: Int) => 0
        val fCId = (i: Int, j: Int) => 0
        // for different txnMan, there'll be a tIdOffs in txnEntrySimInt
        val fTId = (i: Int, j: Int) => i * txnLen + j
        val fLkAttr = (i: Int, j: Int) => 2
        val fWLen = (i: Int, j: Int) => 0
        val txnCtx = SimInit.txnEntrySimInt(txnCnt, txnLen, txnMaxLen, 0)(fNId, fCId, fTId, fLkAttr, fWLen).toArray
        SimDriver.instAxiMemSim(dut.io(idx).cmdAxi(iTxnMan), dut.clockDomain, Some(txnCtx))
        // data memory
        SimDriver.instAxiMemSim(dut.io(idx).axi(iTxnMan), dut.clockDomain, None)
      }
      for (iTxnAgent <- sysConf.nTxnMan until sysConf.nTxnMan + sysConf.nNode - 1) {
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
    dut.clockDomain.waitSampling(sysConf.nLock / sysConf.nLtPart + 1000)

    // start
    for (idx <- 0 until 2) {
      dut.io(idx).start #= true
      dut.clockDomain.waitSampling()
      dut.io(idx).start #= false
    }

    dut.io.foreach(_.done.foreach(a => dut.clockDomain.waitSamplingWhere(a.toBoolean)))
    dut.io.zipWithIndex.foreach { case (n, idx) =>
      Seq(n.cntTxnLd, n.cntTxnCmt, n.cntTxnAbt, n.cntClk).foreach { sigV =>
        sigV.foreach { sig =>
          println(s"Node[$idx]  ${sig.getName()} = ${sig.toBigInt}")
        }
      }
    }
  }
}