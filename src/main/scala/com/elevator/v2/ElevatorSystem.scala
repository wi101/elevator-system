package com.elevator
package v2

import scalaz.zio._
import scalaz.zio.clock.Clock
import scalaz.zio.duration._
import scalaz.zio.stm.{STM, TQueue, TRef}

final case class PickupRequest(floor: Int, destinationFloor: Int)

final class ElevatorSystem(elevators: TRef[Vector[ElevatorState]],
                           requestQueue: TQueue[PickupRequest]) {

  /**
    * Querying the state of the elevator
    */
  def query: UIO[Vector[ElevatorState]] = elevators.get.commit

  /**
    * Move the elevators in parallel
    */
  private def moveElevators(duration: Duration): ZIO[Clock, Nothing, Unit] =
    elevators
      .update(ElevatorSystem.step)
      .commit
      .repeat(Schedule.spaced(duration))
      .unit

  /**
    * Handle the requests and add stops to the adequate elevator
    */
  private val processRequests: ZIO[Clock, Nothing, Unit] =
    STM
      .atomically(for {
        request <- requestQueue.take
        _ <- elevators.update { state =>
          ElevatorSystem.search(state, request) match {
            case None => state
            case Some(index) =>
              state.updated(
                index,
                state(index)
                  .addStops(request.floor, request.destinationFloor))
          }
        }
      } yield ())
      .repeat(Schedule.forever)
      .unit

  /**
    * Receives an update about the status of an elevator
    * runs in parallel those actions:
    * 1. elevators move step by step to their next stops with a specified `duration` time stepping
    * 2. take the closest elevator and perform the requests and add the stops to each selected elevator
    */
  def run(durationBetweenFloors: Duration) =
    moveElevators(durationBetweenFloors).fork *> processRequests

  /**
    * Receive a pick-up request
    * adds the pickup request the Elevator system
    * the pickupRequest contains the floor and the direction
    * adds a new request asynchronously (we need to run it concurrently by calling system.request.fork
    */
  def request(pickupRequest: PickupRequest): UIO[Unit] =
    requestQueue
      .offer(pickupRequest)
      .commit

  /**
    * Gets requests count
    */
  def requestCount: UIO[Int] = requestQueue.size.commit
}

object ElevatorSystem {

  val initialElevatorState: ElevatorState = ElevatorState(0, Vector.empty)

  /**
    * initialize all elevators with an initial state
    */
  def initSystem(capacity: Int): UIO[ElevatorSystem] =
    ElevatorSystem(Vector.fill(capacity)(initialElevatorState))

  /**
    * Makes an ElevatorSystem specifying elevator states
    * Elevator control system should be able to handle multiple elevators up to 16.
    */
  def apply(elevators: Vector[ElevatorState]): UIO[ElevatorSystem] = {
    (for {
      requests <- TQueue.make[PickupRequest](elevators.size)
      state <- TRef.make(elevators)
    } yield new ElevatorSystem(state, requests)).commit
  }

  /**
    * looks for the closest elevator to the request
    */
  private[elevator] final def search(elevators: Vector[ElevatorState],
                                     request: PickupRequest): Option[Int] =
    elevators.zipWithIndex
      .filter(_._1.isOnWay(request.floor, request.destinationFloor))
      .sortBy(_._1.distanceFrom(request.floor))
      .collectFirst { case (_, index) => index }

  /**
    * Moves elevators Up or Down depending on their stops
    */
  private[elevator] final def step(
      elevators: Vector[ElevatorState]): Vector[ElevatorState] =
    elevators.map(_.step)

}
