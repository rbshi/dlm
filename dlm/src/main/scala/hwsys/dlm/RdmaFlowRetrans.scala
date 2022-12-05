package hwsys.dlm

import spinal.core._
import spinal.lib._
import hwsys.util._
import hwsys.coyote._
import spinal.lib.fsm._
import spinal.lib.fsm.StateMachine


class RetransDataFifo[T <: Data](dataType: HardType[T], depth: Int) extends Component {
  require(depth > 1)
  val io = new Bundle {
    val push = slave Stream dataType
    val rdCmd = slave Stream ReqT()
    val rdData = master Stream dataType
    val isRdLast = out Bool() default False

    val releasePtr = in UInt(log2Up(depth) bits)
    val isRelease = in Bool() default False

    val flush = in Bool() default False

    val occupancy = out UInt (log2Up(depth + 1) bits)
    val availability = out UInt (log2Up(depth + 1) bits)
  }

  val ram = Mem(dataType, depth)
  val pushPtr = Counter(depth)
  val rlsePtr = RegNextWhen(io.releasePtr, io.isRelease).init(0)
  val ptrMatch = pushPtr === rlsePtr

  val risingOccupancy = RegInit(False)
  val pushing = io.push.fire
  val releasing = io.isRelease
  val empty = ptrMatch & !risingOccupancy
  val full = ptrMatch & risingOccupancy

  io.push.ready := !full

  when(pushing =/= releasing) {
    risingOccupancy := pushing
  }
  when(pushing) {
    ram.write(pushPtr.value, io.push.payload)
    pushPtr.increment()
  }

