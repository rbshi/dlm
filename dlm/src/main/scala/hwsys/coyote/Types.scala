package hwsys.coyote

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axilite._

import hwsys.util.Helpers._

// both BpssReq & RDMAReq
case class ReqT() extends Bundle {
  val rsrvd = UInt(96-48-28-4-4-6-1 bits) // 5b
  val vfid = UInt(1 bits) // only 1 vFPGA
  val pid = UInt(6 bits)
  val dest = UInt(4 bits)
  val host, ctl, sync, stream = Bool()
  val len = UInt(28 bits)
  val vaddr = UInt(48 bits)
}

case class BpssDone() extends Bundle {
  val pid = UInt(6 bits)
}

// cast struct to data stream
case class StreamData(width: Int) extends Bundle {
  val data = Bits(width bits)
}

case class BpssData(width: Int) extends Bundle {
  val tdata = Bits(width bits)
  val tdest = UInt(4 bits)
  val tkeep = Bits(width/8 bits) // will be renamed in RenameIO
  val tlast = Bool()
}

case class Axi4StreamData(width: Int) extends Bundle {
  val tdata = Bits(width bits)
  val tkeep = Bits(width/8 bits) // will be renamed in RenameIO
  val tlast = Bool()
}

// cast RdmaReqT.msg -> RdmaBaseT
// rdma parser bit definition assumes LSB first in sv struct
case class RdmaBaseT() extends Bundle {
  val lvaddr = UInt(64 bits)
  val rvaddr = UInt(64 bits)
  val len = UInt( 32 bits)
  val params = UInt(512-64-64-32 bits) // 352b
}

case class RdmaReqT() extends Bundle {
  val rsrvd = UInt(544-512-3-10-5 bits) // 14b
  val msg = UInt(512 bits) // RdmaBaseT or RPC
  val last = Bool()
  val mode = Bool()
  val host = Bool()
  val qpn = UInt(10 bits)
  val opcode = UInt(5 bits)
}

case class RdmaAckT() extends Bundle {
  // current coyote rdma_ack_t [is_nack: pid 6b: vfid 4b]
  // roce ip core provides 16b qpn
  val qpn  = UInt(10 bits)
  val is_nak = Bool()
}

class RdmaIO extends Bundle {
  // rd/wr cmd
  val rd_req = slave Stream StreamData(96)
  val wr_req = slave Stream StreamData(96)
  val sq = master Stream StreamData(544)
  val ack = slave Stream StreamData(11)

  val axis_sink = slave Stream Axi4StreamData(512)
  val axis_src =  master Stream Axi4StreamData(512)

  def flipDir(): Unit = {
    rd_req.flipDir()
    wr_req.flipDir()
    sq.flipDir()
    ack.flipDir()
    axis_sink.flipDir()
    axis_src.flipDir()
  }

  def tieOff(): Unit = {
    rd_req.setBlocked()
    wr_req.setBlocked()
    sq.setIdle()
    ack.setBlocked()
    axis_sink.setBlocked()
    axis_src.setIdle()
  }

}

// TCP stack
case class ListenReqT() extends Bundle {
  val port = UInt(16 bits)
}

case class ListenRspT() extends Bundle {
  val success = UInt(8 bits)
}

case class OpenReqT() extends Bundle {
  val ipaddr = UInt(32 bits)
  val port = UInt(16 bits)
}

case class OpenRspT() extends Bundle {
  val sid = UInt(16 bits)
  val success = UInt(8 bits)
  val ipaddr = UInt(32 bits)
  val port = UInt(16 bits)
}

case class CloseReqT() extends Bundle {
  val sid = UInt(16 bits)
}

case class NotifyT() extends Bundle {
  val sid = UInt(16 bits)
  val len = UInt(16 bits)
  val ipaddr = UInt(32 bits)
  val dstport = UInt(16 bits)
  val closed = UInt(8 bits)
}

case class RdPkgT() extends Bundle {
  val sid = UInt(16 bits)
  val len = UInt(16 bits)
}

case class RxMetaT() extends Bundle {
  val sid = UInt(16 bits)
}

case class TxMetaT() extends Bundle {
  val sid = UInt(16 bits)
  val len = UInt(16 bits)
}

case class TxStatT() extends Bundle {
  val sid = UInt(16 bits)
  val len = UInt(16 bits)
  val remaining_space = UInt(30 bits)
  val error = UInt(2 bits)
}


class TcpIO extends Bundle {

//  // general control [:start_server(1b)]
//  val ctrl = in UInt(32 bits)
//
//  // listen port (as the server)
//  val listen_port = in UInt(32 bits)
//
//  // remote server config
//  val svr_ipaddr = in UInt(32 bits)
//  val svr_port = in UInt(16 bits)

