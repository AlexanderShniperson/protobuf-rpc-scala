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

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws._
import akka.http.scaladsl.server.Directives._
import akka.stream.scaladsl._
import akka.stream.{ActorMaterializer, OverflowStrategy}
import ru.rknrl.rpc.WebSocketServer.{WebClientConnected, WebClientDisconnected}

import scala.concurrent.Future

object WebSocketServer {
  def props(host: String, port: Int, acceptWithActor: ActorRef ⇒ Props, serializer: Serializer, messagePoolSize: Int): Props = {
    Props(classOf[WebSocketServer], host, port, acceptWithActor, serializer, messagePoolSize)
  }

  private[rpc] case class WebClientConnected(ref: ActorRef)

  private[rpc] case object WebClientDisconnected

}

private class WebSocketServer(host: String,
                              port: Int,
                              acceptWithActor: ActorRef ⇒ Props,
                              serializer: Serializer,
                              messagePoolSize: Int) extends Actor with ActorLogging {

  implicit val as = context.system
  implicit val materializer = ActorMaterializer()

  private var bindingFuture = Option.empty[Future[Http.ServerBinding]]

  override def receive = {
    case _ ⇒
  }

  def flow(): Flow[Message, Message, Any] = {
    val clientRef = context.actorOf(WebSocketClientSession.props(acceptWithActor, serializer))

    val in = Sink.actorRef(clientRef, WebClientDisconnected)

    val out = Source.actorRef(messagePoolSize, OverflowStrategy.fail).mapMaterializedValue { a ⇒
      clientRef ! WebClientConnected(a)
      a
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