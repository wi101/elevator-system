package com.elevator.v2

import scalaz.zio._
import scalaz.zio.clock.Clock
import scalaz.zio.duration._
import scalaz.zio.stm.{STM, TRef}

final case class ElevatorState(floor: Int, stops: Set[Int]) { current =>
  def step: ElevatorState = {
    if (isStationary)
      current
    else if (isGoingUp)
      ElevatorState(floor = floor + 1, stops = stops - (floor + 1))
    else
      ElevatorState(floor = floor - 1, stops = stops - (floor - 1))
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
  def addStops(newStops: Set[Int]): ElevatorState =
    copy(stops = stops ++ newStops.filterNot(_ == floor))
}

final case class PickupRequest(floor: Int, destinationFloor: Int)

final class ElevatorSystem(elevators: TRef[Vector[ElevatorState]],
                           requestsTRef: TRef[Vector[PickupRequest]]) {

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
    STM.atomically(for {
        requests <- requestsTRef.get
        _ <- STM.check(requests.nonEmpty)
        elevatorStates <- elevators.get
        changeStates = requests.flatMap { request =>
          val maybeStateIndex = ElevatorSystem.search(elevatorStates, request)
          maybeStateIndex match {
            case None => None
            case Some(index) =>
              Some((request, index, Set(request.floor, request.destinationFloor)))
          }
        }
        _ <- changeStates.foldLeft(elevators.get) {
          case (_, (request, index, newStops)) =>
            elevators.update(
              elevator =>
                elevator.updated(index,
                  elevator(index).addStops(newStops))) <* requestsTRef.update(_.filterNot(_ == request))
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
  def request(pickupRequest: PickupRequest): UIO[Unit] = {
    requestsTRef
      .update(_ :+ pickupRequest)
      .commit
      .unit
  }

  /**
    * Gets requests count
    */
  def requestCount: UIO[Int] = requestsTRef.get.commit.map(_.size)
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
    (for {
      requests <- TRef.make(Vector.empty[PickupRequest])
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
        .headOption
        .map(_._2)

  /**
    * Moves elevators Up or Down depending on their stops
    */
  private[elevator] final def step(
      elevators: Vector[ElevatorState]): Vector[ElevatorState] =
    elevators.map(_.step)

}
