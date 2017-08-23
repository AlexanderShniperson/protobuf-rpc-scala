//       ___       ___       ___       ___       ___
//      /\  \     /\__\     /\__\     /\  \     /\__\
//     /::\  \   /:/ _/_   /:| _|_   /::\  \   /:/  /
//    /::\:\__\ /::-"\__\ /::|/\__\ /::\:\__\ /:/__/
//    \;:::/  / \;:;-",-" \/|::/  / \;:::/  / \:\  \
//     |:\/__/   |:|  |     |:/  /   |:\/__/   \:\__\
//      \|__|     \|__|     \/__/     \|__|     \/__/

package ru.rknrl.rpc

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.http.scaladsl.model.ws.BinaryMessage
import akka.util.ByteString
import com.trueaccord.scalapb.GeneratedMessage

object WebSocketClientSession {
  def props(clientRef: ActorRef, acceptWithActor: ActorRef ⇒ Props, serializer: Serializer) = {
    Props(classOf[WebSocketClientSession], clientRef, acceptWithActor, serializer)
  }
}

private class WebSocketClientSession(clientRef: ActorRef,
                                     acceptWithActor: ActorRef ⇒ Props,
                                     serializer: Serializer) extends Actor with SocketMessage with ActorLogging {
  val serverRef = context.actorOf(acceptWithActor(self))
  var receiveBuffer = ByteString.empty

  def receive = {
    case BinaryMessage.Strict(receivedData) ⇒
      val data = receiveBuffer ++ receivedData
      val (newBuffer, frames) = extractFrames(data, Nil)
      for (frame ← frames) serverRef ! serializer.bytesToMessage(frame.msgId, frame.byteString)
      receiveBuffer = newBuffer

    case msg: GeneratedMessage ⇒ clientRef ! BinaryMessage.Strict(buildMessage(msg, serializer))

    case any ⇒ log.warning(s"Unhandled message <$any>")
  }

  override def postStop(): Unit = {
    context.stop(serverRef)
    context.stop(clientRef)
  }
}