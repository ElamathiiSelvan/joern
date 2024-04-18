/* reads version declarations from /build.sbt so that we can declare them in one place */
object Versions {
  val cpg = parseVersion("cpgVersion")
  // Dont upgrade antlr to 4.10 or above since those versions require java 11 or higher which
  // causes problems upstreams.
  val antlr          = "4.7.2"
  val scalatest      = "3.2.18"
  val cats           = "3.5.4"
  val json4s         = "4.0.7"
  val gradleTooling  = "8.3"
  val circe          = "0.14.6"
  val requests       = "0.8.0"
  val upickle        = "3.3.0"
  val scalaReplPP    = "0.1.85"
  val typeSafeConfig = "1.4.3"
  val versionSort    = "1.0.11"

  private def parseVersion(key: String): String = {
    val versionRegexp = s""".*val $key[ ]+=[ ]?"(.*?)"""".r
    val versions: List[String] = scala.io.Source
      .fromFile("build.sbt")
      .getLines
      .filter(_.contains(s"val $key"))
      .collect { case versionRegexp(version) => version }
      .toList
    assert(
      versions.size == 1,
      s"""unable to extract $key from build.sbt, expected exactly one line like `val $key= "0.0.0-SNAPSHOT"`."""
    )
    versions.head
  }

}
