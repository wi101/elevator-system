package com.elevator

import java.util.{Timer, TimerTask}
import org.specs2.Specification
import org.specs2.execute.AsResult
import org.specs2.specification.core.{AsExecution, Execution}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import zio.{DefaultRuntime, FiberFailure, ZIO}

abstract class TestRuntime extends Specification with DefaultRuntime {
  val DefaultTimeout: Duration = 60.seconds
  val timer = new Timer()
  implicit val ec: ExecutionContext = ExecutionContext.Implicits.global

  implicit def zioAsExecution[A: AsResult, R >: zio.ZEnv, E]
    : AsExecution[ZIO[R, E, A]] =
    io =>
      Execution.withEnvAsync(_ => runToFutureWithTimeout(io, DefaultTimeout))

  protected def runToFutureWithTimeout[E, R >: zio.ZEnv, A: AsResult](
      io: ZIO[R, E, A],
      timeout: Duration
  ): Future[A] = {
    val p = scala.concurrent.Promise[A]()
    val task = new TimerTask {
      override def run(): Unit =
        try {
          p.failure(new Exception("TIMEOUT: " + timeout))
          ()
        } catch {
          case _: Throwable => ()
        }
    }
    timer.schedule(task, timeout.toMillis)

    unsafeRunToFuture(io.sandbox.mapError(FiberFailure(_))).map(p.success)
    p.future
  }

}
