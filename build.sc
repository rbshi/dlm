import $ivy.`com.goyeau::mill-scalafix::0.2.8`
import com.goyeau.mill.scalafix.ScalafixModule
import mill._, scalalib._, scalafmt._

val spinalVersion = "1.6.4"
val scalaTestVersion = "3.2.11"

trait CommonSpinalModule extends ScalaModule with ScalafmtModule with ScalafixModule {
  def scalaVersion = "2.12.14"
  def scalacOptions = Seq("-unchecked", "-deprecation", "-feature")

  def ivyDeps = Agg(
    ivy"com.github.spinalhdl::spinalhdl-core:$spinalVersion",
    ivy"com.github.spinalhdl::spinalhdl-lib:$spinalVersion",
    ivy"com.github.spinalhdl::spinalhdl-sim:$spinalVersion",
    ivy"com.lihaoyi::os-lib:0.8.0",
    ivy"org.scala-stm::scala-stm:0.11.0"
    )

  def scalacPluginIvyDeps = Agg(ivy"com.github.spinalhdl::spinalhdl-idsl-plugin:$spinalVersion")
  def scalafixIvyDeps = Agg(ivy"com.github.liancheng::organize-imports:0.6.0")
}

object dlm extends CommonSpinalModule {
  object test extends Tests with TestModule.ScalaTest {
    def ivyDeps = Agg(ivy"org.scalatest::scalatest:$scalaTestVersion")
    def testFramework = "org.scalatest.tools.Framework"
    def testSim(args: String*) = T.command {
      super.runMain("org.scalatest.run", args: _*)
    }
  }
}