  // status reg
  val cntNotif    = out(Reg(UInt(32 bits))).init(0)
  val cntNotifLen = out(Reg(UInt(32 bits))).init(0)
  val cntRdPkg    = out(Reg(UInt(32 bits))).init(0)
  val cntRxMeta   = out(Reg(UInt(32 bits))).init(0)
  val cntTxMeta   = out(Reg(UInt(32 bits))).init(0)
  val cntTxStat   = out(Reg(UInt(32 bits))).init(0)
  val cntSink     = out(Reg(UInt(32 bits))).init(0)
  val cntSrc      = out(Reg(UInt(32 bits))).init(0)

  val listen_req = master Stream StreamData(ListenReqT().getBitsWidth)
  val listen_rsp = slave Stream StreamData(ListenRspT().getBitsWidth)

  val open_req   = master Stream StreamData(OpenReqT().getBitsWidth)
  val open_rsp   = slave Stream StreamData(OpenRspT().getBitsWidth)
  val close_req  = master Stream StreamData(CloseReqT().getBitsWidth)

  val notif      = slave Stream StreamData(NotifyT().getBitsWidth)     // notify is a member of Object
  val rd_pkg     = master Stream StreamData(RdPkgT().getBitsWidth)
  val rx_meta    = slave Stream StreamData(RxMetaT().getBitsWidth)

  val tx_meta    = master Stream StreamData(TxMetaT().getBitsWidth)
  val tx_stat    = slave Stream StreamData(TxStatT().getBitsWidth)

  val axis_sink  = slave Stream Axi4StreamData(512)
  val axis_src   = master Stream Axi4StreamData(512)

  def regMap(r: AxiLite4SlaveFactory, baseR: Int): Int = {
    implicit val baseReg = baseR
//    val rCtrl = r.rwInPort(ctrl,       r.getAddr(0), 0, "Tcp: ctrl")
//    when(rCtrl.orR) (rCtrl.clearAll()) // auto clear
    r.read(cntNotif   , r.getAddr(4) , 0, "Tcp: cntNotif")
    r.read(cntNotifLen, r.getAddr(5) , 0, "Tcp: cntNotifLen")
    r.read(cntRdPkg   , r.getAddr(6) , 0, "Tcp: cntRdPkg")
    r.read(cntRxMeta  , r.getAddr(7) , 0, "Tcp: cntRxMeta")
    r.read(cntTxMeta  , r.getAddr(8) , 0, "Tcp: cntTxMeta")
    r.read(cntTxStat  , r.getAddr(9) , 0, "Tcp: cntTxStat")
    r.read(cntSink    , r.getAddr(10), 0, "Tcp: cntSink")
    r.read(cntSrc     , r.getAddr(11), 0, "Tcp: cntSrc")

    val assignOffs = 12
    assignOffs
  }

}


// Bypass
class HostDataIO extends Bundle {
  // bpss h2c/c2h
  val bpss_rd_req = master Stream StreamData(96)
  val bpss_wr_req = master Stream StreamData(96)
  val bpss_rd_done = slave Stream StreamData(6)
  val bpss_wr_done = slave Stream StreamData(6)
  val axis_host_sink = slave Stream BpssData(512)
  val axis_host_src = master Stream BpssData(512)

  def tieOff(): Unit = {
    bpss_rd_req.setIdle()
    bpss_wr_req.setIdle()
    bpss_rd_done.setBlocked()
    bpss_wr_done.setBlocked()
    axis_host_sink.setBlocked()
    axis_host_src.setIdle()
  }

}


class CMemHostIO(cmemAxiConf: Axi4Config) extends Bundle {
  // ctrl
  val mode = in UInt(2 bits)
  val hostAddr = in UInt(64 bits)
  val cmemAddr = in UInt(64 bits)
  val len = in UInt(16 bits)
  val cnt = in UInt(64 bits)
  val pid = in UInt(6 bits)
  val cntDone = out(Reg(UInt(64 bits))).init(0)

  // host data IO
  val hostd = new HostDataIO

  // cmem interface
  val axi_cmem = master(Axi4(cmemAxiConf))

  def regMap(r: AxiLite4SlaveFactory, baseR: Int): Int = {
    implicit val baseReg = baseR
    val rMode = r.rwInPort(mode,     r.getAddr(0), 0, "CMemHost: mode")
    when(rMode.orR) (rMode.clearAll()) // auto clear
    r.rwInPort(hostAddr, r.getAddr(1), 0, "CMemHost: hostAddr")
    r.rwInPort(cmemAddr, r.getAddr(2), 0, "CMemHost: cmemAddr")
    r.rwInPort(len,      r.getAddr(3), 0, "CMemHost: len")
    r.rwInPort(cnt,      r.getAddr(4), 0, "CMemHost: cnt")
    r.rwInPort(pid,      r.getAddr(5), 0, "CMemHost: pid")
    r.read(cntDone,      r.getAddr(6), 0, "CMemHost: cntDone")
    val assignOffs = 7
    assignOffs
  }
}
