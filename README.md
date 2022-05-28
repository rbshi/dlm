
# Hardware distributed lock manager

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
