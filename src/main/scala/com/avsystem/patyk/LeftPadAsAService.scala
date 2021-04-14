package com.avsystem.patyk

import monix.eval.Task
import monix.execution.Scheduler

import java.net.InetSocketAddress
import scala.concurrent.duration.Duration

trait LeftPadAsAService {
  def leftPad(text: String, padding: Char, length: Int): Task[String]
}
object LeftPadAsAService extends PatykCompanion[LeftPadAsAService]

object LeftPadServer {
  implicit def scheduler: Scheduler = Scheduler.global

  def main(args: Array[String]): Unit = {
    val patykImpl = new LeftPadAsAService {
      def leftPad(text: String, padding: Char, length: Int): Task[String] = Task {
        println(s"happily left-padding $text to length $length with $padding")
        if (text.length < length)
          padding.toString.repeat(length - text.length) + text
        else text
      }
    }

    val endpoint = new PatykServer(new InetSocketAddress(6969), patykImpl)
    endpoint.start()
    endpoint.join()
  }
}

object LeftPadClient {
  implicit def scheduler: Scheduler = Scheduler.global

  def main(args: Array[String]): Unit = {
    val patykClient = new PatykClient(new InetSocketAddress(6969), 16)
    patykClient.start()

    val zdalnieSterowanyPatyk = RawPatyk.asReal[LeftPadAsAService](patykClient)

    val results = Task.parTraverse(List.range(0, 128)) { i =>
      zdalnieSterowanyPatyk.leftPad(s"foo$i", '_', 10)
    }.runSyncUnsafe(Duration.Inf)

    println(s"Left-padded: $results")
    patykClient.shutdown()
  }
}
