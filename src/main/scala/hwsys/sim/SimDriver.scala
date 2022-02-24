package hwsys.sim

import spinal.core._
import spinal.lib.bus.amba4.axi.Axi4


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
    readResponseDelay = 10,
    writeResponseDelay = 10
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

}