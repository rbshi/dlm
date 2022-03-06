package hwsys.coyote

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axilite._

import hwsys.util.Helpers._

// both BpssReq & RDMAReq
case class ReqT() extends Bundle {
  val rsrvd = UInt(96-48-28-4-4-6-1 bits) // total len = 96b
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

// cast RdmaReqT.pkg -> RdmaBaseT
// rdma parser bit definition assumes LSB first in sv struct
case class RdmaBaseT() extends Bundle {
  val lvaddr = UInt(48 bits)
  val rvaddr = UInt(48 bits)
  val len = UInt( 32 bits)
  val params = UInt(64 bits)
}

case class RdmaReqT() extends Bundle {
  val rsrvd = UInt(256-5-24-1-1-1-192 bits) // 32b
  val pkg = UInt(192 bits) // RdmaBaseT or RPC
  val mode = Bool()
  val host = Bool()
  val id = UInt(1 bits)
  val qpn = UInt(24 bits)
  val opcode = UInt(5 bits)
}


class RdmaIO extends Bundle {
  // rd/wr cmd
  val rd_req = slave Stream StreamData(96)
  val wr_req = slave Stream StreamData(96)
  val rq = slave Stream StreamData(256)
  val sq = master Stream StreamData(256)

  val axis_sink = slave Stream Axi4StreamData(512)
  val axis_src =  master Stream Axi4StreamData(512)

  def flipDir(): Unit = {
    rd_req.flipDir()
    wr_req.flipDir()
    rq.flipDir()
    sq.flipDir()
    axis_sink.flipDir()
    axis_src.flipDir()
  }
}

class HostDataIO extends Bundle {
  // bpss h2c/c2h
  val bpss_rd_req = master Stream StreamData(96)
  val bpss_wr_req = master Stream StreamData(96)
  val bpss_rd_done = slave Stream StreamData(6)
  val bpss_wr_done = slave Stream StreamData(6)
  val axis_host_sink = slave Stream BpssData(512)
  val axis_host_src = master Stream BpssData(512)
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




