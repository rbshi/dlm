package hwsys.dlm.test

import spinal.core._
import spinal.lib.bus.amba4.axi._
import hwsys.util._
import hwsys.sim._
import hwsys.dlm._


case class TxnEntrySim(
                        nId: Int,
                        cId: Int,
                        tId: Int,
                        tabId: Int,
                        lkType: Int,
                        wLen: Int
                      )(implicit sysConf: SysConfig) extends MemStructSim {
  override def asBytes = SimConversions.txnEntrySimToBytes(this)
}


// Conversion methods
object SimConversions {

  def txnEntrySimToBytes(req: TxnEntrySim)(implicit sysConf: SysConfig) : Seq[Byte] = {
    val vBigInt = req.nId +
      (req.cId << (sysConf.wNId)) +
      (req.tId << (sysConf.wNId+sysConf.wCId)) +
      (req.tabId << (sysConf.wNId+sysConf.wCId+sysConf.wTId)) +
      (req.lkType << (sysConf.wNId+sysConf.wCId+sysConf.wTId+sysConf.wTabId)) +
      (req.wLen << (sysConf.wNId+sysConf.wCId+sysConf.wTId+sysConf.wTabId+sysConf.wLkType))

//    println(s"txnBigInt=${vBigInt.toHexString}")

    MemStructSim.bigIntToBytes(vBigInt, 8)
  }

}

// Init methods
object SimInit {

  def txnEntrySimInt(txnCnt: Int, txnLen: Int, txnMaxLen: Int, tIdOffs: Int = 0)(fNId: (Int, Int) => Int, fCId: (Int, Int) => Int, fTId: (Int, Int) => Int, fLk: (Int, Int) => Int, fWLen: (Int, Int) => Int)(implicit sysConf: SysConfig): Seq[Byte] = {
    var txnMem = Seq.empty[Byte]
    for (i <- 0 until txnCnt) {
      // txnHd
      txnMem = txnMem ++ MemStructSim.bigIntToBytes(BigInt(txnLen), 8)
      // lkInsTab
      // txnMem = txnMem ++ TxnEntrySim(fNId(i, 0), fCId(i, 0), fTId(i, 0) + tIdOffs, 0, 3, fWLen(i, 0)).asBytes
      for (j <- 0 until txnLen) {
        txnMem = txnMem ++ TxnEntrySim(fNId(i, j), fCId(i, j), fTId(i, j) + tIdOffs, 0, fLk(i, j), fWLen(i, j)).asBytes
      }
      for (j <- 0 until (txnMaxLen-txnLen))
        txnMem = txnMem ++ MemStructSim.bigIntToBytes(BigInt(0), 8)
    }
    txnMem
  }
}

