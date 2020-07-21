package com.foreignlanguagereader.api.client.elasticsearch.searchstates

import com.foreignlanguagereader.api.client.elasticsearch.LookupAttempt
import com.foreignlanguagereader.api.domain.Language
import com.foreignlanguagereader.api.domain.definition.{
  ChineseDefinition,
  Definition,
  DefinitionSource,
  GenericDefinition
}
import com.foreignlanguagereader.api.domain.word.PartOfSpeech
import com.sksamuel.elastic4s.ElasticDsl.{indexInto, updateById}
import com.sksamuel.elastic4s.playjson._
import org.scalatest.funspec.AnyFunSpec

class ElasticsearchResultTest extends AnyFunSpec {
  val index: String = "definition"
  val fields: Map[String, String] =
    Map("field1" -> "value1", "field2" -> "value2", "field3" -> "value3")
  describe("an elasticsearch result") {
    describe("where nothing was refetched") {
      it("does not persist anything to elasticsearch") {
        val result = ElasticsearchResult[Definition](
          index = index,
          fields = fields,
          result = None,
          fetchCount = 5,
          lookupId = None,
          refetched = false
        )

        assert(result.cacheQueries.isEmpty)
        assert(result.updateAttemptsQuery.isEmpty)
        assert(result.toIndex.isEmpty)
      }
    }

    describe("where refetching gave no result") {
      val result = ElasticsearchResult[Definition](
        index = index,
        fields = fields,
        result = None,
        fetchCount = 5,
        lookupId = None,
        refetched = true
      )
      val attemptsQuery =
        Left(indexInto("attempts").doc(LookupAttempt(index, fields, 5)))
      it("correctly saves fetch attempts to elasticsearch") {
        assert(result.updateAttemptsQuery.contains(attemptsQuery))
      }
      it("does not cache anything") {
        assert(result.cacheQueries.isEmpty)
        assert(result.toIndex.contains(List(attemptsQuery)))
      }
    }

    val dummyChineseDefinition = ChineseDefinition(
      subdefinitions = List("definition 1", "definition 2"),
      tag = Some(PartOfSpeech.NOUN),
      examples = Some(List("example 1", "example 2")),
      inputPinyin = "ni3 hao3",
      inputSimplified = Some("你好"),
      inputTraditional = Some("你好"),
      definitionLanguage = Language.ENGLISH,
      source = DefinitionSource.MULTIPLE,
      token = "你好"
    )
    val dummyGenericDefinition = GenericDefinition(
      subdefinitions = List("definition 1", "definition 2"),
      ipa = "ipa",
      tag = Some(PartOfSpeech.NOUN),
      examples = Some(List("example 1", "example 2")),
      definitionLanguage = Language.ENGLISH,
      wordLanguage = Language.ENGLISH,
      source = DefinitionSource.MULTIPLE,
      token = "anything"
    )

    describe("on a previously untried query") {
      val result = ElasticsearchResult[Definition](
        index = index,
        fields = fields,
        result = Some(List(dummyChineseDefinition, dummyGenericDefinition)),
        fetchCount = 1,
        lookupId = None,
        refetched = true
      )
      val attemptsQuery =
        Left(indexInto("attempts").doc(LookupAttempt(index, fields, 1)))
      val indexQuery = List(
        Left(indexInto(index).doc(dummyChineseDefinition)),
        Left(indexInto(index).doc(dummyGenericDefinition))
      )
      it("creates a new fetch attempt in elasticsearch") {
        assert(result.updateAttemptsQuery.contains(attemptsQuery))
      }
      it("caches search results to elasticsearch") {
        assert(result.cacheQueries.contains(indexQuery))
        assert(result.toIndex.contains(attemptsQuery :: indexQuery))
      }
    }

    describe(
      "on a query which previously failed but contained results this time"
    ) {
      val attemptsId = "2423423"
      val result = ElasticsearchResult[Definition](
        index = index,
        fields = fields,
        result = Some(List(dummyChineseDefinition, dummyGenericDefinition)),
        fetchCount = 2,
        lookupId = Some(attemptsId),
        refetched = true
      )
      val attemptsQuery =
        Right(
          updateById("attempts", attemptsId)
            .doc(LookupAttempt(index = index, fields = fields, count = 2))
        )
      val indexQuery = List(
        Left(indexInto(index).doc(dummyChineseDefinition)),
        Left(indexInto(index).doc(dummyGenericDefinition))
      )
      it("updates the previous fetch attempt in elasticsearch") {
        assert(result.updateAttemptsQuery.contains(attemptsQuery))
      }
      it("caches search results to elasticsearch") {
        assert(result.cacheQueries.contains(indexQuery))
        assert(result.toIndex.contains(attemptsQuery :: indexQuery))
      }
    }
  }
}
