package com.avsystem.patyk

import com.avsystem.commons._
import com.avsystem.commons.serialization.GenCodec
import com.avsystem.commons.serialization.cbor.{CborInput, CborReader, RawCbor}
import com.typesafe.scalalogging.LazyLogging
import monix.eval.Task
import monix.execution.Scheduler

import java.net.InetSocketAddress
import java.nio.channels._

class PatykServer[T: RawPatyk.AsRawRpc](
  address: InetSocketAddress,
  patykImpl: T
)(implicit
  scheduler: Scheduler
) extends LazyLogging {

  private val rawPatyk = RawPatyk.asRaw(patykImpl)

  private lazy val selector = Selector.open()
  private lazy val serverChannel =
    ServerSocketChannel.open().setup { s =>
      s.configureBlocking(false)
      s.register(selector, SelectionKey.OP_ACCEPT)
    }

  private class ClientConnection(val channel: SocketChannel) extends PatykConnection {
    def selector: Selector = PatykServer.this.selector

    protected def dispatchRequest(data: RawCbor): Unit =
      Task.defer {
        val cborInput = new CborInput(new CborReader(data), RawPatyk.InvocationCborLabels)
        val invocation = GenCodec.read[RawPatyk.Invocation](cborInput)
        rawPatyk.invoke(invocation)
      }.runAsync {
        case Right(response) => queueResponse(response)
        case Left(cause) => queueError(cause)
      }
  }

  private val eventDispatcherThread = new Thread(() => {
    try while (selector.isOpen) {
      selector.select { (key: SelectionKey) =>
        if (key.isAcceptable) {
          val channel = key.channel.asInstanceOf[ServerSocketChannel].accept()
          channel.configureBlocking(false)
          channel.register(selector, SelectionKey.OP_READ, new ClientConnection(channel))
          logger.debug(s"accepted new connection: ${channel.getRemoteAddress}")
        }
        else if (key.isReadable) {
          key.attachment.asInstanceOf[ClientConnection].doRead(key)
        }
        else if (key.isWritable) {
          key.attachment.asInstanceOf[ClientConnection].doWrite(key)
        }
      }
    } catch {
      case _: ClosedSelectorException =>
      case NonFatal(e) =>
        logger.error("selector failure", e)
    }
  })

  def start(): Unit = {
    serverChannel.bind(address)
    eventDispatcherThread.start()
  }

  def join(): Unit =
    eventDispatcherThread.join()
}
