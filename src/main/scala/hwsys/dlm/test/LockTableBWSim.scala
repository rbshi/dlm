package hwsys.dlm.test

import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.Axi4
import spinal.lib.master
import hwsys.dlm._
import hwsys.sim._
import hwsys.util.Helpers._

object LockTableBWSim {

  def main(args: Array[String]) = {

    val sysConf = new SysConfig {
      override val nNode  : Int = 1
      override val nCh    : Int = 1
      override val nTxnMan: Int = 1
      override val nLtPart: Int = 1
      override val nLock  : Int = (((1 << 10) << 10) << 8) >> 6
    }

    SimConfig.withWave.compile {
      val dut = new LockTableBW(sysConf)
      dut
    }.doSim("llbwdut", 99) { dut =>
      dut.io.lkReq.valid #= false

      dut.clockDomain.forkStimulus(period = 10)
      dut.clockDomain.waitSampling(2 << sysConf.wHtTable + 100) // wait for initialization of empty ptr storage


      var reqQ  = scala.collection.mutable.Queue.empty[(Int, Int, Int, Int, Int, Int, Int, LkT.E, Boolean, Boolean, Boolean, Int, Int)]
      val lkReq = fork {
        for (k <- 0 until 8) {
          reqQ += ((0, 0, k%4, 0, 0, 0, 0, LkT.wr, false, false, false, k, 0)) // lkGet
        }
        for (k <- 0 until 8) {
          reqQ += ((0, 0, k%4, 0, 0, 0, 0, LkT.wr, true, false, false, k, 0)) // lkRlse
        }

        sendCmd()

        def sendCmd(): Unit = {
          while (reqQ.nonEmpty) {
            val (nId, cId, tId, tabId, snId, txnManId, txnId, lkType, lkRelease, txnTimeOut, txnAbt, lkIdx, wLen) = reqQ.front
            dut.io.lkReq.valid #= true

            dut.io.lkReq.nId #= nId
            dut.io.lkReq.cId #= cId
            dut.io.lkReq.tId #= tId
            dut.io.lkReq.tabId #= tabId
            dut.io.lkReq.snId #= snId
            dut.io.lkReq.txnManId #= txnManId
            dut.io.lkReq.txnId #= txnId
            dut.io.lkReq.lkType #= lkType
            dut.io.lkReq.lkRelease #= lkRelease
            dut.io.lkReq.txnTimeOut #= txnTimeOut
            dut.io.lkReq.txnAbt #= txnAbt
            dut.io.lkReq.lkIdx #= lkIdx
            dut.io.lkReq.wLen #= wLen

            dut.clockDomain.waitSampling()

            if (dut.io.lkReq.valid.toBoolean && dut.io.lkReq.ready.toBoolean) {
              println("[lkReq]  lkIdx:" + lkIdx + "\ttId:" + tId)
              reqQ.dequeue()
            }
          }
          dut.io.lkReq.valid #= false
        }
      }

      val lkResp = fork {
        dut.io.lkResp.ready #= true
        while (true) {
          dut.clockDomain.waitSampling()
          if (dut.io.lkResp.valid.toBoolean) {
            println("[lkResp] lkIdx:" + dut.io.lkResp.lkIdx.toBigInt + "\trespType:" + dut.io.lkResp.respType.toBigInt)
          }
        }
      }

      lkReq.join()
    }
  }
}
