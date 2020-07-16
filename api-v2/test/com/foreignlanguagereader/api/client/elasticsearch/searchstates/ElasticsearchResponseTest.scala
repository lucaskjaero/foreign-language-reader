package com.foreignlanguagereader.api.client.elasticsearch.searchstates

import com.foreignlanguagereader.api.client.common.{
  CircuitBreakerAttempt,
  CircuitBreakerNonAttempt,
  CircuitBreakerResult
}
import com.foreignlanguagereader.api.client.elasticsearch.LookupAttempt
import com.foreignlanguagereader.api.domain.Language
import com.foreignlanguagereader.api.domain.definition.{
  Definition,
  DefinitionSource,
  GenericDefinition
}
import com.foreignlanguagereader.api.domain.word.PartOfSpeech
import com.foreignlanguagereader.api.util.ElasticsearchTestUtil
import com.sksamuel.elastic4s.HitReader
import org.scalatest.funspec.AsyncFunSpec
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Future

class ElasticsearchResponseTest extends AsyncFunSpec with MockitoSugar {
  val index: String = "definition"
  val fields: Map[String, String] =
    Map("field1" -> "value1", "field2" -> "value2", "field3" -> "value3")
  val fetchedDefinition = GenericDefinition(
    List("refetched"),
    Some(PartOfSpeech.NOUN),
    List("this was refetched"),
    Language.ENGLISH,
    Language.ENGLISH,
    DefinitionSource.MULTIPLE,
    "refetched"
  )
  val attemptsRefreshEligible = LookupAttempt(index, fields, 4)
  val attemptsRefreshIneligible = LookupAttempt(index, fields, 7)

  // These are necessary to avoid having to manually create the results.
  // They are pulled into ElasticsearchTestUtil.cacheQueryResponseFrom, and given the appropriate responses.
  implicit val definitionHitReader: HitReader[Definition] =
    mock[HitReader[Definition]]
  implicit val attemptsHitReader: HitReader[LookupAttempt] =
    mock[HitReader[LookupAttempt]]

  val responseBase: ElasticsearchResponse[Definition] =
    ElasticsearchResponse[Definition](
      index = index,
      fields = fields,
      fetcher = () =>
        Future.successful(CircuitBreakerAttempt(Some(List(fetchedDefinition)))),
      maxFetchAttempts = 5,
      response = None
    )

  describe("an elasticsearch response") {
    describe("where data was found in elasticsearch") {
      val es = ElasticsearchTestUtil.cacheQueryResponseFrom[Definition](
        Right(List(fetchedDefinition)),
        Right(attemptsRefreshEligible)
      )

      val response = responseBase.copy(response = Some(es))

      it("contains the response") {
        assert(response.elasticsearchResult.contains(Seq(fetchedDefinition)))
      }
      it("does not call the fetcher") {
        response.getResultOrFetchFromSource.map(result => {
          assert(!result.refetched)
        })
      }
    }

    describe("where data was not found") {
      val es = ElasticsearchTestUtil.cacheQueryResponseFrom[Definition](
        Right(List()),
        Right(attemptsRefreshEligible)
      )
      it("updates the fetch count if the fetcher fetched") {
        val response = responseBase.copy(response = Some(es))
        assert(response.elasticsearchResult.isEmpty)

        response.getResultOrFetchFromSource.map(result => {
          assert(result.refetched)
          assert(result.result.contains(List(fetchedDefinition)))
        })
      }
      it("updates the fetch count if the fetcher failed") {
        val fetcher
          : () => Future[CircuitBreakerResult[Option[Seq[Definition]]]] =
          () => Future.failed(new IllegalArgumentException("Oh no"))
        val response = responseBase.copy(response = Some(es), fetcher = fetcher)
        assert(response.elasticsearchResult.isEmpty)

        response.getResultOrFetchFromSource.map(result => {
          assert(result.refetched)
          assert(result.result.isEmpty)
        })
      }
      it("does not update the fetch count if the fetcher did not fetch") {
        val response = responseBase.copy(
          response = Some(es),
          fetcher = () =>
            Future.successful(
              CircuitBreakerNonAttempt[Option[Seq[Definition]]]()
          )
        )
        assert(response.elasticsearchResult.isEmpty)

        response.getResultOrFetchFromSource.map(result => {
          assert(!result.refetched)
          assert(result.result.isEmpty)
        })
      }
    }
    describe("where fetching has failed too many times") {
      it("does not call the fetcher") {
        val es = ElasticsearchTestUtil.cacheQueryResponseFrom[Definition](
          Right(List()),
          Right(attemptsRefreshIneligible)
        )

        val response = responseBase.copy(response = Some(es))
        assert(response.elasticsearchResult.isEmpty)

        response.getResultOrFetchFromSource.map(result => {
          assert(!result.refetched)
          assert(result.result.isEmpty)
        })
      }
    }
    describe("with errors") {
      it("fetches when the fetch count cannot be found") {
        val es = ElasticsearchTestUtil.cacheQueryResponseFrom[Definition](
          Right(List()),
          Left(new IllegalArgumentException("oh no"))
        )
        val response = responseBase.copy(response = Some(es))
        assert(response.elasticsearchResult.isEmpty)

        response.getResultOrFetchFromSource.map(result => {
          assert(result.refetched)
          assert(result.fetchCount == 1)
        })
      }
      it("gracefully handles when elasticsearch fails") {
        val es = ElasticsearchTestUtil.cacheQueryResponseFrom[Definition](
          Left(new IllegalArgumentException("oh no")),
          Right(attemptsRefreshEligible)
        )
        val response = responseBase.copy(response = Some(es))
        assert(response.elasticsearchResult.isEmpty)
      }

      it("gracefully handles when elasticsearch is completely unavailable") {
        assert(responseBase.elasticsearchResult.isEmpty)

        responseBase.getResultOrFetchFromSource.map(result => {
          assert(result.refetched)
          assert(result.fetchCount == 1)
        })
      }
    }
  }
}