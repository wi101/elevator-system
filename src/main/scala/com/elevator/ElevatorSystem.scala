package com.elevator

import zio.duration._
import zio._
import zio.clock.Clock

final case class PickupRequest(floor: Int, destinationFloor: Int)

final class ElevatorSystem(elevators: Ref[Vector[ElevatorState]],
                           requests: Queue[PickupRequest]) {

  /**
    * Querying the state of the elevator
    */
  def query: UIO[Vector[ElevatorState]] = elevators.get

  /**
    * Receives an update about the status of an elevator
    * runs in parallel those actions:
    * 1. elevators move step by step to their next stops with a specified `duration` time stepping
    * 2. take the closest elevator for every request
    * 3. add the next stops to the selected elevator and update the elevator state
    * 4. consume (listen) every incoming request
    */
  def run(duration: Duration): ZIO[Clock, Nothing, Unit] = {
    val moveElevator = elevators
      .update(ElevatorSystem.step)
      .repeat(Schedule.spaced(duration))
      .unit
    val processRequests = (for {
      request <- requests.take
      _ <- elevators.update { state =>
        ElevatorSystem.search(state, request) match {
          case None => state
          case Some(index) =>
            state.updated(index,
                          state(index)
                            .addStop(request.floor)
                            .addStop(request.destinationFloor))
        }
      }
    } yield ()).repeat(Schedule.forever).unit
    moveElevator.zipPar(processRequests).unit

  }

  /**
    * Receive a pick-up request
    * adds the pickup request the Elevator system
    * the pickupRequest contains the floor and the direction
    * adds a new request asynchronously (we need to run it concurrently by calling system.request.fork
    */
  def request(pickupRequest: PickupRequest): UIO[Boolean] =
    requests.offer(pickupRequest)

  /**
    * Gets requests count
    */
  def requestCount: UIO[Int] = requests.size
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
      queue <- Queue.bounded[PickupRequest](elevators.length)
      state <- Ref.make(elevators)
    } yield new ElevatorSystem(state, queue)
  }

  /**
    * looks for the closest elevator to the request
    */
  final def search(elevators: Vector[ElevatorState],
                   request: PickupRequest): Option[Int] =
    elevators.zipWithIndex
      .filter(_._1.isOnWay(request.floor, request.destinationFloor))
      .sortBy(_._1.distanceFrom(request.floor))
      .collectFirst { case (_, index) => index }

  /**
    * Moves elevators Up or Down depending on their stops
    */
  final def step(elevators: Vector[ElevatorState]): Vector[ElevatorState] =
    elevators.map(_.step)

}
