package com.avsystem.patyk

import com.avsystem.commons._
import com.avsystem.commons.serialization.cbor.RawCbor
import com.avsystem.patyk.PatykConnection._
import com.typesafe.scalalogging.LazyLogging

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{SelectionKey, Selector, SocketChannel}
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec
import scala.collection.mutable

object PatykConnection {
  private case class QueuedWrite(
    msgType: MessageType,
    reqId: Int,
    data: Array[Byte],
    responsePromise: Opt[Promise[RawCbor]]
  )

  final val HeaderSize = 1 + 4 + 4 // message type + request id + payload length
  final val BufferSize = 1024 * 1024
  final val MaxMessageSize = BufferSize - HeaderSize
}
abstract class PatykConnection extends LazyLogging {
  def channel: SocketChannel
  def selector: Selector

  protected def dispatchRequest(id: Int, data: RawCbor): Unit

  private val readBuffer: ByteBuffer = ByteBuffer.allocate(BufferSize).limit(HeaderSize)
  private var readingHeader = true
  private var msgType: MessageType = _
  private var requestId: Int = _

  private val writeBuffer: ByteBuffer = ByteBuffer.allocate(BufferSize).limit(0)
  private val writeQueue = new ConcurrentLinkedDeque[QueuedWrite]
  private val responsePromises = new mutable.LongMap[Promise[RawCbor]] // this one doesn't need to be concurrent
  private val nextRequestId = new AtomicInteger(0)

  def connect(address: InetSocketAddress): Unit =
    channel.connect(address)

  def doRead(key: SelectionKey): Unit = {
    @tailrec def loop(): Unit = {
      val read = channel.read(readBuffer)
      // remaining == 0 means we have all the bytes that we need to proceed
      if (read > 0 && readBuffer.remaining() == 0) {
        if (readingHeader) {
          readBuffer.rewind()
          msgType = MessageType.values(readBuffer.get())
          requestId = readBuffer.getInt()
          val dataLen = readBuffer.getInt()
          require(dataLen >= 0 && dataLen <= MaxMessageSize)
          readBuffer.rewind().limit(dataLen)
          readingHeader = false
        } else {
          val bytes = new Array[Byte](readBuffer.limit())
          readBuffer.rewind().get(bytes).rewind().limit(HeaderSize)
          readingHeader = true
          msgType match {
            case MessageType.Request =>
              dispatchRequest(requestId, RawCbor(bytes))
            case MessageType.Response =>
              responsePromises.remove(requestId).foreach(_.success(RawCbor(bytes)))
            case MessageType.Error =>
              responsePromises.remove(requestId).foreach(_.failure(PatykException(new String(bytes, StandardCharsets.UTF_8))))
          }
        }
        loop()
      } else if (read < 0) {
        logger.debug(s"connection ${channel.getRemoteAddress} closed")
        key.cancel()
        channel.close() // TODO: this causes a RST to be sent, can we do this gracefully?
      }
    }

    try loop() catch {
      case NonFatal(e) =>
        logger.error(s"failure during channel read", e)
        channel.close()
    }
  }

  def doWrite(key: SelectionKey): Unit = {
    @tailrec def loop(): Unit = {
      // writeBuffer.remaining() == 0 means that this buffer has been fully written
      // try fetching the next queued write and fill the buffer
      if (writeBuffer.remaining() == 0 && !writeQueue.isEmpty) {
        val QueuedWrite(msgType, reqId, data, responsePromise) = writeQueue.pollFirst()
        responsePromise.foreach(responsePromises.update(reqId, _))
        writeBuffer.rewind().limit(HeaderSize + data.length)
          .put(msgType.ordinal.toByte).putInt(reqId).putInt(data.length).put(data).rewind()
      }

      // check if there's anything to write
      if (writeBuffer.remaining() > 0) {
        // try writing it to the channel
        if (channel.isConnected && channel.write(writeBuffer) > 0) {
          // we successfully wrote something, try writing more
          loop()
        } else {
          // could not write anything this time, subscribe to OP_WRITE and wait for another opportunity
          key.interestOpsOr(SelectionKey.OP_WRITE)
        }
      } else {
        // nothing else to write, unsubscribe from OP_WRITE
        key.interestOpsAnd(~SelectionKey.OP_WRITE)
      }
    }
    try loop() catch {
      case NonFatal(e) =>
        logger.error(s"failure during channel write", e)
        channel.close()
    }
  }

  def queueRequest(data: RawCbor): Future[RawCbor] = {
    val promise = Promise[RawCbor]()
    queueWrite(MessageType.Request, nextRequestId.getAndIncrement(), data.compact.bytes, promise.opt)
    promise.future
  }

  def queueResponse(reqId: Int, data: RawCbor): Unit =
    queueWrite(MessageType.Response, reqId, data.compact.bytes, Opt.Empty)

  def queueError(reqId: Int, cause: Throwable): Unit = {
    val errorMsg = cause match {
      case PatykException(msg, _) => msg
      case _ => s"${cause.getClass.getSimpleName}: ${cause.getMessage}"
    }
    queueWrite(MessageType.Error, reqId, errorMsg.getBytes(StandardCharsets.UTF_8), Opt.Empty)
  }

  private def queueWrite(msgType: MessageType, reqId: Int, data: Array[Byte], responsePromise: Opt[Promise[RawCbor]]): Unit = {
    writeQueue.addLast(QueuedWrite(msgType, reqId, data, responsePromise))
    // TODO: maybe there is a safe way to avoid doing the wakeup() every single time
    channel.keyFor(selector).interestOpsOr(SelectionKey.OP_WRITE)
    selector.wakeup()
  }

  def shutdown(cause: Throwable): Unit = {
    channel.shutdownOutput()
    responsePromises.foreachValue(_.failure(cause))
    responsePromises.clear()
  }
}
