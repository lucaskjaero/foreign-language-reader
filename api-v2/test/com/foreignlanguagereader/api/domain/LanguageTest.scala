package com.foreignlanguagereader.api.domain

class LanguageTest extends org.scalatest.FunSuite {

  test("Enums should evaluate to their value using toString") {
    assert(Language.CHINESE.toString == "CHINESE")
    assert(Language.ENGLISH.toString == "ENGLISH")
    assert(Language.SPANISH.toString == "SPANISH")
  }

  test("Enums can be read from a string") {
    assert(Language.fromString("CHINESE").isDefined)
    assert(Language.fromString("CHINESE").get == Language.CHINESE)
    assert(Language.fromString("KLINGON").isEmpty)
  }
}
