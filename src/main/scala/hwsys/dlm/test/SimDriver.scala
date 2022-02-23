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
                        lkAttr: Int,
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
      (req.lkAttr << (sysConf.wNId+sysConf.wCId+sysConf.wTId)) +
      (req.wLen << (sysConf.wNId+sysConf.wCId+sysConf.wTId+2))
    MemStructSim.bigIntToBytes(vBigInt, 8)
  }

}

// Init methods
object SimInit {

  def txnEntrySimInt(txnCnt: Int, txnLen: Int, txnMaxLen: Int)(fNId: (Int, Int) => Int, fCId: (Int, Int) => Int, fTId: (Int, Int) => Int, fLk: (Int, Int) => Int)(implicit sysConf: SysConfig): Seq[Byte] = {
    var txnMem = Seq.empty[Byte]
    for (i <- 0 until txnCnt) {
      // txnHd
      txnMem = txnMem ++ MemStructSim.bigIntToBytes(BigInt(txnLen), 8)
      for (j <- 0 until txnLen) {
        txnMem = txnMem ++ TxnEntrySim(fNId(i, j), fCId(i, j), fTId(i, j), fLk(i, j), 0).asBytes
      }
      for (j <- 0 until (txnMaxLen-txnLen))
        txnMem = txnMem ++ MemStructSim.bigIntToBytes(BigInt(0), 8)
    }
    txnMem
  }
}

