package com.foreignlanguagereader.api.contentsource.definition.webster

import com.foreignlanguagereader.api.contentsource.definition.webster.common.WebsterPartOfSpeech.WebsterPartOfSpeech
import com.foreignlanguagereader.api.contentsource.definition.DefinitionEntry
import com.foreignlanguagereader.api.contentsource.definition.webster.common.{
  WebsterDefinedRunOnPhrase,
  WebsterDefinition,
  WebsterHeadwordInfo,
  WebsterInflection,
  WebsterMeta,
  WebsterPartOfSpeech
}
import com.foreignlanguagereader.api.domain.Language
import com.foreignlanguagereader.api.domain.Language.Language
import com.foreignlanguagereader.api.domain.definition.{
  Definition,
  DefinitionSource
}
import com.foreignlanguagereader.api.domain.definition.DefinitionSource.DefinitionSource
import com.foreignlanguagereader.api.domain.word.PartOfSpeech.PartOfSpeech
import com.foreignlanguagereader.api.util.JsonSequenceHelper
import play.api.libs.functional.syntax._
import play.api.libs.json.{JsPath, Json, Reads, Writes}

case class WebsterLearnersDefinitionEntry(
  meta: WebsterMeta,
  headwordInfo: WebsterHeadwordInfo,
  partOfSpeech: WebsterPartOfSpeech,
  inflections: Option[Seq[WebsterInflection]],
  definitions: Seq[WebsterDefinition],
  definedRunOns: Option[Seq[WebsterDefinedRunOnPhrase]],
  shortDefinitions: Seq[String]
) extends DefinitionEntry {
  override val wordLanguage: Language = Language.ENGLISH
  override val definitionLanguage: Language = Language.ENGLISH
  override val source: DefinitionSource =
    DefinitionSource.MIRRIAM_WEBSTER_LEARNERS

  // TODO Usage labels https://www.merriam-webster.com/help/explanatory-notes/dict-usage
  // Might be worth noting if a word is obsolete
  // Or non-standard
  // Learners may not even want to see obsolete words
  // And definitely should be discouraged from adding them to their vocabulary list.

  // Here we make some opinionated choices about how webster definitions map to our model
  val tag: Option[PartOfSpeech] = Some(
    WebsterPartOfSpeech.toDomain(partOfSpeech)
  )

  val subdefinitions: List[String] = {
    val d = definitions
    // senseSequence: Option[Seq[Seq[WebsterSense]]]
    // remove the nones
      .flatMap(_.senseSequence)
      // Our data model needs them flattened to one list
      .flatten
      .flatten
      // definingText: WebsterDefiningText => examples: Option[Seq[WebsterVerbalIllustration]]
      .flatMap(_.definingText.text)

    if (d.nonEmpty) d.toList else shortDefinitions.toList
  }

  val examples: List[String] = {
    //definitions: Seq[WebsterDefinition]
    val e = definitions
    // senseSequence: Option[Seq[Seq[WebsterSense]]]
    // remove the nones
      .flatMap(_.senseSequence)
      // Our data model needs them flattened to one list
      .flatten
      .flatten
      // definingText: WebsterDefiningText => examples: Option[Seq[WebsterVerbalIllustration]]
      .flatMap(_.definingText.examples)
      .flatten
      // Verbal Illustration means examples, so we can just get the text.
      .map(_.text)
    if (e.isEmpty) List() else e.toList
  }

  // Id is either the token, or token:n where n is the nth definition for the token.
  val token: String = meta.id.split(":")(0)

  lazy override val toDefinition: Definition = Definition(
    subdefinitions,
    tag,
    examples,
    wordLanguage,
    definitionLanguage,
    source,
    token
  )
}
object WebsterLearnersDefinitionEntry {
  implicit val reads: Reads[WebsterLearnersDefinitionEntry] = (
    (JsPath \ "meta").read[WebsterMeta] and
      (JsPath \ "hwi").read[WebsterHeadwordInfo] and
      (JsPath \ "fl").read[WebsterPartOfSpeech] and
      (JsPath \ "ins")
        .readNullable[Seq[WebsterInflection]](WebsterInflection.helper.readsSeq) and
      (JsPath \ "def")
        .read[Seq[WebsterDefinition]](WebsterDefinition.helper.readsSeq) and
      (JsPath \ "dros").readNullable[Seq[WebsterDefinedRunOnPhrase]](
        WebsterDefinedRunOnPhrase.helper.readsSeq
      ) and
      (JsPath \ "shortdef").read[Seq[String]](Reads.seq[String])
  )(WebsterLearnersDefinitionEntry.apply _)
  implicit val writes: Writes[WebsterLearnersDefinitionEntry] =
    Json.writes[WebsterLearnersDefinitionEntry]
  implicit val helper: JsonSequenceHelper[WebsterLearnersDefinitionEntry] =
    new JsonSequenceHelper[WebsterLearnersDefinitionEntry]
}