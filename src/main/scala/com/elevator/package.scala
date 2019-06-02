package com

package object elevator {final case class ElevatorState(floor: Int, stops: Set[Int]) { current =>
  def step: ElevatorState = {
    if (isFree)
      current
    else if (isGoingUp)
      ElevatorState(floor = floor + 1, stops = stops - (floor + 1))
    else if (isGoingDown)
      ElevatorState(floor = floor - 1, stops = stops - (floor - 1))
    else copy(stops = Set.empty)
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
}
