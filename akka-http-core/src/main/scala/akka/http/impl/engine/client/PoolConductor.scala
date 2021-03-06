/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.client

import language.existentials
import scala.annotation.tailrec
import scala.collection.immutable
import akka.event.LoggingAdapter
import akka.stream.scaladsl._
import akka.stream._
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.model.HttpMethod
import akka.macros.LogHelper
import akka.stream.stage.GraphStage
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.InHandler

private object PoolConductor {
  import PoolFlow.RequestContext
  import PoolSlot.{ RawSlotEvent, SlotEvent }

  case class Ports(
    requestIn:   Inlet[RequestContext],
    slotEventIn: Inlet[RawSlotEvent],
    slotOuts:    immutable.Seq[Outlet[SlotCommand]]) extends Shape {

    override val inlets = requestIn :: slotEventIn :: Nil
    override def outlets = slotOuts

    override def deepCopy(): Shape =
      Ports(
        requestIn.carbonCopy(),
        slotEventIn.carbonCopy(),
        slotOuts.map(_.carbonCopy()))

    override def copyFromPorts(inlets: immutable.Seq[Inlet[_]], outlets: immutable.Seq[Outlet[_]]): Shape =
      Ports(
        inlets.head.asInstanceOf[Inlet[RequestContext]],
        inlets.last.asInstanceOf[Inlet[RawSlotEvent]],
        outlets.asInstanceOf[immutable.Seq[Outlet[SlotCommand]]])
  }

  final case class PoolSlotsSetting(minSlots: Int, maxSlots: Int) {
    require(minSlots <= maxSlots, "min-connections must be <= max-connections")
  }

  /*
    Stream Setup
    ============
                                                                                                  Slot-
    Request-   +-----------+     +-----------+    Switch-    +-------------+     +-----------+    Command
    Context    |   retry   |     |   slot-   |    Command    |   doubler   |     |   route   +-------------->
    +--------->|   Merge   +---->| Selector  +-------------->| (MapConcat) +---->|  (Flexi   +-------------->
               |           |     |           |               |             |     |   Route)  +-------------->
               +----+------+     +-----+-----+               +-------------+     +-----------+       to slots
                    ^                  ^
                    |                  | SlotEvent
                    |             +----+----+
                    |             | flatten | mapAsync
                    |             +----+----+
                    |                  | RawSlotEvent
                    | Request-         |
                    | Context     +---------+
                    +-------------+  retry  |<-------- RawSlotEvent (from slotEventMerge)
                                  |  Split  |
                                  +---------+

  */
  def apply(slotSettings: PoolSlotsSetting, pipeliningLimit: Int, log: LoggingAdapter): Graph[Ports, Any] =
    GraphDSL.create() { implicit b ⇒
      import GraphDSL.Implicits._

      val retryMerge = b.add(MergePreferred[RequestContext](1, eagerComplete = true))
      val slotSelector = b.add(new SlotSelector(slotSettings, pipeliningLimit, log))
      val route = b.add(new Route(slotSettings.maxSlots))
      val retrySplit = b.add(Broadcast[RawSlotEvent](2))
      val flatten = Flow[RawSlotEvent].mapAsyncUnordered(slotSettings.maxSlots) {
        case x: SlotEvent.Disconnected                ⇒ FastFuture.successful(x)
        case SlotEvent.RequestCompletedFuture(future) ⇒ future
        case x: SlotEvent.ConnectedEagerly            ⇒ FastFuture.successful(x)
        case x                                        ⇒ throw new IllegalStateException("Unexpected " + x)
      }

      retryMerge.out ~> slotSelector.in0
      slotSelector.out ~> route.in
      retrySplit.out(0).filter(!_.isInstanceOf[SlotEvent.RetryRequest]) ~> flatten ~> slotSelector.in1
      retrySplit.out(1).collect { case SlotEvent.RetryRequest(r) ⇒ r } ~> retryMerge.preferred

      Ports(retryMerge.in(0), retrySplit.in, route.outArray.toList)
    }

