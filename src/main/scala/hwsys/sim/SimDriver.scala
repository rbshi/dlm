package hwsys.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axilite._

import scala.collection.mutable
import scala.collection._
import scala.math.BigInt

import hwsys.coyote._
import hwsys.sim.SimHelpers._

// import scala.concurrent.stm._


/** atomic lock */
//object Lock{
//  private val lkStatus = Ref(false)
//  def get(waitF: Unit): Unit = atomic { implicit txn =>
//    while(lkStatus()){waitF} // wait function as the argument; for here, it's cd.waitSampling()
//    lkStatus() = true
//  }
//  def rlse(): Unit = atomic {
//    implicit txn => lkStatus() = false
//  }
//}

/** Types in sim */
trait MemStructSim {
  def asBytes : Seq[Byte]
}

/** Helper function with MemStruct */
object MemStructSim {
  def bigIntToBytes(v: BigInt, byteLen: Int) : Seq[Byte] = {
    v.toByteArray.reverse.padTo(byteLen, 0.toByte)
  }
}

/** Driver components in sim */
object SimDriver {

  val axiMemSimConf = AxiMemorySimConfig(
    maxOutstandingReads = 128,
    maxOutstandingWrites = 128,
    readResponseDelay = 3,
    writeResponseDelay = 3
  )

  def instAxiMemSim(axi: Axi4, clockDomain: ClockDomain, memCtx: Option[Array[Byte]]) : AxiMemorySim = {
    val mem = AxiMemorySim(axi, clockDomain, axiMemSimConf)
    mem.start()
    memCtx match {
      case Some(ctx) => {
        mem.memory.writeArray(0, ctx)
      }
      case None => mem.memory.writeArray(0, Array.fill[Byte](1<<22)(0.toByte))
    }
    mem
  }

  // Axi4Lite
  def setAxi4LiteReg(cd: ClockDomain, bus: AxiLite4, addr: Int, data: Int): Unit ={
    val awa = fork {
      bus.aw.addr #= addr
      bus.w.data #= data
      bus.w.strb #= 0xF // strb for 4 Bytes
      bus.aw.valid #= true
      bus.w.valid #= true
      cd.waitSamplingWhere(bus.aw.ready.toBoolean && bus.w.ready.toBoolean)
      bus.aw.valid #= false
      bus.w.valid #= false
    }

    val b = fork {
      bus.b.ready #= true
      cd.waitSamplingWhere(bus.b.valid.toBoolean)
      bus.b.ready #= false
    }
    awa.join()
    b.join()
  }

  def readAxi4LiteReg(cd: ClockDomain, bus: AxiLite4, addr: Int): BigInt ={
    var data: BigInt = 1
    val ar = fork{
      bus.ar.addr #= addr
      bus.ar.valid #= true
      cd.waitSamplingWhere(bus.ar.ready.toBoolean)
      bus.ar.valid #= false
    }

    val r = fork{
      bus.r.ready #= true
      cd.waitSamplingWhere(bus.r.valid.toBoolean)
      data = bus.r.data.toBigInt
    }
    ar.join()
    r.join()
    data
  }

  // Axi4
  def axiMonitor(cd: ClockDomain, bus: Axi4): Unit = {
    fork{while(true){
      cd.waitSamplingWhere(bus.readCmd.isFire)
      println(s"[AXI RdCmd]: ReadAddr: ${bus.readCmd.addr.toBigInt}")}}

    fork{while(true){
      cd.waitSamplingWhere(bus.readRsp.isFire)
      println(s"[AXI RdResp]: ReadData: ${bus.readRsp.data.toBigInt}")}}

    fork{while(true){
      cd.waitSamplingWhere(bus.writeCmd.isFire)
      println(s"[AXI WrCmd]: WrAddr: ${bus.writeCmd.addr.toBigInt}")}}

    fork{while(true){
      cd.waitSamplingWhere(bus.writeData.isFire)
      println(s"[AXI WrData]: WrData: ${bus.writeData.data.toBigInt}")}}
  }

  implicit class StreamUtils[T <: Data](stream: Stream[T]) {
    def isFire: Boolean = {
      stream.valid.toBoolean && stream.ready.toBoolean
    }

    def simIdle(): Unit = {
      stream.valid #= false
    }

    def simBlocked(): Unit = {
      stream.ready #= true
    }
  }

