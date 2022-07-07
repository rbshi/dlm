package hwsys.dlm

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axilite._
import spinal.lib.bus.amba4.axi._
import hwsys.coyote._
import hwsys.util._
import hwsys.util.Helpers._

// coyote wrap
class WrapSys(implicit sysConf: SysConfig) extends Component with RenameIO {

  val io = new Bundle {
    // axi-lite control
    val axi_ctrl = slave(AxiLite4(AxiLite4Config(64, 64)))
    // host data io
    val hostd = new HostDataIO
    // memory ports 0: host; others: user logic (may change)
    val axi_mem = Vec(master(Axi4(sysConf.axiConf)), 16)
    // rdma
    val rdma_0 = new RdmaIO
  }

  // rd/wr data between host and hbm
  val hbmHost = new CMemHost(sysConf.axiConf)
  // connection
  hbmHost.io.hostd.connectAllByName(io.hostd)
  hbmHost.io.axi_cmem <> io.axi_mem(0)


  // txnNodeWrap
  val txnEng = new WrapNodeNet()
  // connection
  // axi of txnMan & txnAgent
  txnEng.io.node.axi.zipWithIndex.foreach{case (p, i) => p <> io.axi_mem(1+i)}
  // axi of cmd of txnMan
  txnEng.io.node.cmdAxi.zipWithIndex.foreach{case (p, i) => p <> io.axi_mem(1+sysConf.nTxnMan+sysConf.nTxnAgent+i)}
  // rdma
  txnEng.io.rdma.connectSomeByName(io.rdma_0)

  // ctrl reg mapping
  val ctrlR = new AxiLite4SlaveFactory(io.axi_ctrl, useWriteStrobes = true)

  var asgnOffs = 0
  asgnOffs = hbmHost.io.regMap(ctrlR, 0) // Reg: 0-6
  asgnOffs = txnEng.io.regMap(ctrlR, asgnOffs)


  // tieOff unused memory ports
  for (i <- (1+sysConf.nTxnMan*2+sysConf.nTxnAgent) until io.axi_mem.length) {
    // TODO: symplify
    io.axi_mem(i).setIdle()
  }

}