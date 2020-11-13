package com.foreignlanguagereader.domain.client.common

import cats.data.Nested
import play.api.Logger
import play.api.libs.json.{JsError, JsSuccess, Reads}
import play.api.libs.ws.WSClient

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

/**
  * Common behavior for rest clients that we implement using WS
  */
trait WsClient extends Circuitbreaker {
  val ws: WSClient
  implicit val ec: ExecutionContext
  val headers: List[(String, String)] = List()

  override val logger: Logger = Logger(this.getClass)

  def get[T: ClassTag](
      url: String
  )(implicit reads: Reads[T]): Nested[Future, CircuitBreakerResult, T] = {
    val typeName = implicitly[ClassTag[T]].runtimeClass.getSimpleName
    val message = s"Failed to get $typeName from $url"
    get(url, message)
  }

  def get[T: ClassTag](
      url: String,
      logIfError: String
  )(implicit reads: Reads[T]): Nested[Future, CircuitBreakerResult, T] = {
    logger.info(s"Calling url $url")
    val typeName = implicitly[ClassTag[T]].runtimeClass.getSimpleName
    withBreaker(logIfError) {
      ws.url(url)
        // Doubled so that the circuit breaker will handle it.
        .withRequestTimeout(timeout * 2)
        .withHttpHeaders(headers: _*)
        .get()
        .map(_.json.validate[T])
        .map {
          case JsSuccess(result, _) => result
          case JsError(errors) =>
            val error = s"Failed to parse $typeName from $url: $errors"
            logger.error(error)
            throw new IllegalArgumentException(error)
        }
    }
  }
}
