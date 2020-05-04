package com.elevator.v1

import com.elevator.ElevatorState
import zio.duration._
import zio.test.Assertion._
import zio.test.{DefaultRunnableSpec, _}
import zio.{IO, Schedule}
import zio.test.TestAspect.sequential

object ElevatorSystemSpec extends DefaultRunnableSpec {

  override def spec =
    suite("ElevatorSystemSpec")(
      test(" `search` must return the closest elevator to the requested floor ") {
        val elevators = Vector(
          ElevatorState(1, Set.empty),
          ElevatorState(2, Set(0)),
          ElevatorState(7, Set(4)),
          ElevatorState(2, Set(3, 4))
        )
        val request1 = PickupRequest(2, 0)
        val request2 = PickupRequest(1, 0)
        val request3 = PickupRequest(2, 4)
        val request4 = PickupRequest(5, 4)
        val s1 = ElevatorSystem.search(elevators, request1)
        val s2 = ElevatorSystem.search(elevators, request2)
        val s3 = ElevatorSystem.search(elevators, request3)
        val s4 = ElevatorSystem.search(elevators, request4)

        assert(s1)(isSome(equalTo(1))) && assert(s2)(isSome(equalTo(0))) &&
        assert(s3)(isSome(equalTo(3))) && assert(s4)(isSome(equalTo(2)))
      },
      test("`step` must move the elevators to the next stop") {
        val elevators = Vector(
          ElevatorState(2, Set(0)),
          ElevatorState(7, Set(6)),
          ElevatorState(2, Set(3, 4))
        )
        val expectedResult = Vector(
          ElevatorState(1, Set(0)),
          ElevatorState(6, Set.empty),
          ElevatorState(3, Set(4))
        )

        assert(ElevatorSystem.step(elevators))(equalTo(expectedResult))
      },
      test("`step` must stay at the same place if there is no stops") {
        val elevators =
          Vector(ElevatorState(1, Set.empty), ElevatorState(2, Set.empty))

        assert(ElevatorSystem.step(elevators))(equalTo(elevators))
      },
      testM(" `query` query the state of the elevator ") {
        val elevators = Vector(
          ElevatorState(1, Set.empty),
          ElevatorState(2, Set(0)),
          ElevatorState(7, Set(4)),
          ElevatorState(2, Set(3, 4))
        )
        assertM(ElevatorSystem(elevators).flatMap(_.query))(equalTo(elevators))
      },
      testM("`request` add a pick-up request in Elevator state") {
        val elevators = Vector(
          ElevatorState(1, Set.empty),
          ElevatorState(2, Set(0)),
          ElevatorState(7, Set(4)),
          ElevatorState(2, Set(3, 4))
        )
        val request = PickupRequest(2, 1)
        for {
          system <- ElevatorSystem(elevators)
          sizeBefore <- system.requestCount
          _ <- system.sendRequest(request)
          sizeAfter <- system.requestCount
        } yield assert(sizeBefore)(equalTo(0)) && assert(sizeAfter)(equalTo(1))
      },
      testM(
        "`run` must run all requests and move the elevators to their next stops "
      ) {
        val elevators =
          Vector(ElevatorState(1, Set.empty), ElevatorState(2, Set(10, 13, 15)))
        val request = PickupRequest(1, 0)
        val finalState =
          Vector(ElevatorState(0, Set.empty), ElevatorState(15, Set.empty))
        for {
          system <- ElevatorSystem(elevators)
          _ <- system.sendRequest(request).fork
          _ <- system.run(1.millis).fork
          _ <- system.requestCount
            .repeat(Schedule.doUntil(_ <= 0)) //the request will be consumed and we will have a suspended consumer waiting for producers (size will be negative)
          state <- system.query.repeat(
            Schedule.doUntil(_.forall(_.stops.isEmpty))
          ) //the elevators will be all free (without stops)
        } yield assert(state)(equalTo(finalState))
      }.provideCustomLayer(zio.clock.Clock.live),
      testM("`run` must run all pick-up requests ") {
        val elevators =
          Vector(ElevatorState(1, Set.empty), ElevatorState(6, Set(10, 13, 14)))
        val request = PickupRequest(7, 15)
        val finalState =
          Vector(ElevatorState(1, Set.empty), ElevatorState(15, Set.empty))
        for {
          system <- ElevatorSystem(elevators)
          _ <- system.run(100.millis).fork
          _ <- system.sendRequest(request)
          _ <- system.requestCount
            .repeat(Schedule.doUntil(_ <= 0)) //the request will be consumed and we will have a suspended consumer waiting for producers (size will be negative)
          state <- system.query.repeat(
            Schedule.doUntil(_.forall(_.stops.isEmpty))
          ) //the elevators will be all free
        } yield assert(state)(equalTo(finalState))
      }.provideCustomLayer(zio.clock.Clock.live),
      testM(
        "`run` must run all pick-up requests even if the requests are more than the elevator number"
      ) {
        val elevators =
          Vector(ElevatorState(1, Set.empty), ElevatorState(6, Set(10, 13, 14)))
        val requests =
          List(PickupRequest(7, 15), PickupRequest(2, 3), PickupRequest(15, 0))
        for {
          system <- ElevatorSystem(elevators)
          _ <- IO.forkAll(requests.map(system.sendRequest))
          _ <- system.run(100.millis).fork
          size <- system.query
            .repeat(Schedule.doUntil(_.forall(_.stops.isEmpty))) *> system.requestCount
            .repeat(Schedule.doUntil(_ <= 0))
        } yield assert(size)(isLessThanEqualTo(0))
      }.provideCustomLayer(zio.clock.Clock.live),
      testM("the elevator should go up to pickup then go down to delivery") {
        val elevators = Vector(ElevatorState(1, Set.empty))
        val request = PickupRequest(15, 0)
        for {
          system <- ElevatorSystem(elevators)
          _ <- system.sendRequest(request)
          _ <- system.run(100.millis).fork
          state <- system.query
            .repeat(Schedule.doUntil(_.forall(_.stops.isEmpty)))
            .delay(100.millis) <* system.requestCount
            .repeat(Schedule.doUntil(_ <= 0))
        } yield assert(state)(equalTo(Vector(ElevatorState(0, Set.empty))))
      }.provideCustomLayer(zio.clock.Clock.live),
      testM(
        "the request couldn't be performed will be unfortunaltly ignored (then we need the v2 using STM that will sove that problem "
      ) {
        val elevators = Vector(ElevatorState(1, Set(10)))
        val request = PickupRequest(12, 0)
        for {
          system <- ElevatorSystem(elevators)
          _ <- system.sendRequest(request)
          _ <- system.run(10.millis).fork
          state <- (system.query
            .repeat(Schedule.doUntil(_.forall(_.stops.isEmpty))) <* system.requestCount
            .repeat(Schedule.doUntil(_ <= 0))).delay(100.millis)
        } yield assert(state)(equalTo(Vector(ElevatorState(10, Set.empty))))
      }.provideCustomLayer(zio.clock.Clock.live)
    ) @@ sequential

}
