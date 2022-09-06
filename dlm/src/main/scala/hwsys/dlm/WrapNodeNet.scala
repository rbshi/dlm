package hwsys.dlm

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.Axi4

import hwsys.coyote._
import hwsys.util.Helpers._

class WrapNodeNet(implicit sysConf: SysConfig) extends Component {

  val io = new NodeNetIO()

  // modules
  val nodeFlow = new WrapNode()
  nodeFlow.io.connectSomeByName(io.node)

  val rdmaFlowMstr = new RdmaFlowBpss(isMstr = true)
  val rdmaFlowSlve = new RdmaFlowBpss(isMstr = false)
  // connect rdma ctrl
  rdmaFlowMstr.io.ctrl <> io.rdmaCtrl(0)
  rdmaFlowSlve.io.ctrl <> io.rdmaCtrl(1)

  // connect net status
  nodeFlow.io.connectSomeByName(rdmaFlowMstr.io)
  // connect flow
  nodeFlow.io.sendQ >> rdmaFlowMstr.io.q_sink
  nodeFlow.io.recvQ << rdmaFlowMstr.io.q_src
  nodeFlow.io.reqQ << rdmaFlowSlve.io.q_src
  nodeFlow.io.respQ >> rdmaFlowSlve.io.q_sink

  val rdmaArb = new RdmaArb(2) // 2 nodes
  rdmaFlowMstr.io.rdma <> rdmaArb.io.rdmaV(0)
  rdmaFlowSlve.io.rdma <> rdmaArb.io.rdmaV(1)
  rdmaArb.io.rdmaio <> io.rdma

}
