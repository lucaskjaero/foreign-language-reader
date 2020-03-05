import pytest
from language_service.service.definition import get_definitions
from language_service.dto import ChineseDefinition


def test_calls_multiple_data_sources_on_chinese_definitions(mocker):
    cedict = mocker.patch("language_service.service.definition.cedict")
    wiktionary = mocker.patch("language_service.service.definition.wiktionary")

    get_definitions("CHINESE", "所有的")

    cedict.get_definitions.assert_called_once_with("所有的")
    wiktionary.get_definitions.assert_called_once_with("CHINESE", "所有的")


def test_chinese_definitions_return_none_if_no_data_sources_resolve(mocker):
    cedict = mocker.patch("language_service.service.definition.cedict")
    cedict.get_definitions.return_value = None
    wiktionary = mocker.patch("language_service.service.definition.wiktionary")
    wiktionary.get_definitions.return_value = None

    # None should be returned if no data source resolves correctly
    assert get_definitions("CHINESE", "所有的") is None


def test_chinese_definitions_return_cedict_if_wiktionary_fails(mocker):
    cedict = mocker.patch("language_service.service.definition.cedict")
    cedict.get_definitions.return_value = "cedict"
    wiktionary = mocker.patch("language_service.service.definition.wiktionary")
    wiktionary.get_definitions.return_value = None

    assert get_definitions("CHINESE", "所有的") == ["cedict"]


def test_chinese_definitions_return_wiktionary_if_cedict_fails(mocker):
    cedict = mocker.patch("language_service.service.definition.cedict")
    cedict.get_definitions.return_value = None
    wiktionary = mocker.patch("language_service.service.definition.wiktionary")
    wiktionary.get_definitions.return_value = ["wiktionary"]

    assert get_definitions("CHINESE", "所有的") == ["wiktionary"]


def test_chinese_definitions_correctly_merge_cedict_and_wiktionary(mocker):
    cedict_definition = ChineseDefinition(
        subdefinitions="cedict",
        tag="cedict",
        examples="cedict",
        pinyin="cedict",
        simplified="cedict",
        traditional="cedict",
        hsk="cedict",
    )

    wiktionary_definition = ChineseDefinition(
        subdefinitions="wiktionary",
        tag="wiktionary",
        examples="wiktionary",
        pinyin="wiktionary",
        simplified="wiktionary",
        traditional="wiktionary",
        hsk="wiktionary",
    )

    cedict = mocker.patch("language_service.service.definition.cedict")
    cedict.get_definitions.return_value = cedict_definition
    wiktionary = mocker.patch("language_service.service.definition.wiktionary")
    wiktionary.get_definitions.return_value = [wiktionary_definition]

    definition = get_definitions("CHINESE", "所有的")[0]

    assert definition.subdefinitions == "cedict"
    assert definition.tag == "wiktionary"
    assert definition.examples == "wiktionary"
    assert definition.pinyin == "cedict"
    assert definition.simplified == "cedict"
    assert definition.traditional == "cedict"
    assert definition.hsk is None


def test_can_pass_through_english_definitions(mocker):
    wiktionary = mocker.patch("language_service.service.definition.wiktionary")
    wiktionary.get_definitions.return_value = "any"

    assert get_definitions("ENGLISH", "any") == "any"

    wiktionary.get_definitions.assert_called_once_with("ENGLISH", "any")


def test_can_pass_through_spanish_definitions(mocker):
    wiktionary = mocker.patch("language_service.service.definition.wiktionary")
    wiktionary.get_definitions.return_value = "cualquier"

    assert get_definitions("SPANISH", "cualquier") == "cualquier"

    wiktionary.get_definitions.assert_called_once_with("SPANISH", "cualquier")
