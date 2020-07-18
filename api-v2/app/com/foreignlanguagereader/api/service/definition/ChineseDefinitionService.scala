package com.foreignlanguagereader.api.service.definition

import com.foreignlanguagereader.api.client.LanguageServiceClient
import com.foreignlanguagereader.api.client.common.{
  CircuitBreakerAttempt,
  CircuitBreakerNonAttempt,
  CircuitBreakerResult
}
import com.foreignlanguagereader.api.client.elasticsearch.ElasticsearchClient
import com.foreignlanguagereader.api.domain.Language
import com.foreignlanguagereader.api.domain.Language.Language
import com.foreignlanguagereader.api.domain.definition.DefinitionSource.DefinitionSource
import com.foreignlanguagereader.api.domain.definition.HskLevel.HSKLevel
import com.foreignlanguagereader.api.domain.definition._
import com.foreignlanguagereader.api.repository.definition.Cedict
import com.foreignlanguagereader.api.util.ContentFileLoader
import javax.inject.Inject
import play.api.Logger
import play.api.libs.json.{Json, Reads}

import scala.concurrent.{ExecutionContext, Future}

/**
  * Language specific handling for Chinese.
  * We have two dictionaries here, so we should combine them to produce the best possible results
  * In particular, CEDICT has a minimum level of quality, but doesn't have as many definitions.
  */
class ChineseDefinitionService @Inject()(
  val elasticsearch: ElasticsearchClient,
  val languageServiceClient: LanguageServiceClient,
  implicit val ec: ExecutionContext
) extends LanguageDefinitionService {
  override val logger: Logger = Logger(this.getClass)

  override val wordLanguage: Language = Language.CHINESE
  override val sources: List[DefinitionSource] =
    List(DefinitionSource.CEDICT, DefinitionSource.WIKTIONARY)

  def cedictFetcher
    : (Language,
       String) => Future[CircuitBreakerResult[Option[Seq[Definition]]]] =
    (_, word: String) =>
      Cedict.getDefinition(word) match {
        case Some(entries) =>
          Future.successful(
            CircuitBreakerAttempt(Some(entries.map(_.toDefinition)))
          )
        case None => Future.successful(CircuitBreakerNonAttempt())
    }

  override val definitionFetchers
    : Map[(DefinitionSource, Language), (Language, String) => Future[
      CircuitBreakerResult[Option[Seq[Definition]]]
    ]] = Map(
    (DefinitionSource.CEDICT, Language.ENGLISH) -> cedictFetcher,
    (DefinitionSource.WIKTIONARY, Language.ENGLISH) -> languageServiceFetcher
  )

  // Convert everything to traditional
  // We need one lookup token for elasticsearch.
  // And traditional is more specific
  // TODO reimplement this

  override def enrichDefinitions(
    definitionLanguage: Language,
    word: String,
    definitions: Map[DefinitionSource, Option[Seq[Definition]]]
  ): Seq[Definition] = {
    definitionLanguage match {
      case Language.ENGLISH => enrichEnglishDefinitions(word, definitions)
      case _                => super.enrichDefinitions(definitionLanguage, word, definitions)
    }
  }

  private[this] def enrichEnglishDefinitions(
    word: String,
    definitions: Map[DefinitionSource, Option[Seq[Definition]]]
  ): Seq[Definition] = {
    val cedict = definitions.getOrElse(DefinitionSource.CEDICT, None)
    val wiktionary = definitions.getOrElse(DefinitionSource.WIKTIONARY, None)
    logger.info(
      s"Enhancing results for $word using cedict with ${cedict.size} cedict results and ${wiktionary.size} wiktionary results"
    )

    (cedict, wiktionary) match {
      case (Some(cedict), Some(wiktionary)) =>
        logger.info(s"Combining cedict and wiktionary definitions for $word")
        mergeCedictAndWiktionary(
          word,
          cedict(0).asInstanceOf[ChineseDefinition],
          wiktionary.map(_.asInstanceOf[ChineseDefinition])
        )
      case (Some(cedict), None) =>
        logger.info(s"Using cedict definitions for $word")
        cedict
      case (None, Some(wiktionary)) if cedict.isEmpty =>
        logger.info(s"Using wiktionary definitions for $word")
        wiktionary
      // This should not happen. If it does then it's important to log it.
      case (None, None) =>
        val message =
          s"Definitions were lost for chinese word $word, check the request partitioner"
        logger.error(message)
        throw new IllegalStateException(message)
    }
  }

  private[this] def mergeCedictAndWiktionary(
    word: String,
    cedict: ChineseDefinition,
    wiktionary: Seq[ChineseDefinition]
  ): Seq[ChineseDefinition] = {
    cedict match {
      case empty if empty.subdefinitions.isEmpty =>
        // If CEDICT doesn't have subdefinitions, then we should return wiktionary data
        // We still want pronunciation and simplified/traditional mapping, so we will add cedict data
        addCedictDataToWiktionaryResults(word, cedict, wiktionary)
      // If are definitions from CEDICT, they are better.
      // In that case, we only want part of speech tag and examples from wiktionary.
      // But everything else will be the single CEDICT definition
      case _ => addWiktionaryDataToCedictResults(word, cedict, wiktionary)
    }
  }

  private[this] def addCedictDataToWiktionaryResults(
    word: String,
    cedict: ChineseDefinition,
    wiktionary: Seq[ChineseDefinition]
  ): Seq[ChineseDefinition] = {
    wiktionary.map(
      w =>
        ChineseDefinition(
          subdefinitions = w.subdefinitions,
          tag = w.tag,
          examples = w.examples,
          inputPinyin = cedict.pronunciation.pinyin,
          inputSimplified = Some(cedict.simplified),
          inputTraditional = Some(cedict.traditional),
          definitionLanguage = Language.ENGLISH,
          source = DefinitionSource.MULTIPLE,
          token = word
      )
    )
  }

  private[this] def addWiktionaryDataToCedictResults(
    word: String,
    cedict: ChineseDefinition,
    wiktionary: Seq[ChineseDefinition]
  ): Seq[ChineseDefinition] = {
    val examples = {
      val e = wiktionary.flatMap(_.examples).flatten.toList
      if (e.isEmpty) None else Some(e)
    }

    Seq(
      ChineseDefinition(
        subdefinitions = cedict.subdefinitions,
        tag = wiktionary(0).tag,
        examples = examples,
        inputPinyin = cedict.pronunciation.pinyin,
        inputSimplified = Some(cedict.simplified),
        inputTraditional = Some(cedict.traditional),
        definitionLanguage = Language.ENGLISH,
        source = DefinitionSource.MULTIPLE,
        token = word
      )
    )
  }
}

