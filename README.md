
# Hardware distributed lock manager


The memory node in cloud computing usually has parallel memory channels and memory interfaces with unified data access. Building a management layer on top of the parallel interfaces is not trivial to ensure data integrity (e.g., atomicity). The concept of transaction origins from the database domain has been applied as a general memory semantic. However, software-based transaction processing suffers from a high cost of concurrency control (e.g., lock management). Building a hardware transaction processing layer for the multi-channel memory node is valuable. Our target is to run the hardware as a background daemon and use the expensive memory bandwidth for data access in transactions. The project provides transaction processing hardware with three concurrency control schemes (No wait 2PL, Bounded wait 2PL, and Timestamp OCC). It has been prototyped with [Coyote (an FPGA OS)](https://github.com/fpgasystems/Coyote) on [Alveo FPGA cluster with HBM](https://xilinx.github.io/xacc/ethz.html). The transaction tasks are injected via either TCP or PCIe from the clients.



```
.
├── src/main/             # source files
│   ├── lib/              # external lib
│   │   ├── HashTable.    # 
│   │   └── LinkedList.   # 
│   └── scala/            # design with SpinalHDL
│       └── hwsys/        # hwsys lib
│           ├── coyote    # interface and datatype to coyote
│           ├── dlm/      # distributed lock manager
│           │   └── test  # testbench of dlm
│           ├── sim       # helper function for testbench
│           └── util      # hardware utilities
├── build.sbt             # sbt project
└── build.sc              # mill project
```

## Scripts
Hardware generation: `SysCoyote1T2N1C8PGen` is an example system config name defined in [Generator.scala](https://github.com/rbshi/dlm/blob/master/src/main/scala/hwsys/dlm/Generator.scala)
```
$ mill dlm.runMain hwsys.dlm.SysCoyote1T2N1C8PGen
```
Simulation: `WrapNodeNetSim` is a testbench defined in [WrapNodeNetSim.scala](https://github.com/rbshi/dlm/blob/master/src/main/scala/hwsys/dlm/test/WrapNodeNetSim.scala)
```
$ mill dlm.runMain hwsys.dlm.test.WrapNodeNetSim
```