  sealed trait SlotCommand
  final case class DispatchCommand(rc: RequestContext) extends SlotCommand
  final case object ConnectEagerlyCommand extends SlotCommand

  final case class SwitchSlotCommand(cmd: SlotCommand, slotIx: Int)

  // the SlotSelector keeps the state of all slots as instances of this ADT
  private sealed trait SlotState

  // the connection of the respective slot is not connected
  private case object Unconnected extends SlotState

  // the connection of the respective slot is connected with no requests currently in flight
  private case object Idle extends SlotState

  // the connection of the respective slot has a number of requests in flight and all of them
  // are idempotent which allows more requests to be pipelined onto the connection if required
  private final case class Loaded(openIdempotentRequests: Int) extends SlotState { require(openIdempotentRequests > 0) }

  // the connection of the respective slot has a number of requests in flight and the
  // last one of these is not idempotent which blocks the connection for more pipelined requests
  private case class Busy(openRequests: Int) extends SlotState { require(openRequests > 0) }
  private object Busy extends Busy(1)

  private class SlotSelector(slotSettings: PoolSlotsSetting, pipeliningLimit: Int, val log: LoggingAdapter)
    extends GraphStage[FanInShape2[RequestContext, SlotEvent, SwitchSlotCommand]] with LogHelper {

    private val requestContextIn = Inlet[RequestContext]("SlotSelector.requestContextIn")
    private val slotEventIn = Inlet[SlotEvent]("SlotSelector.slotEventIn")
    private val slotCommandOut = Outlet[SwitchSlotCommand]("SlotSelector.slotCommandOut")

    override def initialAttributes = Attributes.name("SlotSelector")

    override val shape = new FanInShape2(requestContextIn, slotEventIn, slotCommandOut)

    override def createLogic(effectiveAttributes: Attributes) = new GraphStageLogic(shape) {
      val slotStates = Array.fill[SlotState](slotSettings.maxSlots)(Unconnected)
      var nextSlot = 0

      def updateSlotState(idx: Int, f: SlotState ⇒ SlotState): Unit = {
        val oldState = slotStates(idx)
        val newState = f(oldState)

        debug(s"[$idx] $oldState -> $newState")

        slotStates(idx) = newState
      }

      setHandler(requestContextIn, new InHandler {
        override def onPush(): Unit = {
          val ctx = grab(requestContextIn)
          val slot = nextSlot
          updateSlotState(slot, slotStateAfterDispatch(_, ctx.request.method))
          nextSlot = bestSlot()
          emit(slotCommandOut, SwitchSlotCommand(DispatchCommand(ctx), slot), tryPullCtx)
        }
      })

      setHandler(slotEventIn, new InHandler {
        override def onPush(): Unit = {
          grab(slotEventIn) match {
            case SlotEvent.RequestCompleted(slotIx) ⇒
              updateSlotState(slotIx, slotStateAfterRequestCompleted)
            case SlotEvent.Disconnected(slotIx, failed) ⇒
              updateSlotState(slotIx, slotStateAfterDisconnect(_, failed))
              reconnectIfNeeded()
            case SlotEvent.ConnectedEagerly(slotIx) ⇒
            // do nothing ...
          }
          pull(slotEventIn)
          val wasBlocked = nextSlot == -1
          nextSlot = bestSlot()
          val nowUnblocked = nextSlot != -1
          if (wasBlocked && nowUnblocked) pull(requestContextIn) // get next request context
        }
      })

      setHandler(slotCommandOut, eagerTerminateOutput)

      val tryPullCtx = () ⇒ if (nextSlot != -1 && !hasBeenPulled(requestContextIn)) pull(requestContextIn)

      override def preStart(): Unit = {
        pull(requestContextIn)
        pull(slotEventIn)

        // eagerly start at least slotSettings.minSlots connections
        (0 until slotSettings.minSlots).foreach { connect }
      }

      def connect(slotIx: Int): Unit = {
        emit(slotCommandOut, SwitchSlotCommand(ConnectEagerlyCommand, slotIx))
        updateSlotState(slotIx, _ ⇒ Idle)
      }

      private def reconnectIfNeeded(): Unit =
        if (slotStates.count(_ != Unconnected) < slotSettings.minSlots) {
          connect(slotStates.indexWhere(_ == Unconnected))
        }

      def slotStateAfterDispatch(slotState: SlotState, method: HttpMethod): SlotState =
        slotState match {
          case Unconnected | Idle ⇒ if (method.isIdempotent) Loaded(1) else Busy(1)
          case Loaded(n)          ⇒ if (method.isIdempotent) Loaded(n + 1) else Busy(n + 1)
          case Busy(_)            ⇒ throw new IllegalStateException("Request scheduled onto busy connection?")
        }

      def slotStateAfterRequestCompleted(slotState: SlotState): SlotState =
        slotState match {
          case Loaded(1) ⇒ Idle
          case Loaded(n) ⇒ Loaded(n - 1)
          case Busy(1)   ⇒ Idle
          case Busy(n)   ⇒ Busy(n - 1)
          case _         ⇒ throw new IllegalStateException(s"RequestCompleted on $slotState connection?")
        }

      def slotStateAfterDisconnect(slotState: SlotState, failed: Int): SlotState =
        slotState match {
          case Idle if failed == 0      ⇒ Unconnected
          case Loaded(n) if n > failed  ⇒ Loaded(n - failed)
          case Loaded(n) if n == failed ⇒ Unconnected
          case Busy(n) if n > failed    ⇒ Busy(n - failed)
          case Busy(n) if n == failed   ⇒ Unconnected
          case _                        ⇒ throw new IllegalStateException(s"Disconnect(_, $failed) on $slotState connection?")
        }

      /**
       * Implements the following Connection Slot selection strategy
       *  - Select the first idle connection in the pool, if there is one.
       *  - If none is idle select the first unconnected connection, if there is one.
       *  - If all are loaded select the connection with the least open requests (< pipeliningLimit)
       *    that only has requests with idempotent methods scheduled to it, if there is one.
       *  - Otherwise return -1 (which applies back-pressure to the request source)
       *
       *  See http://tools.ietf.org/html/rfc7230#section-6.3.2 for more info on HTTP pipelining.
       */
      @tailrec def bestSlot(ix: Int = 0, bestIx: Int = -1, bestState: SlotState = Busy): Int =
        if (ix < slotStates.length) {
          val pl = pipeliningLimit
          slotStates(ix) → bestState match {
            case (Idle, _)                           ⇒ ix
            case (Unconnected, Loaded(_) | Busy)     ⇒ bestSlot(ix + 1, ix, Unconnected)
            case (x @ Loaded(a), Loaded(b)) if a < b ⇒ bestSlot(ix + 1, ix, x)
            case (x @ Loaded(a), Busy) if a < pl     ⇒ bestSlot(ix + 1, ix, x)
            case _                                   ⇒ bestSlot(ix + 1, bestIx, bestState)
          }
        } else bestIx
    }
  }

  private class Route(slotCount: Int) extends GraphStage[UniformFanOutShape[SwitchSlotCommand, SlotCommand]] {

    override def initialAttributes = Attributes.name("PoolConductor.Route")

    override val shape = new UniformFanOutShape[SwitchSlotCommand, SlotCommand](slotCount)

    override def createLogic(effectiveAttributes: Attributes) = new GraphStageLogic(shape) {
      shape.outArray foreach { setHandler(_, ignoreTerminateOutput) }

      val in = shape.in
      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val switchCommand = grab(in)
          emit(shape.outArray(switchCommand.slotIx), switchCommand.cmd, pullIn)
        }
      })
      val pullIn = () ⇒ pull(in)

      override def preStart(): Unit = pullIn()
    }
  }
}
