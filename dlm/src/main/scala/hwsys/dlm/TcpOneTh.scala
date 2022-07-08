package hwsys.dlm

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axilite._
import spinal.lib.bus.amba4.axi._
import spinal.lib.fsm.{EntryPoint, State, StateMachine}
import hwsys.coyote._
import hwsys.util._
import hwsys.util.Helpers._

class TcpOneTh(implicit sysConf: SysConfig) extends Component with RenameIO {

  val io = new Bundle {
    // axi-lite control
    val axi_ctrl = slave(AxiLite4(AxiLite4Config(64, 64)))
    // host data io
    val hostd = new HostDataIO
    // memory ports 0: host; others: user logic (may change)
    val axi_mem = Vec(master(Axi4(sysConf.axiConf)), 15)
    // tcp
    val tcp_0 = new TcpIO
    // rdma
    val rdma_1 = new RdmaIO
  }

    // general control [:start_server(1b)]
    val ctrl = UInt(32 bits)

    // listen port (as the server)
    val listen_port = UInt(16 bits)


  // server
  val svrState = new StateMachine {
    val IDLE = new State with EntryPoint
    val STARTSVR, STARTRESP = new State

    io.tcp_0.listen_req.data :=  listen_port.asBits
    io.tcp_0.listen_req.valid := False
    io.tcp_0.listen_rsp.ready := True

    IDLE.whenIsActive{
      // bit(0): start port listering
      when(ctrl(0)) {goto(STARTSVR)}
    }

    STARTSVR.whenIsActive{
      io.tcp_0.listen_req.valid := True
      when(io.tcp_0.listen_req.fire) {goto(STARTRESP)}
    }

    STARTRESP.whenIsActive{
      // data(0): success
      when(io.tcp_0.listen_rsp.fire && io.tcp_0.listen_rsp.data(0)) {goto(IDLE)}
    }
  }

  // rx
  val notifFifo = StreamFifo(NotifyT(), 32)
  notifFifo.io.push.translateFrom(io.tcp_0.notif)((a, b) => a.assignFromBits(b.data))

  // TODO: avability check
  // if notif is close session; abandon notif with len==0
  val notifThrowZeroLen = notifFifo.io.pop.throwWhen(notifFifo.io.pop.len===0)
  io.tcp_0.rd_pkg.translateFrom(notifThrowZeroLen)((a, b) => {
    val rdPkg = RdPkgT()
    rdPkg.sid := b.sid
    rdPkg.len := b.len
    a.data := rdPkg.asBits
  })

  io.tcp_0.rx_meta.ready := True
  val rSid = RegNextWhen(io.tcp_0.rx_meta.data, io.tcp_0.rx_meta.fire)

  val dFifo = StreamFifo(Bits(512 bits), 512)
  dFifo.io.push.translateFrom(io.tcp_0.axis_sink)(_ := _.tdata)

  // cnt
  val fifoCnt = AccumIncDec(10 bits, dFifo.io.push.fire, io.tcp_0.tx_meta.fire, 1, 8)

  // tx
  val txMeta = TxMetaT()
  txMeta.sid := rSid.asUInt
  txMeta.len := 64
  io.tcp_0.tx_meta.data := txMeta.asBits
  io.tcp_0.tx_meta.valid := (fifoCnt.accum >= 8)

  // bypass tx_stat
  io.tcp_0.tx_stat.ready := True
  when(io.tcp_0.tx_stat.fire && (io.tcp_0.tx_stat.data.toDataType(TxStatT()).error =/= 0)) {
    // error status
  }

  val cnt = Counter(8, dFifo.io.pop.fire)
  when(cnt === 0) {
    io.tcp_0.axis_src.tkeep.setAll()
    io.tcp_0.axis_src.tlast.set()
    io.tcp_0.axis_src.translateFrom(dFifo.io.pop)(_.tdata := _)
  } otherwise {
    io.tcp_0.axis_src.setIdle()
    dFifo.io.pop.freeRun()
  }

  // status cnt
  when(io.tcp_0.notif.fire) {
    io.tcp_0.cntNotif := io.tcp_0.cntNotif + 1
    io.tcp_0.cntNotifLen := io.tcp_0.cntNotifLen + io.tcp_0.notif.data.toDataType(NotifyT()).len
  }
  when(io.tcp_0.rd_pkg.fire) {io.tcp_0.cntRdPkg := io.tcp_0.cntRdPkg + 1}
  when(io.tcp_0.rx_meta.fire) {io.tcp_0.cntRxMeta := io.tcp_0.cntRxMeta + 1}
  when(io.tcp_0.tx_meta.fire) {io.tcp_0.cntTxMeta := io.tcp_0.cntTxMeta + 1}
  when(io.tcp_0.tx_stat.fire) {io.tcp_0.cntTxStat := io.tcp_0.cntTxStat + 1}
  when(io.tcp_0.axis_sink.fire) {io.tcp_0.cntSink := io.tcp_0.cntSink + 1}
  when(io.tcp_0.axis_src.fire) {io.tcp_0.cntSrc := io.tcp_0.cntSrc + 1}

  // ctrl reg mapping
  val r = new AxiLite4SlaveFactory(io.axi_ctrl, useWriteStrobes = true)

  implicit val baseReg = 0
  val rCtrl = r.rwInPort(ctrl, r.getAddr(0), 0, "Tcp: ctrl")
  when(rCtrl.orR) (rCtrl.clearAll())
  r.rwInPort(listen_port, r.getAddr(1), 0, "Tcp: listen_port")

  io.tcp_0.regMap(r, 0) // reg0-11

  // tieOff unused memory ports
  io.axi_mem.foreach(_.setIdle())
  io.hostd.tieOff()
  io.rdma_1.tieOff()

  io.tcp_0.open_req.tieOff(false)
  io.tcp_0.open_rsp.tieOff(false)
  io.tcp_0.close_req.tieOff(false)

}