package com.elevator
package v2

import org.specs2.concurrent.ExecutionEnv
import scalaz.zio.duration._
import scalaz.zio.{IO, Schedule}

class ElevatorSystemSpec(implicit ee: ExecutionEnv) extends TestRuntime {
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

    """

  def e1 = {
    val elevators = Vector(ElevatorState(1, Vector.empty),
      ElevatorState(4, Vector(0)),
      ElevatorState(7, Vector(4)),
      ElevatorState(2, Vector(3, 4)))
    val request1 = PickupRequest(2, 0)
    val request2 = PickupRequest(6, 2)
    val request3 = PickupRequest(3, 2)
    val request4 = PickupRequest(3, 4)
    val s1 = ElevatorSystem.search(elevators, request1)
    val s2 = ElevatorSystem.search(elevators, request2)
    val s3 = ElevatorSystem.search(elevators, request3)
    val s4 = ElevatorSystem.search(elevators, request4)

    (s1 must beSome(0)) and (s2 must beSome(2)) and (s3 must beSome(1)) and (s4 must beSome(
      3))
  }

  def e2 = {
    val elevators = Vector(ElevatorState(2, Vector(0)),
      ElevatorState(7, Vector(6)),
      ElevatorState(2, Vector(3, 4)))
    val expectedResult = Vector(ElevatorState(1, Vector(0)),
      ElevatorState(6, Vector.empty),
      ElevatorState(3, Vector(4)))

    ElevatorSystem.step(elevators) must_=== expectedResult
  }

  def e3 = {
    val elevators =
      Vector(ElevatorState(1, Vector.empty), ElevatorState(2, Vector.empty))
    val expectedResult =
      Vector(ElevatorState(1, Vector.empty), ElevatorState(2, Vector.empty))

    ElevatorSystem.step(elevators) must_=== expectedResult
  }

  def e4 = {
    val elevators = Vector(ElevatorState(1, Vector.empty),
      ElevatorState(2, Vector(0)),
      ElevatorState(7, Vector(4)),
      ElevatorState(2, Vector(3, 4)))
    for {
      system <- ElevatorSystem(elevators)
      r <- system.query
    } yield r must_=== elevators
  }
  def e5 = {
    val elevators = Vector(ElevatorState(1, Vector.empty),
      ElevatorState(9, Vector(0)),
      ElevatorState(7, Vector(4)),
      ElevatorState(2, Vector(3, 4)))
    for {
      system <- ElevatorSystem(elevators)
      sizeBefore <- system.requestCount
      _ <- system.request(PickupRequest(8, 1))
      sizeAfter <- system.requestCount
    } yield (sizeBefore must_=== 0) and (sizeAfter must_=== 1)
  }

  def e6 = {

    val elevators =
      Vector(ElevatorState(1, Vector.empty), ElevatorState(8, Vector(10, 13, 15)))
    val request = PickupRequest(40, 41)

    val finalState =
      Vector(ElevatorState(1, Vector.empty), ElevatorState(41, Vector.empty))
    (for {
      system <- ElevatorSystem(elevators)
      _ <- system.request(request)
      _ <- system.run(50.millis).fork
      _ <- system.requestCount
        .repeat(Schedule.doUntil(_ <= 0)) //the request will be consumed and we will have a suspended consumer waiting for producers (size will be negative)
      state <- system.query.repeat(Schedule.doUntil(
        _.forall(_.stops.isEmpty))) //the elevators will be all free (without stops)
    } yield state must_=== finalState).supervised
  }

  def e7 = {
    val elevators =
      Vector(ElevatorState(1, Vector.empty), ElevatorState(6, Vector(10, 13, 14)))
    val request = PickupRequest(3, 2)
    val finalState =
      Vector(ElevatorState(2, Vector.empty), ElevatorState(14, Vector.empty))
    (for {
      system <- ElevatorSystem(elevators)
      _ <- system.run(50.millis).fork
      _ <- system.request(request)
      _ <- system.requestCount
        .repeat(Schedule.doUntil(_ <= 0)) //the request will be consumed and we will have a suspended consumer waiting for producers (size will be negative)
      state <- system.query.repeat(Schedule.doUntil(
        _.forall(_.stops.isEmpty))) //the elevators will be all free
    } yield state must_=== finalState).supervised
  }

  def e8 = {
    val elevators =
      Vector(ElevatorState(1, Vector.empty), ElevatorState(6, Vector(10, 13, 14)))
    val requests =
      List(PickupRequest(7, 15), PickupRequest(2, 3), PickupRequest(15, 0))
    (for {
      system <- ElevatorSystem(elevators)
      _ <- IO.forkAll(requests.map(system.request))
      _ <- system.run(1.second).fork
      size <- system.query
        .repeat(Schedule.doUntil(_.forall(_.stops.isEmpty))) *> system.requestCount
        .repeat(Schedule.doUntil(_ <= 0))
    } yield size must be_<=(0)).supervised
  }
}
