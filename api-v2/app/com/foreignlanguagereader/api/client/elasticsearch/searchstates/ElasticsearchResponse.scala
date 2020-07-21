package com.foreignlanguagereader.api.client.elasticsearch.searchstates

import com.foreignlanguagereader.api.client.common.{
  CircuitBreakerAttempt,
  CircuitBreakerNonAttempt,
  CircuitBreakerResult
}
import com.foreignlanguagereader.api.client.elasticsearch.LookupAttempt
import com.sksamuel.elastic4s.playjson._
import com.sksamuel.elastic4s.requests.searches.{
  MultiSearchResponse,
  SearchResponse
}
import com.sksamuel.elastic4s.{ElasticError, HitReader, Indexable}
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

/**
  *
  * Turns the raw elasticsearch response into the query result.
  * This decides whether we will need to refetch from the original content source.
  *
  * @param index The elasticsearch index to cache the data. Should just be the type
  * @param fields The fields needed to look up the correct item. Think of this as the primary key.
  * @param fetcher A function to be called if results are not in elasticsearch, which can try to get the results again.
  * @param maxFetchAttempts If we don't have any results, how many times should we search for this? Highly source dependent.
  * @param response The elasticsearch response created by using the query in ElasticsearchRequest
  * @param indexable$T$0 Automatically generated if Reads[T] is defined
  * @param hitReader Automatically generated if Writes[T] is defined
  * @param attemptsHitReader Automatically generated from lookup attempts
  * @param tag The class of T so that sequences can be initialized. Automatically given.
  * @param ec Automatically taken from the implicit val near the caller. This is the thread pool to block on when fetching.
  * @tparam T A case class with Reads[T] and Writes[T] defined.
  */
case class ElasticsearchResponse[T: Indexable](
  index: String,
  fields: Map[String, String],
  fetcher: () => Future[CircuitBreakerResult[Option[Seq[T]]]],
  maxFetchAttempts: Int,
  response: Option[MultiSearchResponse]
)(implicit hitReader: HitReader[T],
  attemptsHitReader: HitReader[LookupAttempt],
  tag: ClassTag[T],
  ec: ExecutionContext) {
  val logger: Logger = Logger(this.getClass)

  val (
    elasticsearchResult: Option[Seq[T]],
    fetchCount: Int,
    lookupId: Option[String]
  ) =
    response match {
      case Some(r) =>
        val result = parseResults(r.items(0).response)
        val (attempts, id) = parseAttempts(r.items(1).response)
        (result, attempts, id)
      case None =>
        (None, 0, None)
    }

  lazy val getResultOrFetchFromSource: Future[ElasticsearchResult[T]] =
    elasticsearchResult match {
      case Some(es) =>
        Future.successful(
          ElasticsearchResult(
            index = index,
            fields = fields,
            result = Some(es),
            fetchCount = fetchCount,
            lookupId = lookupId,
            refetched = false
          )
        )
      case None if fetchCount < maxFetchAttempts => fetchFromSource
      case None =>
        Future.successful(
          ElasticsearchResult(
            index = index,
            fields = fields,
            result = None,
            fetchCount = fetchCount,
            lookupId = lookupId,
            refetched = false
          )
        )
    }

  lazy val fetchFromSource: Future[ElasticsearchResult[T]] = {
    logger.info(s"Refetching from source for query on $index")
    fetcher()
      .map {
        case CircuitBreakerAttempt(result) =>
          ElasticsearchResult(
            index = index,
            fields = fields,
            result = result,
            fetchCount = fetchCount + 1,
            lookupId = lookupId,
            refetched = true
          )
        case CircuitBreakerNonAttempt() =>
          ElasticsearchResult(
            index = index,
            fields = fields,
            result = None,
            fetchCount = fetchCount,
            lookupId = lookupId,
            refetched = false
          )
      }
      .recover {
        case e: Exception =>
          logger.error(
            s"Failed to get result from elasticsearch on index $index due to error ${e.getMessage}",
            e
          )
          ElasticsearchResult(
            index = index,
            fields = fields,
            result = None,
            fetchCount = fetchCount + 1,
            lookupId = lookupId,
            refetched = true
          )

      }
  }

  private[this] def parseResults(
    results: Either[ElasticError, SearchResponse]
  ): Option[Seq[T]] = results match {
    case Left(error) =>
      logger.error(
        s"Failed to get result from elasticsearch on index $index due to error ${error.reason}",
        error.asException
      )
      None
    case Right(response) =>
      val results = response.hits.hits.map(_.to[T])
      if (results.nonEmpty) Some(results.toIndexedSeq) else None
  }

  private[this] def parseAttempts(
    attempts: Either[ElasticError, SearchResponse]
  ): (Int, Option[String]) = attempts match {
    case Left(error) =>
      logger.error(
        s"Failed to get request count from elasticsearch on index $index due to error ${error.reason}",
        error.asException
      )
      (0, None)
    case Right(response) =>
      val hit = response.hits.hits(0)
      (hit.to[LookupAttempt].count, Some(hit.id))
  }
}

object ElasticsearchResponse {
  def fromResult[T: Indexable](
    request: ElasticsearchRequest[T],
    result: CircuitBreakerResult[Option[MultiSearchResponse]]
  )(implicit hitReader: HitReader[T],
    tag: ClassTag[T],
    ec: ExecutionContext): ElasticsearchResponse[T] = {
    val r = result match {
      case CircuitBreakerAttempt(x)   => x
      case CircuitBreakerNonAttempt() => None
    }

    ElasticsearchResponse(
      request.index,
      request.fields,
      request.fetcher,
      request.maxFetchAttempts,
      r
    )
  }
}
