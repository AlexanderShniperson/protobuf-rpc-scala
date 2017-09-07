//       ___       ___       ___       ___       ___
//      /\  \     /\__\     /\__\     /\  \     /\__\
//     /::\  \   /:/ _/_   /:| _|_   /::\  \   /:/  /
//    /::\:\__\ /::-"\__\ /::|/\__\ /::\:\__\ /:/__/
//    \;:::/  / \;:;-",-" \/|::/  / \;:::/  / \:\  \
//     |:\/__/   |:|  |     |:/  /   |:\/__/   \:\__\
//      \|__|     \|__|     \/__/     \|__|     \/__/

package ru.rknrl.rpc

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import akka.http.scaladsl.model.ws.BinaryMessage
import akka.stream.ActorMaterializer
import akka.util.ByteString
import com.trueaccord.scalapb.GeneratedMessage
import ru.rknrl.rpc.WebSocketServer.{WebClientConnected, WebClientDisconnected}

object WebSocketClientSession {
  def props(acceptWithActor: ActorRef ⇒ Props, serializer: Serializer) = {
    Props(classOf[WebSocketClientSession], acceptWithActor, serializer)
  }
}

private class WebSocketClientSession(acceptWithActor: ActorRef ⇒ Props,
                                     serializer: Serializer) extends Actor with SocketMessage with ActorLogging {
  var clientRef = Option.empty[ActorRef]
  val serverRef = context.actorOf(acceptWithActor(self))
  var receiveBuffer = ByteString.empty
  implicit val materializer = ActorMaterializer()

  def receive = {
    case msg: GeneratedMessage ⇒ clientRef.foreach(_ ! BinaryMessage.Strict(buildMessage(msg, serializer)))

    case BinaryMessage.Strict(receivedData) ⇒
      parseReceivedData(receivedData)

    case BinaryMessage.Streamed(receivedData) ⇒
      receivedData.runForeach { data ⇒
        parseReceivedData(data)
      }

    case WebClientConnected(a: ActorRef) ⇒
      clientRef = Some(a)
      context.watch(a)

    case WebClientDisconnected ⇒
      clientRef = None
      context.stop(self)

    case Terminated(a) if clientRef.contains(a) ⇒
      clientRef = None
      context.stop(self)

    case any ⇒ log.warning(s"Unhandled message <$any>")
  }

  override def postStop(): Unit =
    clientRef.foreach(context.stop)

  def parseReceivedData(receivedData: ByteString): Unit = {
    val data = receiveBuffer ++ receivedData
    val (newBuffer, frames) = extractFrames(data, Nil)
    for (frame ← frames) {
      serverRef ! serializer.bytesToMessage(frame.msgId, frame.byteString)
    }
    receiveBuffer = newBuffer
  }
}
