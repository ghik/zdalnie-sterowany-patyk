package com.avsystem.patyk

import com.avsystem.commons._
import com.avsystem.commons.serialization.cbor.RawCbor
import com.avsystem.patyk.PatykConnection._
import com.typesafe.scalalogging.LazyLogging

import java.nio.ByteBuffer
import java.nio.channels.{SelectionKey, Selector, SocketChannel}
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedDeque
import scala.annotation.tailrec

object PatykConnection {
  private case class QueuedWrite(
    msgType: MessageType,
    data: Array[Byte],
    responsePromise: Opt[Promise[RawCbor]]
  )

  final val HeaderSize = 3
  final val BufferSize = 4096
  final val MaxMessageSize = BufferSize - HeaderSize
}
abstract class PatykConnection extends LazyLogging {
  protected def channel: SocketChannel
  protected def selector: Selector

  protected def dispatchRequest(data: RawCbor): Unit

  // assuming that requests are not larger than 4096 bytes
  private val readBuffer: ByteBuffer = ByteBuffer.allocate(BufferSize).limit(HeaderSize)
  private var readingHeader = true
  private var msgType: MessageType = _

  private val writeBuffer: ByteBuffer = ByteBuffer.allocate(BufferSize).limit(0)
  private val writeQueue = new ConcurrentLinkedDeque[QueuedWrite]
  private val responseQueue = new JArrayDeque[Promise[RawCbor]] // this one doesn't need to be concurrent

  final def doRead(key: SelectionKey): Unit = {
    @tailrec def loop(): Unit = {
      val read = channel.read(readBuffer)
      // remaining == 0 means we have all the bytes that we need to proceed
      if (read > 0 && readBuffer.remaining() == 0) {
        if (readingHeader) {
          msgType = MessageType.values(readBuffer.get(0))
          val dataLen = readBuffer.getShort(1)
          require(dataLen >= 0 && dataLen <= MaxMessageSize)
          readBuffer.rewind().limit(dataLen)
          readingHeader = false
        } else {
          val bytes = new Array[Byte](readBuffer.limit())
          readBuffer.rewind().get(bytes).rewind().limit(HeaderSize)
          readingHeader = true
          msgType match {
            case MessageType.Request =>
              dispatchRequest(RawCbor(bytes))
            case MessageType.Response =>
              responseQueue.pollFirst().success(RawCbor(bytes))
            case MessageType.Error =>
              responseQueue.pollFirst().failure(PatykException(new String(bytes, StandardCharsets.UTF_8)))
          }
        }
        loop()
      } else if (read < 0) {
        logger.debug(s"connection ${channel.getRemoteAddress} closed")
        key.cancel()
        channel.close()
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
        val QueuedWrite(msgType, data, responsePromise) = writeQueue.pollFirst()
        responsePromise.foreach(responseQueue.addLast)
        writeBuffer.rewind().limit(HeaderSize + data.length)
          .put(msgType.ordinal.toByte).putShort(data.length.toShort).put(data).rewind()
      }

      // check if there's anything to write
      if (writeBuffer.remaining() > 0) {
        // try writing it to the channel
        if (channel.isConnected && channel.write(writeBuffer) > 0) {
          // we successfully wrote something, try writing more
          loop()
        } else {
          // could not write anything this time, subscribe to OP_WRITE and wait for another opportunity
          key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE)
        }
      } else {
        // nothing else to write, unsubscribe from OP_WRITE
        key.interestOps(SelectionKey.OP_READ)
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
    queueWrite(MessageType.Request, data.bytes, promise.opt)
    promise.future
  }

  def queueResponse(data: RawCbor): Unit =
    queueWrite(MessageType.Response, data.bytes, Opt.Empty)

  def queueError(cause: Throwable): Unit = {
    val errorMsg = cause match {
      case PatykException(msg, _) => msg
      case _ => s"${cause.getClass.getSimpleName}: ${cause.getMessage}"
    }
    queueWrite(MessageType.Error, errorMsg.getBytes(StandardCharsets.UTF_8), Opt.Empty)
  }

  private def queueWrite(msgType: MessageType, data: Array[Byte], responsePromise: Opt[Promise[RawCbor]]): Unit = {
    writeQueue.addLast(QueuedWrite(msgType, data, responsePromise))
    // TODO: maybe there is a safe way to avoid doing the wakeup() every single time
    channel.keyFor(selector).interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE)
    selector.wakeup()
  }
}
