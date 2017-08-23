//       ___       ___       ___       ___       ___
//      /\  \     /\__\     /\__\     /\  \     /\__\
//     /::\  \   /:/ _/_   /:| _|_   /::\  \   /:/  /
//    /::\:\__\ /::-"\__\ /::|/\__\ /::\:\__\ /:/__/
//    \;:::/  / \;:;-",-" \/|::/  / \;:::/  / \:\  \
//     |:\/__/   |:|  |     |:/  /   |:\/__/   \:\__\
//      \|__|     \|__|     \/__/     \|__|     \/__/

package ru.rknrl.rpc

import akka.Done
import akka.actor._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, WebSocketRequest}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.util.ByteString
import com.trueaccord.scalapb.GeneratedMessage
import ru.rknrl.rpc.WebSocketServer.{WebClientConnected, WebClientDisconnected}

import scala.concurrent.Future

object WebSocketClient {
  def props(serverUrl: String, acceptWithActor: ActorRef ⇒ Props, serializer: Serializer) = {
    Props(classOf[WebSocketClient], serverUrl, acceptWithActor, serializer)
  }
}

class WebSocketClient(serverUrl: String,
                      acceptWithActor: ActorRef ⇒ Props,
                      serializer: Serializer)
  extends Actor with SocketMessage with ActorLogging {

  var receiveBuffer = ByteString.empty
  var serverRef = Option.empty[ActorRef]
  var clientRef = Option.empty[ActorRef]

  val sendBuffer = ByteString.newBuilder
  implicit val system = context.system
  implicit val materializer = ActorMaterializer()

  def receive = {
    case WebClientConnected(ref) ⇒
      serverRef = Some(ref)
      context.watch(ref)
      clientRef = Some(context.actorOf(acceptWithActor(self)))

    case Terminated(ref) if serverRef.contains(ref) ⇒
      serverRef = None
      context.stop(self)

    case WebClientDisconnected ⇒
      context.stop(self)

    case msg: GeneratedMessage ⇒ sendToServer(msg)

    case BinaryMessage.Strict(receivedData) ⇒
      val data = receiveBuffer ++ receivedData
      val (newBuffer, frames) = extractFrames(data, Nil)
      for (frame ← frames) {
        clientRef.foreach(_ ! serializer.bytesToMessage(frame.msgId, frame.byteString))
      }
      receiveBuffer = newBuffer

    case _: BinaryMessage.Streamed ⇒
      log.warning("Received BinaryMessage.Streamed")

    case any ⇒ log.warning(s"Unhandled message <$any>")
  }

  override def preStart(): Unit = {
    super.preStart()
    val req = WebSocketRequest(uri = serverUrl)
    val webSocketFlow = Http().webSocketClientFlow(req)
    val (in, out) = flow()
    val ((ws, upgradeResponse), closed) =
      out
        .viaMat(webSocketFlow)(Keep.both) // keep the materialized Future[WebSocketUpgradeResponse]
        .toMat(in)(Keep.both) // also keep the Future[Done]
        .run()
    import context.dispatcher
    val connected = upgradeResponse.flatMap { upgrade ⇒
      if (upgrade.response.status == StatusCodes.SwitchingProtocols) {
        Future.successful(Done)
      } else {
        throw new RuntimeException(s"Connection failed: ${upgrade.response.status}")
      }
    }
    closed.foreach(_ ⇒ log.warning("closed"))
  }

  override def postStop(): Unit = {
    super.postStop()
  }

  def sendToServer(msg: GeneratedMessage): Unit = {
    serverRef.foreach(_ ! BinaryMessage.Strict(buildMessage(msg, serializer)))
  }

  def flow(): (Sink[Message, Future[Done]], Source[Message, ActorRef]) = {
    val in: Sink[Message, Future[Done]] =
      Sink.foreach[Message] {
        case msg: BinaryMessage ⇒
          self ! msg
      }.mapMaterializedValue { f ⇒
        import context.dispatcher
        f.flatMap { _ ⇒
          self ! WebClientDisconnected
          f
        }
      }
    val out = Source.actorRef[BinaryMessage](8, OverflowStrategy.fail).mapMaterializedValue { ref ⇒
      self ! WebClientConnected(ref)
      ref
    }
    (in, out)
  }
}