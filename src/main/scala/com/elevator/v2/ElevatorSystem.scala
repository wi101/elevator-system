package com.elevator
package v2

import zio._
import zio.clock.Clock
import zio.duration._
import zio.stm.{STM, TRef}

final case class PickupRequest(floor: Int, destinationFloor: Int)

final class ElevatorSystem(elevators: TRef[Vector[ElevatorState]],
                           requestQueue: Queue[PickupRequest]) {

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
    (for {
        request <- requestQueue.take
        _ <- elevators
          .modify { state =>
            ElevatorSystem.search(state, request) match {
              case None => STM.retry -> state
              case Some(index) =>
                val newState = state.updated(index,
                                      state(index)
                                        .addStop(request.floor)
                                        .addStop(request.destinationFloor))
                STM.succeed(()) -> newState
            }
          }
          .flatMap(identity).commit
      } yield ()).forever.unit

  /**
    * Receives an update about the status of an elevator
    * runs in parallel those actions:
    * 1. elevators move step by step to their next stops with a specified `duration` time stepping
    * 2. take the closest elevator and perform the requests and add the stops to each selected elevator
    */
  def run(durationBetweenFloors: Duration) =
    moveElevators(durationBetweenFloors).zipPar(processRequests)

  /**
    * Receive a pick-up request
    * adds the pickup request the Elevator system
    * the pickupRequest contains the floor and the direction
    * adds a new request asynchronously (we need to run it concurrently by calling system.request.fork
    */
  def request(pickupRequest: PickupRequest): UIO[Unit] =
    requestQueue
      .offer(pickupRequest).unit

  /**
    * Gets requests count
    */
  def requestCount: UIO[Int] = requestQueue.size
}

object ElevatorSystem {

  val initialElevatorState: ElevatorState = ElevatorState(0, Set.empty)

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
    for {
      requests <- Queue.bounded[PickupRequest](elevators.size)
      state <- TRef.make(elevators).commit
    } yield new ElevatorSystem(state, requests)
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
