package com.bitwise.bitmarket.common.protocol.gateway

import java.io.IOException

import akka.actor.{Props, Terminated, ActorRef, Actor}
import com.google.protobuf.{RpcCallback, RpcController}
import com.googlecode.protobuf.pro.duplex.PeerInfo
import com.googlecode.protobuf.pro.duplex.execute.ServerRpcController

import com.bitwise.bitmarket.common.PeerConnection
import com.bitwise.bitmarket.common.protocol._
import com.bitwise.bitmarket.common.protocol.gateway.MessageGateway._
import com.bitwise.bitmarket.common.protocol.protobuf.ProtoMapping.fromProtobuf
import com.bitwise.bitmarket.common.protocol.protobuf.DefaultProtoMappings._
import com.bitwise.bitmarket.common.protocol.protobuf.{BitmarketProtobuf => proto}
import com.bitwise.bitmarket.common.protorpc.{PeerSession, PeerServer}

private[gateway] class ProtoRpcMessageGateway(serverInfo: PeerInfo) extends Actor {

  import ProtoRpcMessageGateway._

  /** Metadata on message subscription requested by an actor. */
  private case class MessageSubscription(filter: ReceiveMessage => Boolean)

  private class PeerServiceImpl extends proto.PeerService.Interface {

    override def notifyMatch(
        controller: RpcController,
        request: proto.OrderMatch,
        done: RpcCallback[proto.Void]) {
      dispatchToSubscriptions(fromProtobuf(request), clientPeerConnection(controller))
      done.run(VoidResponse)
    }

    override def requestExchange(
        controller: RpcController,
        request: proto.ExchangeRequest,
        done: RpcCallback[proto.ExchangeRequestResponse]) {
      dispatchToSubscriptions(fromProtobuf(request), clientPeerConnection(controller))
      done.run(SuccessExchangeResponse)
    }

    private def clientPeerConnection(controller: RpcController) = {
      val info = controller.asInstanceOf[ServerRpcController].getRpcClient.getServerInfo
      PeerConnection(info.getHostName, info.getPort)
    }
  }

  private val server: PeerServer = new PeerServer(
    serverInfo, proto.PeerService.newReflectiveService(new PeerServiceImpl()))

  private var subscriptions = Map.empty[ActorRef, MessageSubscription]
  private var sessions = Map.empty[PeerConnection, PeerSession]

  override def preStart() {
    val starting = server.start()
    starting.await()
    if (!starting.isSuccess) {
      server.shutdown()
      throw starting.cause()
    }
  }

  override def postStop() {
    server.shutdown()
  }

  override def receive = {
    case m @ ForwardMessage(msg, dest) =>
      forward(sender, dest, msg, m.send)
    case Subscribe(filter) =>
      subscriptions += sender -> MessageSubscription(filter)
    case Unsubscribe =>
      subscriptions -= sender
    case Terminated(actor) =>
      subscriptions -= actor
  }

  private def forward[T](
      from: ActorRef, to: PeerConnection, msg: T, send: MessageSend[T]) {
    try {
      val sess = session(to)
      send.sendAsProto(msg, sess)
    } catch {
      case e: IOException =>
        throw ForwardException(s"cannot forward message $msg to $to: ${e.getMessage}", e)
    }
  }

  private def dispatchToSubscriptions(msg: Any, sender: PeerConnection) {
    val notification = ReceiveMessage(msg, sender)
    for ((actor, MessageSubscription(filter)) <- subscriptions if filter(notification)) {
      actor ! notification
    }
  }

  private def session(connection: PeerConnection): PeerSession = {
    sessions.getOrElse(connection, {
      val sess = server.peerWith(new PeerInfo(connection.hostname, connection.port)).get
      sessions += connection -> sess
      sess
    })
  }
}

object ProtoRpcMessageGateway {

  private[protocol] val VoidResponse = proto.Void.newBuilder().build()
  private[protocol] val SuccessPublishResponse = proto.PublishResponse.newBuilder()
    .setResult(proto.PublishResponse.Result.SUCCESS)
    .build()
  private[protocol] val SuccessExchangeResponse = proto.ExchangeRequestResponse.newBuilder()
    .setResult(proto.ExchangeRequestResponse.Result.SUCCESS)
    .build()

  trait Component extends MessageGateway.Component {
    override def messageGatewayProps(serverInfo: PeerInfo): Props =
      Props(new ProtoRpcMessageGateway(serverInfo))
  }
}