  // TODO: how to constraint the type scope for specific method in the class? Then I can combine these above and below.
  implicit class StreamUtilsBitVector[T <: BitVector](stream: Stream[T] ) {
    
    def sendData[T1 <: BigInt](cd: ClockDomain, data: T1): Unit = {
      stream.valid #= true
      stream.payload #= data
      cd.waitSamplingWhere(stream.ready.toBoolean)
      stream.valid #= false
    }

    def recvData(cd: ClockDomain): BigInt = {
      stream.ready #= true
      cd.waitSamplingWhere(stream.valid.toBoolean)
      stream.payload.toBigInt
    }

    def <<#(that: Stream[T]): Unit = {
      stream.payload #= that.payload.toBigInt
      stream.valid #= that.valid.toBoolean
      that.ready #= stream.ready.toBoolean
    }

    def #>>(that: Stream[T]) = {
      that <<# stream
    }
  }

  implicit class StreamUtilsBundle[T <: Bundle](stream: Stream[T]) {

    def sendData[T1 <: BigInt](cd: ClockDomain, data: T1): Unit = {
      stream.valid #= true
      stream.payload #= data
      cd.waitSamplingWhere(stream.ready.toBoolean)
      stream.valid #= false
    }

    def recvData(cd: ClockDomain): BigInt = {
      stream.ready #= true
      cd.waitSamplingWhere(stream.valid.toBoolean)
      stream.payload.toBigInt()
    }

    def <<#(that: Stream[T]): Unit = {
      stream.payload #= that.payload.toBigInt()
      stream.valid #= that.valid.toBoolean
      that.ready #= stream.ready.toBoolean
    }

    def #>>(that: Stream[T]) = {
      that <<# stream
    }
  }


  /** Pipe stream in sim with given latency
   */
  def streamDelayPipe[T <: Bits](cd: ClockDomain, streamIn: Stream[T], streamOut: Stream[T], lat: Int) = {

    var cycle = 0
    val payloadQ, tsQ = mutable.Queue[BigInt]()

    // clk counter
    fork {
      while(true){
        cd.waitSampling()
        cycle += 1
      }
    }

    fork {
      while(true){
        payloadQ.enqueue(streamIn.recvData(cd))
        tsQ.enqueue(cycle)
        // send monitor can be put here
      }
    }

    fork {
      while(true){
        if(tsQ.nonEmpty && (cycle > (tsQ.front + lat))) {
          streamOut.sendData(cd, payloadQ.dequeue())
          tsQ.dequeue()
        } else {
          streamOut.simIdle()
          cd.waitSampling()
        }
      }
    }

  }


  /** RDMA switch for sim
   * Support WR verb only now
   * rq is not used
   */
  def rdmaSwitch(cd: ClockDomain, n: Int, lat: Int, sq: Seq[Stream[StreamData]],
                               rdReq: Seq[Stream[StreamData]], wrReq: Seq[Stream[StreamData]],
                               axiSrc: Seq[Stream[Axi4StreamData]], axiSink: Seq[Stream[Axi4StreamData]]) = {


    //
    def getRmt(idx: Int) = (idx+1)%2

    var cycle = 0
    val rdReqQ, wrReqQ, axiSrcCmdQ, axiSinkCmdQ, axiSrcQ, axiSinkQ, tsQ1, tsQ2 = List.fill(rdReq.length)(mutable.Queue[BigInt]())
    // val lkRdReq, lkWrReq, lkAxiSrc, lkAxiSink = List.fill(rdReq.length)(Lock)

    // clk counter
    fork {
      while(true){
        cd.waitSampling()
        cycle += 1
      }
    }

    sq.zipWithIndex.foreach{ case (q, idx) =>
      fork {
        while(true){
          // get sq and enq to other Qs with transformation
          val sqD = q.recvData(cd)
          tsQ1(getRmt(idx)).enqueue(cycle)
          tsQ2(getRmt(idx)).enqueue(cycle)

          // transform to sq to local rd_req (for wr verb), does NOT work due to simPublic
          // val sqPkg = genFromBigInt(RdmaBaseT(), genFromBigInt(RdmaReqT(), sqD).pkg.toBigInt)
          // val reqB = ReqT() // wr/rd shares the same ReqT
          // // FIXME: 1. write an auto trans function. 2. How about other default values?
          // reqB.vaddr #= sqPkg.rvaddr.toBigInt
          // reqB.len #= sqPkg.len.toBigInt
          // val reqVal = reqB.toBigInt()

          // FIXME: manually set the req.pkg.raddr (127:80) req.pkg.len (160:128) to reqT (vaddr@95:48, len@47:20)
          val reqVal = (bigIntTruncVal(sqD, 80, 127) << 48) + (bigIntTruncVal(sqD, 128, 159) << 20)

          // enq local: rdReq, axiSrcCmd. Rmt: wrReq, axiSinkCmd
          for (enQ <- Seq(rdReqQ(idx), wrReqQ(getRmt(idx)), axiSrcCmdQ(idx), axiSinkCmdQ(getRmt(idx)))) {
            enQ.enqueue(reqVal)
          }

          // println(s"[sqCmd] enq ${reqVal.hexString()}")

        }
      }
    }

    // send local rdReq
    rdReqQ.zipWithIndex.foreach{ case (q, idx) =>
      fork {
        while(true){
          if(q.nonEmpty){
            // if RDMA rmt rd verb is used, queue lock should be obtained
            // lkRdReq(idx).get()
            rdReq(idx).sendData(cd, q.dequeue())
            // lkRdReq(idx).rlse()
          } else {
            rdReq(idx).simIdle()
            cd.waitSampling()
          }
        }
      }
    }

    // recv local axi_src
    axiSrcCmdQ.zipWithIndex.foreach { case (q, idx) =>
      fork {
        while(true){
          if(q.nonEmpty){
            // recvData the fragment from axiSrcQ and send to target node axiSinkQ
            var fragEnd = false
            do {
              val axiSrcVal = axiSrc(idx).recvData(cd)
              axiSinkQ(getRmt(idx)).enqueue(axiSrcVal)
              // fragEnd = genFromBigInt(Axi4StreamData(512), axiSrcVal).tlast.toBoolean
              fragEnd = ((axiSrcVal >> (512+64)) & BigInt(1)) == 1
              // println(s"[axiSinkQ] enq ${axiSrcVal.hexString()}")
            } while (!fragEnd)
            q.dequeue() // cmdQ
          } else {
            axiSrc(idx).simBlocked()
            cd.waitSampling()
          }
        }
      }
    }

    // send remote wrReq
    wrReqQ.zipWithIndex.foreach { case (q, idx) =>
      fork {
        while(true){
          if(q.nonEmpty && (cycle > tsQ1(idx).front + lat)){
            val reqVal = q.dequeue()
            // println(s"[wrReq] deq ${reqVal.hexString()}")
            wrReq(idx).sendData(cd, reqVal)
            tsQ1(idx).dequeue()
          } else {
            wrReq(idx).simIdle()
            cd.waitSampling()
          }
        }
      }
    }

    // send remote axiSink (FIXME: perhaps we need a token from wrReq?)
    axiSinkCmdQ.zipWithIndex.foreach { case (q, idx) =>
      fork {
        while(true){
          // axiSinkQ will be ready later than q
          if(q.nonEmpty && (cycle > tsQ2(idx).front + lat) && axiSinkQ(idx).nonEmpty){
            // sendData the fragment from axiSinkQ and send to target node axiSinkQ
            var fragEnd = false
            do {
              val axiSinkVal = axiSinkQ(idx).dequeue()
              // fragEnd = genFromBigInt(Axi4StreamData(512), axiSinkVal).tlast.toBoolean
              fragEnd = ((axiSinkVal >> (512+64)) & BigInt(1)) == 1
              axiSink(idx).sendData(cd, axiSinkVal)
              // println(s"[axiSinkQ] deq ${axiSinkVal.hexString()}")
            } while (!fragEnd)
            q.dequeue() // cmdQ
            tsQ2(idx).dequeue()
          } else {
            axiSink(idx).simIdle()
            cd.waitSampling()
          }
        }
      }
    }

  }

}