  val ptrDif = pushPtr - rlsePtr
  if (isPow2(depth)) {
    io.occupancy := ((risingOccupancy && ptrMatch) ## ptrDif).asUInt
    io.availability := ((!risingOccupancy && ptrMatch) ## (rlsePtr - pushPtr)).asUInt
  } else {
    when(ptrMatch) {
      io.occupancy := Mux(risingOccupancy, U(depth), U(0))
      io.availability := Mux(risingOccupancy, U(0), U(depth))
    } otherwise {
      io.occupancy := Mux(pushPtr > rlsePtr, ptrDif, U(depth) + ptrDif)
      io.availability := Mux(pushPtr > rlsePtr, U(depth) + (rlsePtr - pushPtr), (rlsePtr - pushPtr))
    }
  }

  // arbitrarily read of unreleased data with rd descriptor
  // w/o over-read protection: MUST guarantee the ram has enough valid data for the rdCmd (i.e., issue the rdCmd after
  // check the io.occupancy)
  // Byte addr -> Lane addr
  require((widthOf(dataType) % 8) == 0)
  val discardLowBit = log2Up(widthOf(dataType)/8)
  val rdCmdPtr = io.rdCmd.payload.vaddr( log2Up(depth)+discardLowBit downto discardLowBit )
  val rdCmdLen = io.rdCmd.payload.len.round(discardLowBit)

  val rdFSM = new StateMachine {
    val RDCMD  = new State with EntryPoint
    val RDDATA =  new State

    val rRdLen = RegNextWhen(rdCmdLen, io.rdCmd.fire).init(0)
    val rRdPtr = RegNextWhen(rdCmdPtr, io.rdCmd.fire).init(0)

    val rdEn = Bool() default False

    io.rdCmd.ready := False
    io.rdData.valid := False
    io.rdData.payload := ram.readSync(rRdPtr)

    RDCMD.whenIsActive{
      io.rdCmd.ready := True
      when(io.rdCmd.fire & rdCmdLen > 0) {
        goto(RDDATA)
      }
    }
    RDDATA.whenIsActive{

      rdEn := True
      io.rdData.valid := RegNext(rdEn, False)

      io.isRdLast := rRdLen === 1
      when(io.rdData.fire) {
        rRdLen := rRdLen - 1
        rRdPtr := rRdPtr + 1
        when(rRdLen === 1) (goto(RDCMD))
      }
   }
  }
  when(io.flush) {
    pushPtr.clear()
    rlsePtr.clearAll()
    risingOccupancy := False
  }
}

class RetransPsumLenFifo(dWidth: Int, depth: Int) extends Component {
  require(depth > 1)

  val io = new Bundle {
    val push = slave Stream (UInt(dWidth bits))

    val releasePtr = in UInt(log2Up(depth) bits)
    val isRelease = in Bool() default False

    val releaseData = out UInt(dWidth bits)
    val releaseDataVld = out Bool() default False

    val flush = in Bool() default False
    val occupancy = out UInt (log2Up(depth + 1) bits)
    val availability = out UInt (log2Up(depth + 1) bits)
  }

  val ram = Mem(UInt(dWidth bits), depth)
  val pushPtr = Counter(depth)
  val rlsePtr = RegNextWhen(io.releasePtr, io.isRelease).init(0)
  val ptrMatch = pushPtr === rlsePtr

  val risingOccupancy = RegInit(False)
  val pushing = io.push.fire
  val releasing = io.isRelease
  val empty = ptrMatch & !risingOccupancy
  val full = ptrMatch & risingOccupancy

  io.push.ready := !full

  when(pushing =/= releasing) {
    risingOccupancy := pushing
  }

  // prefix sum pushing
  val rPrefixSum = Reg(UInt(dWidth bits)).init(0)
  when(pushing) {
    ram.write(pushPtr.value, io.push.payload+rPrefixSum)
    rPrefixSum := rPrefixSum + io.push.payload
    pushPtr.increment()
  }

  // rd releasePtr
  io.releaseData := ram.readSync(rlsePtr)
  io.releaseDataVld := RegNext(io.isRelease)

  val ptrDif = pushPtr - rlsePtr
  if (isPow2(depth)) {
    io.occupancy := ((risingOccupancy && ptrMatch) ## ptrDif).asUInt
    io.availability := ((!risingOccupancy && ptrMatch) ## (rlsePtr - pushPtr)).asUInt
  } else {
    when(ptrMatch) {
      io.occupancy := Mux(risingOccupancy, U(depth), U(0))
      io.availability := Mux(risingOccupancy, U(0), U(depth))
    } otherwise {
      io.occupancy := Mux(pushPtr > rlsePtr, ptrDif, U(depth) + ptrDif)
      io.availability := Mux(pushPtr > rlsePtr, U(depth) + (rlsePtr - pushPtr), (rlsePtr - pushPtr))
    }
  }

  when(io.flush) {
    rPrefixSum.clearAll()
    pushPtr.clear()
    rlsePtr.clearAll()
    risingOccupancy := False
  }
}


class RdmaFlowRetrans(isMstr : Boolean)(implicit sysConf: SysConfig) extends Component with RenameIO {

  val io = new Bundle {
    val rdma = new RdmaIO

    // interface user logic
    val q_sink = slave Stream Bits(512 bits)
    val q_src = master Stream Bits(512 bits)

    // ctrl
    val ctrl = RdmaCtrlIO()

  }

  // val incCntToSend = io.rdma.rd_req.fire // should NOT use rd_req to trigger the incCntToSend, it has a delay to the sq.fire, and will underflow to fireSq criteria to minus ??
  val incCntToSend = io.rdma.sq.fire
  val decCntToSend = io.rdma.axis_src.fire && io.rdma.axis_src.tlast
  val cntAxiToSend = CntIncDec(8 bits, incCntToSend, decCntToSend)

  // NOTE: using timeOut may have unknown affect on flow control
  // rst the timer with sq.fire to avoid over issue sq
  val timeOutInc = Bool()
  val timeOut = pauseTimeOut(9 bits, timeOutInc, io.rdma.sq.fire) // 512 cycles
  // buf the to status and refresh with sq.fire or axi.last
  val rTimeOut = RegNextWhen(timeOut.isTimeOut, io.rdma.sq.fire || decCntToSend)

  val cntBeat = CntDynmicBound(Mux(rTimeOut, U(1), io.ctrl.len>>6),  io.rdma.axis_src.fire) // each axi beat is with 64 B
  when(cntBeat.willOverflowIfInc) (io.rdma.axis_src.tlast.set())

  // sq settings
  val rdma_base = RdmaBaseT()
  rdma_base.lvaddr := 0
  rdma_base.rvaddr := io.ctrl.flowId.resized // for rdma wr to different resource, use flowId as rvadd to identify
  rdma_base.len := Mux(rTimeOut, U(64), io.ctrl.len)
  rdma_base.params := 0

  val sq = RdmaReqT()
  sq.opcode := 0xa // 0x0a: RDMA_WRITE_ONLY  0x01: APP_WRITE -> move to RC_RDMA_WRITE_ONLY after `rdma_req_parser`
  sq.qpn := io.ctrl.qpn
  sq.host := False
  sq.mode := True // True: RDMA_MODE_RAW, bypass the RDMA parser False: with RDMA parser
  sq.last := True
  sq.msg.assignFrom(rdma_base)
  sq.rsrvd := 0
  io.rdma.sq.data.assignFrom(sq)


  val nOutStandLane = (1024/64)*32

  if(isMstr){
    // mstr hw & behavior

    //TODO depth decides the outstanding
    val sendQ = new RetransDataFifo(Bits(512 bits), nOutStandLane)
    val recvQ = StreamFifo(Bits(512 bits), nOutStandLane)
    // require max depth of nOutStandLen (each lane )
    val psumQ = new RetransPsumLenFifo(log2Up(nOutStandLane), nOutStandLane)

    sendQ.io.flush.setWhen(~io.ctrl.en)
    recvQ.io.flush.setWhen(~io.ctrl.en)
    psumQ.io.flush.setWhen(~io.ctrl.en)

    timeOutInc := sendQ.io.occupancy > 0

    // sendQ
    sendQ.io.push << io.q_sink

    // axis_src
    io.rdma.axis_src.translateFrom(sendQ.io.rdData)((a,b) => {
      a.tdata := b
      a.tkeep.setAll()
      a.tlast := sendQ.io.isRdLast
    })

    // rd_req
    sendQ.io.rdCmd.translateFrom(io.rdma.rd_req)(_.assignFrom(_))

    // fire sq criteria: enough data in sendQ (e.g., fifo.occupancy > 16, for 1K packet)
    // the outstanding request number is designated by RetransDataFifo size
    val fireSq = Bool()
    switch(io.ctrl.len){
      is(1024)(fireSq := (sendQ.io.occupancy - (cntAxiToSend.cnt<<4)).asSInt >= (U(1, 16 bits)<<4).asSInt)
      is(2048)(fireSq := (sendQ.io.occupancy - (cntAxiToSend.cnt<<5)).asSInt >= (U(1, 16 bits)<<5).asSInt)
      is(4096)(fireSq := (sendQ.io.occupancy - (cntAxiToSend.cnt<<6)).asSInt >= (U(1, 16 bits)<<6).asSInt)
    }
    io.rdma.sq.valid := fireSq || timeOut.isTimeOut

    psumQ.io.push.payload := (rdma_base.len>>6)(log2Up(nOutStandLane)-1 downto 0)
    psumQ.io.push.valid.setWhen(io.rdma.sq.fire)

    io.rdma.ack.ready.set()
    val ack = RdmaAckT()
    ack.assignFromBits(io.rdma.ack.payload.data)
    psumQ.io.isRelease(io.rdma.ack.fire & ~ack.is_nak) // filter out the NACK
    psumQ.io.releasePtr(ack.msn(log2Up(nOutStandLane)-1 downto 0))

    sendQ.io.releasePtr := psumQ.io.releaseData
    sendQ.io.isRelease := psumQ.io.releaseDataVld

    // recvQ
    io.rdma.wr_req.ready.set()
    recvQ.io.push.translateFrom(io.rdma.axis_sink)(_ := _.tdata)
    recvQ.io.pop >> io.q_src

  } else {
    // slave hw & behavior

    val reqQ = StreamFifo(Bits(512 bits), nOutStandLane)
    val respQ = new RetransDataFifo(Bits(512 bits), nOutStandLane)
    val psumQ = new RetransPsumLenFifo(log2Up(nOutStandLane), nOutStandLane)

    reqQ.io.flush.setWhen(~io.ctrl.en)
    respQ.io.flush.setWhen(~io.ctrl.en)
    psumQ.io.flush.setWhen(~io.ctrl.en)

    // reqQ
    io.rdma.wr_req.ready.set()
    reqQ.io.push.translateFrom(io.rdma.axis_sink)(_ := _.tdata)
    reqQ.io.pop >> io.q_src

    // respQ
    io.q_sink >> respQ.io.push

    // axis_src
    io.rdma.axis_src.translateFrom(respQ.io.rdData)((a, b) => {
      a.tdata := b
      a.tkeep.setAll()
      a.tlast := respQ.io.isRdLast
    })

    // rd_req
    respQ.io.rdCmd.translateFrom(io.rdma.rd_req)(_.assignFrom(_))

    // fire sq criteria: enough data in sendQ (e.g., fifo.occupancy > 16, for 1K packet)
    // the outstanding request number is designated by RetransDataFifo size
    val fireSq = Bool()
    switch(io.ctrl.len) {
      is(1024)(fireSq := (respQ.io.occupancy - (cntAxiToSend.cnt << 4)).asSInt >= (U(1, 16 bits) << 4).asSInt)
      is(2048)(fireSq := (respQ.io.occupancy - (cntAxiToSend.cnt << 5)).asSInt >= (U(1, 16 bits) << 5).asSInt)
      is(4096)(fireSq := (respQ.io.occupancy - (cntAxiToSend.cnt << 6)).asSInt >= (U(1, 16 bits) << 6).asSInt)
    }
    io.rdma.sq.valid := fireSq || timeOut.isTimeOut

    psumQ.io.push.payload := (rdma_base.len >> 6)(log2Up(nOutStandLane) - 1 downto 0)
    psumQ.io.push.valid.setWhen(io.rdma.sq.fire)

    io.rdma.ack.ready.set()
    val ack = RdmaAckT()
    ack.assignFromBits(io.rdma.ack.payload.data)
    psumQ.io.isRelease(io.rdma.ack.fire & ~ack.is_nak) // filter out the NACK
    psumQ.io.releasePtr(ack.msn(log2Up(nOutStandLane) - 1 downto 0))

    respQ.io.releasePtr := psumQ.io.releaseData
    respQ.io.isRelease := psumQ.io.releaseDataVld

  }

  when(~io.ctrl.en) {
    cntAxiToSend.clearAll()
    cntBeat.clearAll()
  }

}
