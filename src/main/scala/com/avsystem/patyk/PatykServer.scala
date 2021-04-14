package com.avsystem.patyk

import com.avsystem.commons._
import com.avsystem.commons.serialization.cbor.{CborInput, RawCbor}
import monix.eval.Task
import monix.execution.Scheduler

import java.net.InetSocketAddress
import java.nio.channels.{SelectionKey, Selector, ServerSocketChannel, SocketChannel}

class PatykServer[T: RawPatyk.AsRawRpc](address: InetSocketAddress, patykImpl: T)(implicit scheduler: Scheduler) {
  private val rawPatyk = RawPatyk.asRaw(patykImpl)

  private lazy val selector = Selector.open()

  private lazy val serverChannel =
    ServerSocketChannel.open().setup { s =>
      s.configureBlocking(false)
      s.register(selector, SelectionKey.OP_ACCEPT)
    }

  private class ClientConnection(val channel: SocketChannel) extends BaseBinaryConnection {
    protected def selector: Selector = PatykServer.this.selector

    protected def dispatchReadData(data: RawCbor, responsePromise: Opt[Promise[RawCbor]]): Unit =
      Task.defer {
        val invocation = CborInput.read[RawPatyk.Invocation](data.bytes, RawPatyk.InvocationCborLabels)
        rawPatyk.invoke(invocation)
      }.runAsync {
        case Right(response) => queueWrite(response, Opt.Empty)
        case Left(_) => //TODO: send the failure back somehow
      }
  }

  private val eventDispatcherThread = new Thread(() => {
    try while (selector.isOpen) {
      selector.select { (key: SelectionKey) =>
        if (key.isAcceptable) {
          val channel = key.channel.asInstanceOf[ServerSocketChannel].accept()
          channel.configureBlocking(false)
          channel.register(selector, SelectionKey.OP_READ, new ClientConnection(channel))
          println(s"accepted new connection: ${channel.getRemoteAddress}")
        }
        else if (key.isReadable) {
          key.attachment.asInstanceOf[ClientConnection].doRead(key)
        }
        else if (key.isWritable) {
          key.attachment.asInstanceOf[ClientConnection].doWrite(key)
        }
      }
    } catch {
      case NonFatal(e) =>
        e.printStackTrace()
    }
  })

  def start(): Unit = {
    serverChannel.bind(address)
    eventDispatcherThread.start()
  }

  def join(): Unit =
    eventDispatcherThread.join()
}
