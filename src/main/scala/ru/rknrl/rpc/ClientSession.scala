//       ___       ___       ___       ___       ___
//      /\  \     /\__\     /\__\     /\  \     /\__\
//     /::\  \   /:/ _/_   /:| _|_   /::\  \   /:/  /
//    /::\:\__\ /::-"\__\ /::|/\__\ /::\:\__\ /:/__/
//    \;:::/  / \;:;-",-" \/|::/  / \;:::/  / \:\  \
//     |:\/__/   |:|  |     |:/  /   |:\/__/   \:\__\
//      \|__|     \|__|     \/__/     \|__|     \/__/

package ru.rknrl.rpc

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.io.Tcp
import akka.io.Tcp._
import akka.util.ByteString
import com.trueaccord.scalapb.GeneratedMessage
import ru.rknrl.rpc.ClientSession.CloseConnection

object ClientSession {

  case object CloseConnection

  def props(tcp: ActorRef, acceptWithActor: ActorRef ⇒ Props, serializer: Serializer) =
    Props(classOf[ClientSession], tcp, acceptWithActor, serializer)
}

class ClientSession(var tcp: ActorRef, acceptWithActor: ActorRef ⇒ Props, serializer: Serializer) extends ClientSessionBase(acceptWithActor, serializer) {
  tcp ! Register(self)
}

abstract class ClientSessionBase(acceptWithActor: ActorRef ⇒ Props, serializer: Serializer) extends Actor with ActorLogging with SocketMessage {

  var tcp: ActorRef

  case object Ack extends Event

  var receiveBuffer = ByteString.empty

  val sendBuffer = ByteString.newBuilder

  var waitForAck = false

  val client = context.actorOf(acceptWithActor(self), s"client-" + self.path.name.hashCode.toString)

  def receive: Receive = {
    case Received(receivedData) ⇒
      val data = receiveBuffer ++ receivedData
      val (newBuffer, frames) = extractFrames(data, Nil)
      for (frame ← frames) client ! serializer.bytesToMessage(frame.msgId, frame.byteString)
      receiveBuffer = newBuffer

    case _: ConnectionClosed ⇒
      log.debug("connection closed")
      context stop self

    case msg: GeneratedMessage ⇒ send(msg)

    case CommandFailed(e) ⇒
      log.error("command failed " + e)
      context stop self

    case Ack ⇒
      waitForAck = false
      flush()

    case CloseConnection ⇒ tcp ! Tcp.Close
  }

  def send(msg: GeneratedMessage): Unit = {
    sendBuffer.append(buildMessage(msg, serializer))
    flush()
  }

  def flush(): Unit =
    if (!waitForAck && sendBuffer.length > 0) {
      waitForAck = true
      tcp ! Write(sendBuffer.result.compact, Ack)
      sendBuffer.clear()
    }
}


