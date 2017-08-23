//       ___       ___       ___       ___       ___
//      /\  \     /\__\     /\__\     /\  \     /\__\
//     /::\  \   /:/ _/_   /:| _|_   /::\  \   /:/  /
//    /::\:\__\ /::-"\__\ /::|/\__\ /::\:\__\ /:/__/
//    \;:::/  / \;:;-",-" \/|::/  / \;:::/  / \:\  \
//     |:\/__/   |:|  |     |:/  /   |:\/__/   \:\__\
//      \|__|     \|__|     \/__/     \|__|     \/__/

package ru.rknrl.rpc

import akka.util.ByteString
import com.trueaccord.scalapb.GeneratedMessage

import scala.annotation.tailrec

trait SocketMessage {
  implicit val byteOrder = java.nio.ByteOrder.LITTLE_ENDIAN
  val headerSize = 4 + 4
  val maxSize = 512 * 1024

  case class Frame(msgId: Int, byteString: ByteString)

  @tailrec
  protected final def extractFrames(data: ByteString, frames: List[Frame]): (ByteString, Seq[Frame]) =
    if (data.length < headerSize)
      (data.compact, frames)
    else {
      val iterator = data.iterator
      val msgId = iterator.getInt
      val size = iterator.getInt

      if (size < 0 || size > maxSize)
        throw new IllegalArgumentException(s"received too large frame of size $size (max = $maxSize)")

      val totalSize = headerSize + size
      if (data.length >= totalSize)
        extractFrames(data drop totalSize, frames :+ Frame(msgId, data.slice(headerSize, totalSize)))
      else
        (data.compact, frames)
    }

  protected def buildMessage(msg: GeneratedMessage, serializer: Serializer): ByteString = {
    val builder = ByteString.newBuilder
    val os = builder.asOutputStream
    msg.writeDelimitedTo(os)
    val msgByteString = builder.result()

    val msgId = serializer.messageToId(msg)
    val sendBuffer = ByteString.newBuilder
    sendBuffer.putInt(msgId)
    sendBuffer.putInt(msgByteString.length)
    sendBuffer.append(msgByteString)
    sendBuffer.result()
  }
}