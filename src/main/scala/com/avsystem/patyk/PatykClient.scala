package com.avsystem.patyk

import com.avsystem.commons._
import com.avsystem.commons.serialization.cbor.{CborOutput, RawCbor}
import monix.eval.Task

import java.net.InetSocketAddress
import java.nio.channels.{ClosedSelectorException, SelectionKey, Selector, SocketChannel}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Promise

class PatykClient(address: InetSocketAddress, connectionPoolSize: Int = 1) extends RawPatyk {
  private lazy val selector = Selector.open()

  private class ServerConnection extends BaseBinaryConnection {
    protected val channel: SocketChannel = SocketChannel.open(address).setup { ch =>
      ch.configureBlocking(false)
      ch.register(selector, SelectionKey.OP_READ, this)
    }

    protected def selector: Selector = PatykClient.this.selector

    protected def dispatchReadData(data: RawCbor, responsePromise: Opt[Promise[RawCbor]]): Unit =
      responsePromise.foreach(_.success(data))

    def shutdown(): Unit = channel.shutdownOutput()
  }

  private lazy val connectionPool =
    IArraySeq.fill(connectionPoolSize)(new ServerConnection)

  private val roundRobinConnectionIdx = new AtomicInteger(0)

  private val eventDispatcherThread = new Thread(() => {
    try while (selector.isOpen) {
      selector.select { (key: SelectionKey) =>
        if (key.isReadable) {
          key.attachment.asInstanceOf[ServerConnection].doRead(key)
        }
        else if (key.isWritable) {
          key.attachment.asInstanceOf[ServerConnection].doWrite(key)
        }
      }
    } catch {
      case NonFatal(e) => e.printStackTrace()
    }
  })

  def start(): Unit = {
    connectionPool
    eventDispatcherThread.start()
  }

  def invoke(invocation: RawPatyk.Invocation): Task[RawCbor] = Task.deferFuture {
    val cbor = RawCbor(CborOutput.write(invocation, RawPatyk.InvocationCborLabels))
    val connectionIdx = roundRobinConnectionIdx.getAndIncrement() % connectionPoolSize
    val promise = Promise[RawCbor]()
    connectionPool(connectionIdx).queueWrite(cbor, Opt(promise))
    promise.future
  }

  def shutdown(): Unit = {
    connectionPool.foreach(_.shutdown())
  }
}
