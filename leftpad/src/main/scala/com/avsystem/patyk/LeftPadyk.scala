package com.avsystem.patyk

import monix.eval.Task
import monix.execution.Scheduler

import java.io.{FileInputStream, FileOutputStream}
import java.net.InetSocketAddress
import scala.concurrent.duration.Duration

/**
 * Our RPC interface. It can have multiple methods. Every method must return a Monix Task.
 * Every parameter type and return type (wrapped in Monix Task) must have a `GenCodec`.
 */
trait LeftPadyk {
  def leftPad(text: String, padding: Char, length: Int): Task[String]

  def write(file: String, content: Array[Byte]): Task[Unit]
  def read(file: String): Task[Array[Byte]]
}
object LeftPadyk extends PatykCompanion[LeftPadyk]

/**
 * Server side RPC implementation.
 */
class LeftPadykImpl extends LeftPadyk {
  import LeftPadykImpl._

  def leftPad(text: String, padding: Char, length: Int): Task[String] = Task {
    require(length >= 0, s"invalid length: $length")
    if (text.length < length)
      padding.toString.repeat(length - text.length) + text
    else text
  }

  def write(file: String, content: Array[Byte]): Task[Unit] = Task {
    val fos = new FileOutputStream(s"$DataDir/$file")
    try fos.write(content) finally fos.close()
  }

  def read(file: String): Task[Array[Byte]] = Task {
    val fis = new FileInputStream(s"$DataDir/$file")
    try fis.readAllBytes() finally fis.close()
  }
}
object LeftPadykImpl {
  final val DataDir = sys.env.getOrElse("PATYK_DATA_DIR", "data")
}

object LeftPadServer {
  implicit def scheduler: Scheduler = Scheduler.global

  def main(args: Array[String]): Unit = {
    val patykServer = new PatykServer[LeftPadyk](new InetSocketAddress(6969), new LeftPadykImpl)
    patykServer.start()
    patykServer.join()
  }
}

object LeftPadClient {
  implicit def scheduler: Scheduler = Scheduler.global

  def main(args: Array[String]): Unit = {
    val patykClient = new PatykClient(new InetSocketAddress(6969), 8)
    patykClient.start()

    try {
      // a macro generated proxy for LeftPadPatyk that uses PatykClient to send invocations to the server
      val leftPadyk: LeftPadyk = RawPatyk.asReal[LeftPadyk](patykClient)

      val results = Task.parTraverse(List.range(0, 128)) { i =>
        leftPadyk.leftPad(s"foo$i", '_', 10).foreachL(s => println(s"Left padded: $s"))
      }.runSyncUnsafe(Duration.Inf)

    } finally {
      patykClient.shutdown()
    }
  }
}
