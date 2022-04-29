package hwsys.dlm.test

import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.Axi4
import spinal.lib.master
import hwsys.dlm._
import hwsys.sim._
import hwsys.util.Helpers._



object LinkedListSim {

  def main(args: Array[String]): Unit = {


    SimConfig.withWave.compile {
      val dut = new LinkedListDut(32, 10)
      dut
    }.doSim("lldut", 99) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      var simHT = scala.collection.mutable.Map.empty[Int, Int]

      val prepCmd = fork {
        // init hash table for sim
        val sizeHT = 128
        var cmdQueue = scala.collection.mutable.Queue.empty[(SpinalEnumElement[LLOp.type], Int, Int, Boolean)] // opcode, key, head_ptr, head_val, head_ptr_val

        for ( k <- 1 to 10) {
          if (k==1) {
            cmdQueue += ((LLOp.ins, k, 0, false)) // insert
          } else{
            cmdQueue += ((LLOp.ins, k, 0, true)) // insert
          }
        }
        dut.io.ll_cmd_if.valid #= false
        dut.clockDomain.waitSampling(1200)

        sendCmd()

        for ( k <- 1 to 10) {
          cmdQueue += ((LLOp.deq, 0, k-1, true)) // dequeue
        }
        sendCmd()

        def sendCmd(): Unit = {
          while (cmdQueue.nonEmpty) {
            val (opcode, key, head_ptr, head_ptr_val) = cmdQueue.front
            dut.io.ll_cmd_if.valid #= true
            dut.io.ll_cmd_if.opcode #= opcode
            dut.io.ll_cmd_if.key #= key
            dut.io.ll_cmd_if.head_ptr #= head_ptr
            dut.io.ll_cmd_if.head_ptr_val #= head_ptr_val
            dut.clockDomain.waitSampling()
            if (dut.io.ll_cmd_if.valid.toBoolean && dut.io.ll_cmd_if.ready.toBoolean) {
              println("[SEND] opcode:" + opcode + "\tkey:" + key)
              cmdQueue.dequeue()
            }
          }
          dut.io.ll_cmd_if.valid #= false
        }
      }

      val recRes = fork{
        dut.io.ll_res_if.ready #= true
        while (true){
          dut.clockDomain.waitSampling()
          if (dut.io.ll_res_if.valid.toBoolean) {
            println("[RECE] key:" + dut.io.ll_res_if.key.toBigInt + "\tresponse:" + dut.io.ll_res_if.rescode.toBigInt)
          }
        }
      }

      val updateHeadTable = fork{
        while (true){
          dut.clockDomain.waitSampling()
          if (dut.io.head_table_if.wr_en.toBoolean){
            println("[HeadTable] ptr:" + dut.io.head_table_if.wr_data_ptr.toBigInt + "\tptr_val:" + dut.io.head_table_if.wr_en.toBoolean)
          }
        }
      }

      prepCmd.join()
    }

  }
}