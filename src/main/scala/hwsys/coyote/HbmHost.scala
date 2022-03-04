package hwsys.coyote

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.bus.amba4.axi._
import spinal.lib.fsm.StateMachine

//
case class HbmHost(hbmAxiConf: Axi4Config) extends Component {

  val io = new Bundle {
    // ctrl
    val mode = in UInt(2 bits)
    val hostAddr = in UInt(64 bits)
    val hbmAddr = in UInt(64 bits)
    val len = in UInt(16 bits)
    val cnt = in UInt(64 bits)
    val pid = in UInt(6 bits)
    val cntDone = out(Reg(UInt(64 bits))).init(0)

    // host data IO
    val hostd = new HostDataIO

    // hbm interface
    val axi_hbm = master(Axi4(hbmAxiConf))
  }
  
  val cntWrData, cntRdData, cntHostReq, cntHbmReq = Reg(UInt(64 bits)).init(0)
  val hbmRdAddr, hbmWrAddr, reqHostAddr = Reg(UInt(64 bits)).init(0)

  // req assignment
  val bpss_rd_req, bpss_wr_req = ReqT()
  List(bpss_rd_req, bpss_wr_req).foreach { e =>
    e.vaddr := reqHostAddr.resized
    e.len := io.len.resized
    e.stream := False
    e.sync := False
    e.ctl := True
    e.host := True
    e.dest := 0
    e.pid := io.pid
    e.vfid := 0
    e.rsrvd := 0
  }
  io.hostd.bpss_rd_req.data.assignFromBits(bpss_rd_req.asBits)
  io.hostd.bpss_wr_req.data.assignFromBits(bpss_wr_req.asBits)


  val fsm = new StateMachine {
    val IDLE = new State with EntryPoint
    val RD, WR = new State

    IDLE.whenIsActive {
      when(io.mode(0)) (goto(RD))
      when(io.mode(1)) (goto(WR))
      // cleanup the status
      when(io.mode(0) || io.mode(1)) {
        reqHostAddr := io.hostAddr
        hbmRdAddr := io.hbmAddr
        hbmWrAddr := io.hbmAddr
        List(cntRdData, cntWrData, cntHostReq, cntHbmReq, io.cntDone).foreach(_.clearAll())
      }
    }

    // rd from host
    RD.whenIsActive {
      when(io.cntDone === io.cnt) (goto(IDLE))
    }


    // wr to host
    WR.whenIsActive {
      when(io.cntDone === io.cnt) (goto(IDLE))
    }
  }



  // deft behavior of rd from host
  // axi_hbm wr
  io.axi_hbm.aw.setBurstINCR()
  io.axi_hbm.aw.len := ((io.len >> 6) - 1).resized
  io.axi_hbm.aw.size := log2Up(512/8)
  io.axi_hbm.aw.addr := hbmWrAddr.resized

  io.axi_hbm.w.strb.setAll()
  io.axi_hbm.w.data <> io.hostd.axis_host_sink.tdata
  io.axi_hbm.w.last <> io.hostd.axis_host_sink.tlast
  io.axi_hbm.w.valid <> io.hostd.axis_host_sink.valid
  io.axi_hbm.w.ready <> io.hostd.axis_host_sink.ready

  io.axi_hbm.b.ready := True

  // axi_hbm rd
  io.axi_hbm.ar.setBurstINCR()
  io.axi_hbm.ar.len := ((io.len >> 6) - 1).resized
  io.axi_hbm.ar.size := log2Up(512/8)
  io.axi_hbm.ar.addr := hbmRdAddr.resized

  io.axi_hbm.r.data <> io.hostd.axis_host_src.tdata
  io.axi_hbm.r.valid <> io.hostd.axis_host_src.valid
  io.axi_hbm.r.ready <> io.hostd.axis_host_src.ready

  io.hostd.axis_host_src.tdest := 0
  io.hostd.axis_host_src.tlast := False
  io.hostd.axis_host_src.tkeep.setAll()
  
  // inc cnt
  when(io.axi_hbm.aw.fire || io.axi_hbm.ar.fire) (cntHbmReq := cntHbmReq + 1)
  when(io.axi_hbm.aw.fire) (hbmWrAddr := hbmWrAddr + io.len)
  when(io.axi_hbm.ar.fire) (hbmRdAddr := hbmRdAddr + io.len)

  when(io.hostd.bpss_rd_req.fire) {
    cntHostReq := cntHostReq + 1
    reqHostAddr := reqHostAddr + io.len
  }

  val hbmToReq = cntHbmReq =/= io.cnt
  val hostToReq = cntHostReq =/= io.cnt

  io.axi_hbm.aw.valid := hbmToReq && fsm.isActive(fsm.RD)
  io.axi_hbm.ar.valid := hbmToReq && fsm.isActive(fsm.WR)

  io.hostd.bpss_rd_req.valid := hostToReq && fsm.isActive(fsm.RD)
  io.hostd.bpss_wr_req.valid := hostToReq && fsm.isActive(fsm.WR)

  when(io.hostd.bpss_rd_done.fire || io.hostd.bpss_wr_done.fire)(io.cntDone := io.cntDone + 1)
  when(io.hostd.axis_host_sink.fire) (cntRdData := cntRdData + 1)
  when(io.hostd.axis_host_src.fire) {cntWrData := cntWrData + 1}

}