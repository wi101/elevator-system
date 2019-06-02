package com

package object elevator {
  final case class ElevatorState(floor: Int, stops: Set[Int]) { current =>

    def step: ElevatorState = {
      if (isStationary) copy(stops = Set.empty)
      else if (isGoingUp || isGoingUpButDown)
        ElevatorState(floor = floor + 1, stops = stops - (floor + 1))
      else if (isGoingDown || isGoingDownButUp)
        ElevatorState(floor = floor - 1, stops = stops - (floor - 1))
      else current
    }

    def isFree: Boolean = stops.isEmpty
    def isGoingUp: Boolean = !isFree && stops.forall(floor <= _)
    def isGoingDown: Boolean = !isFree && stops.forall(floor >= _)
    def isStationary: Boolean = isGoingUp && isGoingDown
    // if the selected elevator was free and it will go up to pickup request with destination that goes down again
    def isGoingUpButDown: Boolean =
      !isGoingUp && (stops.toList match {
        case pickup :: delivery :: Nil
            if floor < pickup && pickup >= delivery =>
          true
        case _ => false
      })

    // if the selected elevator was free and it will go down to pickup request with destination that goes up again
    def isGoingDownButUp: Boolean =
      !isGoingDown && (stops.toList match {
        case pickup :: delivery :: Nil
            if floor > pickup && pickup <= delivery =>
          true
        case _ => false
      })

    /**
      * Checks if the requested floor is on way of the elevator and if it is on the same destination
      */
    def isOnWay(from: Int, to: Int): Boolean =
      (from >= floor && to > from && isGoingUp) || (from <= floor && to < from && isGoingDown) || isFree

    def distanceFrom(f: Int): Int = (floor - f).abs

    /**
      * adds a next stop if the current elevator is in other floor otherwise keep the same stops
      */
    def addStop(stop: Int): ElevatorState =
      if (stop != floor) copy(stops = stops + stop) else current
  }
}
