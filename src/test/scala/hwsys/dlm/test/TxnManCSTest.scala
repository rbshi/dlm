package hwsys.dlm.test

import utest.{Tests, TestSuite, test}
import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.Axi4
import spinal.lib.master
import hwsys.dlm._
import hwsys.sim._
import hwsys.util.Helpers._

/*
* connect the txnManCS to a lt
* */
class TxnManCSTop(sysConf: SysConfig) extends Component {

  val txnMan = new TxnManCS(sysConf)
  val ltMCh = new LtTop(sysConf)

  val io = new Bundle {
    val axi = master(Axi4(sysConf.axiConf))
    val cmdAxi = master(Axi4(sysConf.axiConf))
    val start = in Bool()
    val txnNumTotal = in UInt(32 bits)
    val cmdAddrOffs = in UInt(32 bits) //NOTE: unit size 64B
  }

  io.start <> txnMan.io.start
  io.txnNumTotal <> txnMan.io.txnNumTotal
  io.cmdAddrOffs <> txnMan.io.cmdAddrOffs

  io.axi <> txnMan.io.axi
  io.cmdAxi <> txnMan.io.cmdAxi
  txnMan.io.lkReqLoc <> ltMCh.io.lt(0).lkReq
  txnMan.io.lkRespLoc <> ltMCh.io.lt(0).lkResp

  txnMan.io.nodeId := 0
  txnMan.io.txnManId := 0

  ltMCh.io.nodeId := 0


  // tieOff
  Seq(txnMan.io.lkReqRmt, txnMan.io.wrRmt, txnMan.io.lkRespRmt, txnMan.io.rdRmt).foreach(_.tieOff(true))

}

class TwoNodeDirect(sysConf: SysConfig) extends Component {

  val n0 = new Area {

    val io = new Bundle {
      val axi = master(Axi4(sysConf.axiConf))
      val cmdAxi = master(Axi4(sysConf.axiConf))
      val start = in Bool()
      val txnNumTotal = in UInt (32 bits)
      val cmdAddrOffs = in UInt (32 bits) //NOTE: unit size 64B
    }
    val txnMan = new TxnManCS(sysConf)
    val ltMCh = new LtTop(sysConf)

    txnMan.io.axi <> io.axi
    txnMan.io.cmdAxi <> io.cmdAxi
    txnMan.io.start <> io.start
    txnMan.io.txnNumTotal <> io.txnNumTotal
    txnMan.io.cmdAddrOffs <> io.cmdAddrOffs

    txnMan.io.txnManId := 0
    txnMan.io.nodeId := 0
    txnMan.io.lkReqLoc <> ltMCh.io.lt(0).lkReq
    txnMan.io.lkRespLoc <> ltMCh.io.lt(0).lkResp

    ltMCh.io.nodeId := 0

    Seq(ltMCh.io.lt(1).lkReq, ltMCh.io.lt(1).lkResp).foreach(_.tieOff(true))

  }

  val n1 = new Area {

    val io = new Bundle {
      val axi = master(Axi4(sysConf.axiConf))
    }

    val txnAgt = new TxnAgent(sysConf)
    val ltMCh = new LtTop(sysConf)

    txnAgt.io.axi <> io.axi
    txnAgt.io.ltReq <> ltMCh.io.lt(1).lkReq
    txnAgt.io.ltResp <> ltMCh.io.lt(1).lkResp
    ltMCh.io.nodeId := 1

    Seq(ltMCh.io.lt(0).lkReq, ltMCh.io.lt(0).lkResp).foreach(_.tieOff(true))

  }

  // directly connect the txnMan <> txnAgent
  n0.txnMan.io.lkReqRmt <> n1.txnAgt.io.lkReq
  n0.txnMan.io.lkRespRmt <> n1.txnAgt.io.lkResp
  n0.txnMan.io.wrRmt <> n1.txnAgt.io.wrData
  n0.txnMan.io.rdRmt <> n1.txnAgt.io.rdData
}

@deprecated("Put things in sim first")
object TxnManCSTest extends TestSuite {

  implicit val sysConf = new SysConfig {
    override val nNode: Int = 2
    override val nCh: Int = 4
    override val nLock: Int = 4096*8
    override val nTxnMan: Int = 1
    override val nLtPart: Int = 1
  }

