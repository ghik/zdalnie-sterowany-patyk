package com.avsystem.patyk

import com.avsystem.commons._
import com.avsystem.commons.serialization.cbor.RawCbor
import com.avsystem.patyk.BaseBinaryConnection.QueuedWrite

import java.nio.ByteBuffer
import java.nio.channels.{SelectionKey, Selector, SocketChannel}
import java.util.concurrent.ConcurrentLinkedDeque
import scala.annotation.tailrec

object BaseBinaryConnection {
  private case class QueuedWrite(data: RawCbor, responsePromise: Opt[Promise[RawCbor]])
}
abstract class BaseBinaryConnection {
  protected def channel: SocketChannel
  protected def selector: Selector

  protected def dispatchReadData(data: RawCbor, responsePromise: Opt[Promise[RawCbor]]): Unit

  // assuming that requests are not larger than 4096 bytes
  private val readBuffer: ByteBuffer = ByteBuffer.allocate(4096).limit(2)
  private var readingLen = true

  private val writeBuffer: ByteBuffer = ByteBuffer.allocate(4096).limit(0)
  private val writeQueue = new ConcurrentLinkedDeque[QueuedWrite]
  private val responseQueue = new JArrayDeque[Promise[RawCbor]] // this one doesn't need to be concurrent

  @tailrec final def doRead(key: SelectionKey): Unit = {
    val read = channel.read(readBuffer)
    // remaining == 0 means we have all the bytes that we need to proceed
    if (read > 0 && readBuffer.remaining() == 0) {
      if (readingLen) {
        val dataLen = readBuffer.getShort(0) //TODO: validate
        readBuffer.rewind().limit(dataLen)
        readingLen = false
      } else {
        val bytes = new Array[Byte](readBuffer.limit())
        readBuffer.rewind().get(bytes).rewind().limit(2)
        readingLen = true
        dispatchReadData(RawCbor(bytes), responseQueue.pollFirst().opt)
      }
      doRead(key)
    } else if (read < 0) {
      println(s"connection ${channel.getRemoteAddress} closed")
      channel.close()
      key.cancel()
    }
  }

  @tailrec final def doWrite(key: SelectionKey): Unit = {
    // writeBuffer.remaining() == 0 means that this buffer has been fully written, try fetching next queued write
    if (writeBuffer.remaining() == 0 && !writeQueue.isEmpty) {
      val QueuedWrite(data, responsePromise) = writeQueue.pollFirst()
      responsePromise.foreach(responseQueue.addLast)
      writeBuffer.rewind().limit(2 + data.length)
        .putShort(data.length.toShort)
        .put(data.bytes, data.offset, data.length)
        .rewind()
    }

    if (writeBuffer.remaining() > 0) {
      // there is stuff to write, so write it
      if (channel.isConnected && channel.write(writeBuffer) > 0) {
        doWrite(key) // we successfully wrote something, try writing more
      } else {
        key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE)
      }
    } else {
      // nothing else to write, unsubscribe from OP_WRITE
      key.interestOps(SelectionKey.OP_READ)
    }
  }

  def queueWrite(cbor: RawCbor, responsePromise: Opt[Promise[RawCbor]]): Unit = {
    val writeQueueWasEmpty = writeQueue.isEmpty
    writeQueue.addLast(QueuedWrite(cbor, responsePromise))
    // there's a chance that more than one thread does this at once but that's no problem
    if (writeQueueWasEmpty) {
      channel.keyFor(selector).interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE)
      selector.wakeup()
    }
  }
}
