//       ___       ___       ___       ___       ___
//      /\  \     /\__\     /\__\     /\  \     /\__\
//     /::\  \   /:/ _/_   /:| _|_   /::\  \   /:/  /
//    /::\:\__\ /::-"\__\ /::|/\__\ /::\:\__\ /:/__/
//    \;:::/  / \;:;-",-" \/|::/  / \;:::/  / \:\  \
//     |:\/__/   |:|  |     |:/  /   |:\/__/   \:\__\
//      \|__|     \|__|     \/__/     \|__|     \/__/

package ru.rknrl.rpc

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import akka.io.Tcp._
import akka.io.{IO, Tcp}

object ClientConnection {
  def props(host: String, port: Int, acceptWithActor: ActorRef ⇒ Props, serializer: Serializer) =
    Props(classOf[ClientConnection], host, port, acceptWithActor, serializer)

  case object ConnectionOpened

  class ConnectionClosedException extends Exception
}

class ClientConnection(host: String, port: Int, acceptWithActor: ActorRef ⇒ Props, serializer: Serializer) extends ClientSessionBase(acceptWithActor, serializer) {

  import ru.rknrl.rpc.ClientConnection.{ConnectionClosedException, ConnectionOpened}
  import context.system

  val address = new InetSocketAddress(host, port)

  var tcp: ActorRef = _

  IO(Tcp) ! Connect(address)

  val connectionReceive: Receive = {
    case _: ConnectionClosed ⇒
      log.debug("connection closed")
      throw new ConnectionClosedException()

    case CommandFailed(e) ⇒
      log.error("command failed " + e)
      throw new ConnectionClosedException()

    case Connected(remote, local) ⇒
      val name = remote.getAddress.getHostAddress + ":" + remote.getPort
      log.debug("connected " + name)

      tcp = sender
      tcp ! Register(self)
      client ! ConnectionOpened
  }

  override def receive = connectionReceive orElse super.receive
}
