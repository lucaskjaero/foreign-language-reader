package com.foreignlanguagereader.api.contentsource.definition.cedict

import com.foreignlanguagereader.api.contentsource.definition.DefinitionEntry
import com.foreignlanguagereader.api.domain.Language
import com.foreignlanguagereader.api.domain.Language.Language
import com.foreignlanguagereader.api.domain.definition.DefinitionSource.DefinitionSource
import com.foreignlanguagereader.api.domain.definition.{
  ChineseDefinition,
  Definition,
  DefinitionSource
}
import com.foreignlanguagereader.api.domain.word.PartOfSpeech.PartOfSpeech
import play.api.libs.json.{Format, Json, Reads}

case class CEDICTDefinitionEntry(override val subdefinitions: List[String],
                                 pinyin: String,
                                 simplified: String,
                                 traditional: String,
                                 override val token: String)
    extends DefinitionEntry {
  override val tag: Option[PartOfSpeech] = None
  override val pronunciation: String = pinyin
  override val examples: Option[List[String]] = None

  override val wordLanguage: Language = Language.CHINESE
  override val definitionLanguage: Language = Language.ENGLISH
  override val source: DefinitionSource = DefinitionSource.CEDICT

  // We clearly do have simplified and traditional values so let's use them.
  override def toDefinition(partOfSpeech: PartOfSpeech): Definition =
    ChineseDefinition(
      subdefinitions = subdefinitions,
      tag = partOfSpeech,
      examples = examples,
      inputPinyin = pinyin,
      inputSimplified = Some(simplified),
      inputTraditional = Some(traditional),
      definitionLanguage = definitionLanguage,
      source = source,
      token = token
    )
}

object CEDICTDefinitionEntry {
  // Allows serializing and deserializing in json
  implicit val format: Format[CEDICTDefinitionEntry] =
    Json.format[CEDICTDefinitionEntry]
  implicit val readsSeq: Reads[Seq[CEDICTDefinitionEntry]] =
    Reads.seq(format.reads)
}
