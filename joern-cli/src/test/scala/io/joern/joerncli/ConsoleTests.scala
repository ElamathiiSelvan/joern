package io.joern.joerncli

import better.files.Dsl.mkdir
import better.files.File
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ConsoleTests extends AnyWordSpec with Matchers {

  "run" should {
    "provide a human readable overview of overlay creators" in withTestCode { codeDir =>
      RunScriptTests.exec(os.RelPath("general/run.sc"), codeDir.toString)
    }
  }

  "help" should {
    "allow getting long description via help object" in withTestCode { codeDir =>
      RunScriptTests.exec(os.RelPath("general/help.sc"), codeDir.toString)
    }
  }

  def withTestCode(fun: File => Unit): Unit = {
    File.usingTemporaryDirectory("console") { workspaceDir =>
      File.usingTemporaryDirectory("console") { codeDir =>
        mkdir(codeDir / "dir1")
        mkdir(codeDir / "dir2")
        (codeDir / "dir1" / "foo.c")
          .write("int main(int argc, char **argv) { char *ptr = 0x1 + argv; return argc; }")
        (codeDir / "dir2" / "bar.c").write("int bar(int x) { return x; }")
        fun(codeDir)
      }
    }
  }
}
