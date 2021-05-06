package com.avsystem.patyk

import com.avsystem.commons._
import com.avsystem.commons.serialization.cbor.{CborOutput, RawCbor}
import com.typesafe.scalalogging.LazyLogging
import monix.eval.Task

import java.net.InetSocketAddress
import java.nio.channels.{ClosedSelectorException, SelectionKey, Selector, SocketChannel}
import java.util.concurrent.atomic.AtomicInteger

class PatykClient(
  address: InetSocketAddress,
  connectionPoolSize: Int = 1
) extends RawPatyk with LazyLogging {
  private lazy val selector = Selector.open()

  private class ServerConnection extends PatykConnection {
    val channel: SocketChannel = SocketChannel.open().setup { ch =>
      ch.configureBlocking(false)
      ch.register(selector, SelectionKey.OP_CONNECT | SelectionKey.OP_READ, this)
    }

    def selector: Selector = PatykClient.this.selector

    protected def dispatchRequest(reqId: Int, data: RawCbor): Unit =
      throw new Exception("Unexpected request in PatykClient")
  }

  private lazy val connectionPool =
    IArraySeq.fill(connectionPoolSize)(new ServerConnection)

  private val roundRobinConnectionIdx = new AtomicInteger(0)

  private val eventDispatcherThread = new Thread(() => {
    try while (selector.isOpen) {
      selector.select { (key: SelectionKey) =>
        if (key.isConnectable) {
          key.attachment.asInstanceOf[ServerConnection].channel.finishConnect()
        }
        else if (key.isReadable) {
          key.attachment.asInstanceOf[ServerConnection].doRead(key)
        }
        else if (key.isWritable) {
          key.attachment.asInstanceOf[ServerConnection].doWrite(key)
        }
      }
    } catch {
      case _: ClosedSelectorException =>
      case NonFatal(e) =>
        connectionPool.foreach(_.shutdown(e))
        logger.error("selector failure", e)
    }
  }).setup(_.setDaemon(true))

  def start(): Unit = {
    connectionPool.foreach(_.connect(address))
    eventDispatcherThread.start()
  }

  def invoke(invocation: RawPatyk.Invocation): Task[RawCbor] = Task.deferFuture {
    val cbor = RawCbor(CborOutput.write(invocation, RawPatyk.InvocationCborLabels))
    val connectionIdx = roundRobinConnectionIdx.getAndIncrement() % connectionPoolSize
    connectionPool(connectionIdx).queueRequest(cbor)
  }

  def shutdown(): Unit = {
    connectionPool.foreach(_.shutdown(new Exception("the client was shut down")))
    selector.close()
  }
}
