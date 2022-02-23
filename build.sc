import mill._, scalalib._

val spinalVersion = "1.6.4"

object dlm extends SbtModule {
  def scalaVersion = "2.12.14"
  override def millSourcePath = os.pwd
  def ivyDeps = Agg(
    ivy"com.github.spinalhdl::spinalhdl-core:$spinalVersion",
    ivy"com.github.spinalhdl::spinalhdl-lib:$spinalVersion",
    ivy"com.lihaoyi::os-lib:0.8.0"
  )
  def scalacPluginIvyDeps = Agg(ivy"com.github.spinalhdl::spinalhdl-idsl-plugin:$spinalVersion")

  object test extends Tests {
    def ivyDeps = Agg(
      ivy"com.lihaoyi::utest:0.7.4",
    )
    def testFrameworks = Seq("utest.runner.Framework")
  }

}