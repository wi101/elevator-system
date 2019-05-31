package com

package object elevator {
  final case class ElevatorState(floor: Int, stops: Vector[Int]) { current =>

    def removeStop(floor: Int) =
      stops.headOption
        .find(_ == floor)
        .fold(stops)(s => stops.filterNot(_ == floor))

    def step: ElevatorState = {
      if (isFree)
        current
      else if (isGoingUp)
        ElevatorState(floor = floor + 1, stops = removeStop(floor + 1))
      else if (isGoingDown)
        ElevatorState(floor = floor - 1, stops = removeStop(floor - 1))
      else copy(stops = Vector.empty)
    }

    def isFree: Boolean = stops.isEmpty
    def isGoingUp: Boolean = !isFree && stops.forall(floor < _)
    def isGoingDown: Boolean = !isFree && stops.forall(floor > _)
    def isStationary: Boolean = !isFree && stops.forall(floor == _)

    /**
      * Checks if the requested floor is on way of the elevator and if it is on the same destination
      */
    def isOnWay(from: Int, to: Int): Boolean =
      (from > floor && to > from && isGoingUp) || (from < floor && to < from && isGoingDown) || isFree

    def distanceFrom(f: Int): Int = (floor - f).abs

    /**
      * adds a next stop if the current elevator is in other floor otherwise keep the same stops
      */
    def addStops(pickup: Int, destination: Int): ElevatorState =
      if (pickup != floor) copy(stops = stops :+ pickup :+ destination) else current
  }
}
