ThisBuild / version := "1.0"
ThisBuild / scalaVersion := "2.11.12"
ThisBuild / organization := "org.example"

val spinalVersion = "1.6.4"
val spinalCore = "com.github.spinalhdl" %% "spinalhdl-core" % spinalVersion
val spinalLib = "com.github.spinalhdl" %% "spinalhdl-lib" % spinalVersion
val spinalIdslPlugin = compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % spinalVersion)

lazy val dlm = (project in file("."))
  .settings(
    name := "dlm",
    libraryDependencies ++= Seq(spinalCore, spinalLib, spinalIdslPlugin),
    libraryDependencies += "com.lihaoyi" %% "utest" % "0.7.10" % "test",
    libraryDependencies += "com.lihaoyi" %% "os-lib" % "0.8.0",
    testFrameworks += new TestFramework("utest.runner.Framework")
  )

fork := true
