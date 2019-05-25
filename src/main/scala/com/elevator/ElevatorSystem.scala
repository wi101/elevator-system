package com.elevator

import scalaz.zio.duration._
import scalaz.zio._
import scalaz.zio.clock.Clock

final case class ElevatorState(floor: Int, stops: Set[Int]) { current =>
  def step: Option[ElevatorState] = {
    if (isStationary)
      None
    else if (isGoingUp)
      Some(ElevatorState(floor = floor + 1, stops = stops - (floor + 1)))
    else
      Some(ElevatorState(floor = floor - 1, stops = stops - (floor - 1)))
  }

  def isFree: Boolean = stops.isEmpty
  def isGoingUp: Boolean = stops.forall(floor <= _)
  def isGoingDown: Boolean = stops.forall(floor >= _)
  def isStationary: Boolean = isGoingUp && isGoingDown

  /**
    * Checks if the requested floor is on way of the elevator and if it is on the same destination
    */
  def isOnWay(from: Int, to: Int): Boolean =
    (from >= floor && to >= floor && isGoingUp) || (from <= floor && to <= floor && isGoingDown) || isFree

  def distanceFrom(f: Int): Int = (floor - f).abs

  /**
    * adds a next stop if the current elevator is in other floor otherwise keep the same stops
    */
  def addStop(stop: Int): ElevatorState =
    if (stop != floor) copy(stops = stops + stop) else current
}
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
    moveElevator.fork *> processRequests

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
      .headOption
      .map(_._2)

  /**
    * Moves elevators Up or Down depending on their stops
    */
  final def step(elevators: Vector[ElevatorState]): Vector[ElevatorState] =
    elevators.map(e => e.step.getOrElse(e))

}
