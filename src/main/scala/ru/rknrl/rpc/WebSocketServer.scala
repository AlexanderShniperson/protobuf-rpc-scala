//       ___       ___       ___       ___       ___
//      /\  \     /\__\     /\__\     /\  \     /\__\
//     /::\  \   /:/ _/_   /:| _|_   /::\  \   /:/  /
//    /::\:\__\ /::-"\__\ /::|/\__\ /::\:\__\ /:/__/
//    \;:::/  / \;:;-",-" \/|::/  / \;:::/  / \:\  \
//     |:\/__/   |:|  |     |:/  /   |:\/__/   \:\__\
//      \|__|     \|__|     \/__/     \|__|     \/__/

package ru.rknrl.rpc

/* for ssl
import java.io.FileInputStream
import java.security.{KeyStore, SecureRandom}
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}
import akka.http.scaladsl.{ConnectionContext, HttpsConnectionContext}
*/

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws._
import akka.http.scaladsl.server.Directives._
import akka.stream.scaladsl._
import akka.stream.{ActorMaterializer, OverflowStrategy}
import ru.rknrl.rpc.WebSocketServer.{WebClientConnected, WebClientDisconnected}

import scala.concurrent.Future

object WebSocketServer {
  def props(host: String, port: Int, acceptWithActor: ActorRef ⇒ Props, serializer: Serializer): Props = {
    Props(classOf[WebSocketServer], host, port, acceptWithActor, serializer)
  }

  case class WebClientConnected(ref: ActorRef)

  case object WebClientDisconnected

}

private class WebSocketServer(host: String,
                              port: Int,
                              acceptWithActor: ActorRef ⇒ Props,
                              serializer: Serializer) extends Actor with ActorLogging {

  implicit val as = context.system
  implicit val materializer = ActorMaterializer()

  private var bindingFuture = Option.empty[Future[Http.ServerBinding]]
  var clients = Map.empty[String, ActorRef] // key -> remoteClient; value -> clientSession

  override def receive = {
    case WebClientConnected(ref) ⇒
      val clientSession = context.actorOf(WebSocketClientSession.props(ref, acceptWithActor, serializer))
      context.watch(ref)
      clients = clients.updated(parseClientRef(ref), clientSession)

    case msg: BinaryMessage.Strict ⇒
      clients.get(parseClientRef(sender())) match {
        case None ⇒ log.warning(s"Received message from unknown remote client <${parseClientRef(sender())}>")
        case Some(clientSession) ⇒ clientSession forward msg
      }

    case _: BinaryMessage.Streamed ⇒
      log.warning("Received BinaryMessage.Streamed")

    case Terminated(ref) ⇒
      clientDisconnected(ref)

    case WebClientDisconnected ⇒
      clientDisconnected(sender())
  }

  def clientDisconnected(ref: ActorRef): Unit = {
    clients.get(parseClientRef(ref)).foreach { clientSession ⇒
      context.stop(clientSession)
      clients -= parseClientRef(ref)
    }
  }

  def parseClientRef(ref: ActorRef): String = {
    val name = ref.path.name
      .replace("actorRefSource", "actorRef")
      .replace("actorRefSink", "actorRef")
      .replace("-1-", "-") // flow actor Sink
      .replace("-2-", "-") // flow actor Source
    ref.path.toStringWithoutAddress
      .replace(ref.path.name, name)
  }

  def flow(): Flow[Message, Message, Any] = {
    val in = Sink.actorRef(self, WebClientDisconnected)
    val out = Source.actorRef(8, OverflowStrategy.fail).mapMaterializedValue { ref ⇒
      self ! WebClientConnected(ref)
      ref
    }
    Flow.fromSinkAndSource(in, out)
  }

  override def preStart(): Unit = {
    /* SSL
    // todo: convert pem to pk12 run command below
    // openssl pkcs12 -export -inkey private.key -in all.pem -name test -out test.p12

    val ksPassword = "123".toCharArray // do not store passwords in code, read them from somewhere safe!

    val ks = KeyStore.getInstance("PKCS12")
    val keystore = new FileInputStream("/etc/letsencrypt/live/domain.example.com/test.p12")

    require(keystore != null, "Keystore required!")
    ks.load(keystore, ksPassword)

    val keyManagerFactory = KeyManagerFactory.getInstance("SunX509")
    keyManagerFactory.init(ks, ksPassword)

    val tmf = TrustManagerFactory.getInstance("SunX509")
    tmf.init(ks)

    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(keyManagerFactory.getKeyManagers, tmf.getTrustManagers, new SecureRandom)
    val https: HttpsConnectionContext = ConnectionContext.https(sslContext)
    Http().setDefaultServerHttpContext(https)
    */

    val webSocketRoute = path("ws") {
      handleWebSocketMessages(flow())
    }
    bindingFuture = Some(Http().bindAndHandle(webSocketRoute, host, port))
  }

  override def postStop() = {
    import context.dispatcher
    bindingFuture.foreach { f ⇒
      f.flatMap(_.unbind())
        .onComplete { _ ⇒
          // system.terminate()
        }
    }
    super.postStop()
  }
}

