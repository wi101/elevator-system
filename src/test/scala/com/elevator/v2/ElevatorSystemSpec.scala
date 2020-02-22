package com.elevator
package v2

import zio.duration._
import zio.{IO, Schedule}

class ElevatorSystemSpec extends TestRuntime {
  def is = "ElevatorSystemSpec".title ^ s2"""
     make an Elevator System and check if:
    `search` must return the closed elevator to the requested floor                             $e1
    `step` must move the elevators to the next stop and                                         $e2
    `step` must stay at the same place if there is no stops                                     $e3
    `query` query the state of the elevator                                                     $e4
    `request` add a pick-up request in Elevator state                                           $e5
    `run` must run all requests and move the elevators to their next stops                      $e6
    `run` must run all pick-up requests                                                         $e7
    `run` must run all pick-up requests even if the requests are more than the elevator number  $e8
     the elevator should go up to pickup then go down to delivery $e9
     the request couldn't be performed must retry until finding an available elevator           $e10
    """

  def e1 = {
    val elevators = Vector(ElevatorState(1, Set.empty),
      ElevatorState(2, Set(0)),
      ElevatorState(7, Set(4)),
      ElevatorState(2, Set(3, 4)))
    val request1 = PickupRequest(2, 0)
    val request2 = PickupRequest(1, 0)
    val request3 = PickupRequest(2, 4)
    val request4 = PickupRequest(5, 4)
    val s1 = ElevatorSystem.search(elevators, request1)
    val s2 = ElevatorSystem.search(elevators, request2)
    val s3 = ElevatorSystem.search(elevators, request3)
    val s4 = ElevatorSystem.search(elevators, request4)

    (s1 must beSome(1)) and (s2 must beSome(0)) and (s3 must beSome(3)) and (s4 must beSome(
      2))
  }

  def e2 = {
    val elevators = Vector(ElevatorState(2, Set(0)),
      ElevatorState(7, Set(6)),
      ElevatorState(2, Set(3, 4)))
    val expectedResult = Vector(ElevatorState(1, Set(0)),
      ElevatorState(6, Set.empty),
      ElevatorState(3, Set(4)))

    ElevatorSystem.step(elevators) must_=== expectedResult
  }

  def e3 = {
    val elevators =
      Vector(ElevatorState(1, Set.empty), ElevatorState(2, Set.empty))
    val expectedResult =
      Vector(ElevatorState(1, Set.empty), ElevatorState(2, Set.empty))

    ElevatorSystem.step(elevators) must_=== expectedResult
  }

  def e4 = {
    val elevators = Vector(ElevatorState(1, Set.empty),
      ElevatorState(2, Set(0)),
      ElevatorState(7, Set(4)),
      ElevatorState(2, Set(3, 4)))
    for {
      system <- ElevatorSystem(elevators)
      r <- system.query
    } yield r must_=== elevators
  }
  def e5 = {
    val elevators = Vector(ElevatorState(1, Set.empty),
      ElevatorState(2, Set(0)),
      ElevatorState(7, Set(4)),
      ElevatorState(2, Set(3, 4)))
    val request = PickupRequest(2, 1)
    for {
      system <- ElevatorSystem(elevators)
      sizeBefore <- system.requestCount
      _ <- system.request(request)
      sizeAfter <- system.requestCount
    } yield (sizeBefore must_=== 0) and (sizeAfter must_=== 1)
  }

  def e6 = {

    val elevators =
      Vector(ElevatorState(1, Set.empty), ElevatorState(2, Set(10, 13, 15)))
    val request = PickupRequest(1, 0)
    val finalState =
      Vector(ElevatorState(0, Set.empty), ElevatorState(15, Set.empty))
    (for {
      system <- ElevatorSystem(elevators)
      _ <- system.request(request).fork
      _ <- system.run(1.millis).fork
      _ <- system.requestCount
        .repeat(Schedule.doUntil(_ <= 0)).delay(100.millis) //the request will be consumed and we will have a suspended consumer waiting for producers (size will be negative)
      state <- system.query.repeat(Schedule.doUntil(
        _.forall(_.stops.isEmpty))) //the elevators will be all free (without stops)
    } yield state must_=== finalState)
  }

  def e7 = {
    val elevators =
      Vector(ElevatorState(1, Set.empty), ElevatorState(6, Set(10, 13, 14)))
    val request = PickupRequest(7, 15)
    val finalState =
      Vector(ElevatorState(1, Set.empty), ElevatorState(15, Set.empty))
    (for {
      system <- ElevatorSystem(elevators)
      _ <- system.run(100.millis).fork
      _ <- system.request(request)
      _ <- system.requestCount
        .repeat(Schedule.doUntil(_ <= 0)) //the request will be consumed and we will have a suspended consumer waiting for producers (size will be negative)
      state <- system.query.repeat(Schedule.doUntil(
        _.forall(_.stops.isEmpty))) //the elevators will be all free
    } yield state must_=== finalState)
  }

  def e8 = {
    val elevators =
      Vector(ElevatorState(1, Set.empty), ElevatorState(6, Set(10, 13, 14)))
    val requests =
      List(PickupRequest(7, 15), PickupRequest(2, 3), PickupRequest(15, 0))
    (for {
      system <- ElevatorSystem(elevators)
      f <- IO.forkAll(requests.map(system.request))
      _ <- system.run(100.millis).fork
      _ <- f.join
      size <- system.query
        .repeat(Schedule.doUntil(_.forall(_.stops.isEmpty))) *> system.requestCount
        .repeat(Schedule.doUntil(_ <= 0))
    } yield size must be_<=(0))
  }

  def e9 = {
    val elevators = Vector(ElevatorState(1, Set.empty))
    val request = PickupRequest(15, 0)
    (for {
      system <- ElevatorSystem(elevators)
      _ <- system.request(request)
      _ <- system.run(10.millis).fork
      state <- system.query
        .repeat(Schedule.doUntil(_.forall(_.stops.isEmpty))).delay(100.millis) <* system.requestCount
        .repeat(Schedule.doUntil(_ <= 0))
    } yield state must_=== Vector(ElevatorState(0, Set.empty)))
  }

  def e10 = {
    val elevators = Vector(ElevatorState(1, Set(10)), ElevatorState(20, Set(30)))
    (for {
      system <- ElevatorSystem(elevators)
      _ <- system.request(PickupRequest(12, 9) )
      _ <- system.run(10.millis).fork
      _ <- system.request(PickupRequest(25, 32)).delay(50.millis)
      state <- (system.query
        .repeat(Schedule.doUntil(_.forall(_.stops.isEmpty))) <* system.requestCount
        .repeat(Schedule.doUntil(_ <= 0))).delay(100.millis)
    } yield state must_=== Vector(ElevatorState(9, Set.empty), ElevatorState(32, Set.empty)))
  }
}