  def txnManCS(dut: TxnManCSTop): Unit = {
    // params
    val txnLen = 8
    val txnCnt = 16
    val txnMaxLen = sysConf.maxTxnLen-1

    dut.clockDomain.forkStimulus(period = 10)

    // data memory
    val axiMem = SimDriver.instAxiMemSim(dut.io.axi, dut.clockDomain, None)

    // cmd memory
    val txnCtx = SimInit.txnEntrySimInt(txnCnt, txnLen, txnMaxLen).toArray
    val cmdAxiMem = SimDriver.instAxiMemSim(dut.io.cmdAxi, dut.clockDomain, Some(txnCtx))

    dut.io.start #= false
    // wait the fifo (empty_ptr) to reset
    dut.clockDomain.waitSampling(2000)

    // config
    dut.io.cmdAddrOffs #= 0
    dut.io.txnNumTotal #= txnCnt

    // start
    dut.io.start #= true
    dut.clockDomain.waitSampling()
    dut.io.start #= false

    // dut.clockDomain.waitSampling(64000)

    dut.clockDomain.waitSamplingWhere(dut.txnMan.io.done.toBoolean)

    println(s"[txnMan] cntTxnCmt: ${dut.txnMan.io.cntTxnCmt.toBigInt}")
    println(s"[txnMan] cntTxnAbt: ${dut.txnMan.io.cntTxnAbt.toBigInt}")
    println(s"[txnMan] cntTxnLd: ${dut.txnMan.io.cntTxnLd.toBigInt}")
    println(s"[txnMan] cntClk: ${dut.txnMan.io.cntClk.toBigInt}")

  }

  def twoNodeDirect(dut: TwoNodeDirect): Unit = {
    // params
    val txnLen = 32
    val txnCnt = 128
    val txnMaxLen = sysConf.maxTxnLen-1

    dut.clockDomain.forkStimulus(period = 10)

    // cmd memory
    val txnCtx = SimInit.txnEntrySimInt(txnCnt, txnLen, txnMaxLen).toArray
    val cmdAxiMem = SimDriver.instAxiMemSim(dut.n0.io.cmdAxi, dut.clockDomain, Some(txnCtx))

    // data memory
    val n0Axi = SimDriver.instAxiMemSim(dut.n0.io.axi, dut.clockDomain, None)
    val n1Axi = SimDriver.instAxiMemSim(dut.n1.io.axi, dut.clockDomain, None)

    dut.n0.io.start #= false
    // wait the fifo (empty_ptr) to reset
    dut.clockDomain.waitSampling(2000)

    // config
    dut.n0.io.cmdAddrOffs #= 0
    dut.n0.io.txnNumTotal #= txnCnt

    // start
    dut.n0.io.start #= true
    dut.clockDomain.waitSampling()
    dut.n0.io.start #= false

    // dut.clockDomain.waitSampling(64000)

    dut.clockDomain.waitSamplingWhere(dut.n0.txnMan.io.done.toBoolean)

    println(s"[txnMan] cntTxnCmt: ${dut.n0.txnMan.io.cntTxnCmt.toBigInt}")
    println(s"[txnMan] cntTxnAbt: ${dut.n0.txnMan.io.cntTxnAbt.toBigInt}")
    println(s"[txnMan] cntTxnLd: ${dut.n0.txnMan.io.cntTxnLd.toBigInt}")
    println(s"[txnMan] cntClk: ${dut.n0.txnMan.io.cntClk.toBigInt}")

  }

//  test("txnman_cs") {
//    SimConfig.withWave.compile {
//      val dut = new TxnManCSTop(sysConf)
//      dut.txnMan.io.simPublic()
//      dut
//    }.doSim("txnman_cs", 99)(txnManCS)
//  }

  def tests = Tests {
    test("twonode_direct_conn") {
      SimConfig.withWave.compile {
        val dut = new TwoNodeDirect(sysConf)
        dut.n0.txnMan.io.simPublic()
        dut
      }.doSim("twonode_direct_conn", 99)(twoNodeDirect)
    }
  }

}