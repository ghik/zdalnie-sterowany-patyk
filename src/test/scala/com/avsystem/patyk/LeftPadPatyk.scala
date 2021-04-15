package com.avsystem.patyk

import monix.eval.Task
import monix.execution.Scheduler

import java.net.InetSocketAddress
import scala.concurrent.duration.Duration

/**
 * Our RPC interface. It can have multiple methods. Every method must return a Monix Task.
 * Every parameter type and return type (wrapped in Monix Task) must have a `GenCodec`.
 */
trait LeftPadPatyk {
  def leftPad(text: String, padding: Char, length: Int): Task[String]
}
object LeftPadPatyk extends PatykCompanion[LeftPadPatyk]

/**
 * Server side RPC implementation.
 */
class LeftPadPatykImpl extends LeftPadPatyk {
  def leftPad(text: String, padding: Char, length: Int): Task[String] = Task {
    require(length >= 0, s"invalid length: $length")
    if (text.length < length)
      padding.toString.repeat(length - text.length) + text
    else text
  }
}

object LeftPadServer {
  implicit def scheduler: Scheduler = Scheduler.global

  def main(args: Array[String]): Unit = {
    val patykServer = new PatykServer[LeftPadPatyk](new InetSocketAddress(6969), new LeftPadPatykImpl)
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
      val leftPadPatyk: LeftPadPatyk = RawPatyk.asReal[LeftPadPatyk](patykClient)

      val results = Task.parTraverse(List.range(0, 128)) { i =>
        leftPadPatyk.leftPad(s"foo$i" * 150, '_', 10)
      }.runSyncUnsafe(Duration.Inf)

      println(s"Left-padded: $results")

      println(leftPadPatyk.leftPad("bu", '+', -4).runSyncUnsafe(Duration.Inf))
    } finally {
      patykClient.shutdown()
    }
  }
}
