"""Translation worker boundaries; the real enrichment cache is tested in Data-Pipeline."""

import importlib.util
from pathlib import Path
from unittest.mock import Mock

import pytest

spec = importlib.util.spec_from_file_location(
    "topic_toc_translation",
    Path(__file__).resolve().parents[1] / "topic-toc-translation.py",
)
adapter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(adapter)


@pytest.fixture
def source(tmp_path):
    source = tmp_path / "original"
    source.mkdir()
    for name in ("books", "documents", "toc", "sources"):
        (source / (name + ".jsonl")).write_text("{}\n")
    return source


@pytest.mark.parametrize(
    ("pending", "calls", "status"),
    [(["remaining-book"], 2, "PREPARING"), ([], 1, "PREPARING"), ([], 0, "READY")],
)
def test_only_cached_completed_translation_releases_outline_worker(
    source, monkeypatch, pending, calls, status
):
    translator = Mock()
    monkeypatch.setattr(adapter, "GeminiTranslator", Mock(return_value=translator))
    enrich = Mock(
        return_value={"pending_books": pending, "api_requests": calls, "failures": []}
    )
    monkeypatch.setattr(adapter, "enrich_english", enrich)
    result = adapter.prepare(source, source.parent / "request")
    assert result["status"] == status
    assert Path(result["canonicalDataPath"]).is_relative_to(
        source.parent / "request/content-english"
    )
    assert enrich.call_args.kwargs == {
        "scope": "toc",
        "workers": 2,
        "max_new_requests": 2,
    }
    assert set(result["identity"]["sourceHashes"]) == {p.name for p in source.iterdir()}
    translator.close.assert_called_once()


def test_failed_or_changed_input_never_becomes_ready(source, monkeypatch):
    translator = Mock()
    monkeypatch.setattr(adapter, "GeminiTranslator", Mock(return_value=translator))
    monkeypatch.setattr(
        adapter,
        "enrich_english",
        Mock(return_value={"failures": [{"reason": "invalid"}]}),
    )
    with pytest.raises(ValueError, match="toc_translation_failed"):
        adapter.prepare(source, source.parent / "request")

    def changed(*args, **kwargs):
        (source / "toc.jsonl").write_text('{"changed":true}\n')
        return {"failures": [], "pending_books": [], "api_requests": 0}

    monkeypatch.setattr(adapter, "enrich_english", changed)
    with pytest.raises(ValueError, match="translation_source_changed"):
        adapter.prepare(source, source.parent / "request")
    assert translator.close.call_count == 2