object ChineseDefinitionService {
  val toneRegex = "[12345]+"

  private[this] val pronunciations: Map[String, ChinesePronunciationFromFile] =
    ContentFileLoader
      .loadJsonResourceFile[Seq[ChinesePronunciationFromFile]](
        "/resources/definition/chinese/pronunciation.json"
      )
      .map(pron => pron.pinyin -> pron)
      .toMap

  private[this] val hsk: HskHolder = ContentFileLoader
    .loadJsonResourceFile[HskHolder]("/resources/definition/chinese/hsk.json")

  // This tags the words with zhuyin, wade giles, and IPA based on the pinyin.
  // It also pulls the tones out of the pinyin as a separate thing
  // This works because pinyin is a perfect sound system
  def getPronunciation(pinyin: String): ChinesePronunciation = {
    val (rawPinyin, tones) = separatePinyinFromTones(pinyin)

    // We don't want to drop any because tone and pinyin must line up.
    // If any part of the input is garbage then the whole thing should be treated as such.
    val pronunciation = {
      val temp = rawPinyin.map(pinyin => pronunciations.get(pinyin))
      if (temp.forall(_.isDefined)) Some(temp.flatten) else None
    }

    (pronunciation, tones) match {
      case (Some(p), Some(t)) =>
        p.zip(t)
          .map {
            case (pron, tone) => pron.toDomain(List(tone))
          }
          .reduce(_ + _)
      case (Some(p), None) =>
        p.map(_.toDomain()).reduce(_ + _)
      case _ => ChinesePronunciation()
    }
  }

  // Pulling tone numbers off pinyin turns out to be complicated
  // This returns the pinyin with all tones stripped,
  private[this] def separatePinyinFromTones(
    pinyin: String
  ): (Array[String], Option[Array[String]]) = {
    pinyin.split(" ") match {
      case hasTones if hasTones.forall(_.takeRight(1).matches(toneRegex)) =>
        (hasTones.map(_.dropRight(1)), Some(hasTones.map(_.takeRight(1))))
      // Specifically remove all tone marks from the pinyin.
      // Otherwise it will attempt to convert pinyin to other pronunciation with words in, which will fail
      case hasBadTones
          if hasBadTones.exists(_.takeRight(1).matches(toneRegex)) =>
        // We need to remove the last number, but there might be numbers within. Eg; 2B
        (
          hasBadTones.map(
            pinyin =>
              if (pinyin.takeRight(1).matches("[0-9]")) pinyin.dropRight(1)
              else pinyin
          ),
          None
        )
      case noTones => (noTones, None)
    }
  }

  def getHSK(simplified: String): HSKLevel = hsk.getLevel(simplified)
}

case class ChinesePronunciationFromFile(pinyin: String,
                                        ipa: String,
                                        zhuyin: String,
                                        wadeGiles: String) {
  def toDomain(tones: List[String] = List()): ChinesePronunciation =
    ChinesePronunciation(pinyin, ipa, zhuyin, wadeGiles, tones)
}
object ChinesePronunciationFromFile {
  implicit val reads: Reads[ChinesePronunciationFromFile] =
    Json.reads[ChinesePronunciationFromFile]
  implicit val readsSeq: Reads[Seq[ChinesePronunciationFromFile]] =
    Reads.seq(reads)
}

case class HskHolder(hsk1: Set[String],
                     hsk2: Set[String],
                     hsk3: Set[String],
                     hsk4: Set[String],
                     hsk5: Set[String],
                     hsk6: Set[String]) {
  def getLevel(simplified: String): HskLevel.Value = simplified match {
    case s if hsk1.contains(s) => HskLevel.ONE
    case s if hsk2.contains(s) => HskLevel.TWO
    case s if hsk3.contains(s) => HskLevel.THREE
    case s if hsk4.contains(s) => HskLevel.FOUR
    case s if hsk5.contains(s) => HskLevel.FIVE
    case s if hsk6.contains(s) => HskLevel.SIX
    case _                     => HskLevel.NONE
  }
}
object HskHolder {
  implicit val reads: Reads[HskHolder] = Json.reads[HskHolder]
}
