"""Offline adapter tests. Mock outputs prove protocol behavior, not Gemini accuracy."""

import importlib.util
import json
from pathlib import Path
from unittest.mock import patch

import httpx
import pytest
from pydantic import ValidationError

spec = importlib.util.spec_from_file_location(
    "topic_content_adapter",
    Path(__file__).resolve().parents[1] / "topic-content-preparation.py",
)
adapter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(adapter)


def proposal():
    targets = {
        ability: {
            "objective": "Measure this concept independently of other concepts",
            "misconceptions": [
                "Confuse the concept with its inverse",
                "Apply the concept outside its assumptions",
            ],
            "difficulty": difficulty,
        }
        for ability, difficulty in (
            ("meaning", 1),
            ("application", 2),
            ("reasoning", 3),
        )
    }
    return {
        "outline_version": "topic-outline-v1",
        "topic_id": "synthetic-topic",
        "concepts": [
            {
                "id": f"concept-{i}",
                "name": f"Concept {i}",
                "aliases": [f"Alpha {i}", f"Beta {i}"],
                "abilities": targets,
            }
            for i in range(6)
        ],
        "edges": [],
    }


def test_only_field_name_goes_to_ai_and_validated_cache_prevents_duplicate_calls(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("GEMINI_API_KEY", "synthetic-key")
    captured = []

    def respond(request):
        captured.append(json.loads(request.content))
        return httpx.Response(
            200,
            json={
                "candidates": [
                    {"content": {"parts": [{"text": json.dumps(proposal())}]}}
                ]
            },
        )

    client = httpx.Client(transport=httpx.MockTransport(respond))
    with patch.object(adapter.httpx, "Client", return_value=client):
        outline, cache = adapter.propose_outline(
            "synthetic-topic", "Synthetic Field", tmp_path, "synthetic-model"
        )
    user_input = json.loads(captured[0]["contents"][0]["parts"][0]["text"])
    assert set(user_input) == {"topic_id", "outline_version", "name"}
    assert "synthetic-key" not in cache.read_text()
    assert captured[0]["generationConfig"]["responseSchema"]["type"] == "OBJECT"
    with patch.object(
        adapter.httpx, "Client", side_effect=AssertionError("duplicate model call")
    ):
        assert (
            adapter.propose_outline(
                "synthetic-topic", "Synthetic Field", tmp_path, "synthetic-model"
            )[0]
            == outline
        )


def test_invalid_proposals_keep_raw_output_but_never_create_validated_cache(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("GEMINI_API_KEY", "synthetic-key")
    invalid = proposal()
    invalid["concepts"][1]["aliases"] = invalid["concepts"][0]["aliases"]
    client = httpx.Client(
        transport=httpx.MockTransport(
            lambda request: httpx.Response(
                200,
                json={
                    "candidates": [
                        {"content": {"parts": [{"text": json.dumps(invalid)}]}}
                    ]
                },
            )
        )
    )
    with (
        patch.object(adapter.httpx, "Client", return_value=client),
        pytest.raises(ValidationError),
    ):
        adapter.propose_outline(
            "synthetic-topic", "Synthetic Field", tmp_path, "synthetic-model"
        )
    assert len(list(tmp_path.glob("*.raw.json"))) == 1
    assert len(list(tmp_path.glob("*.json"))) == 1


def test_missing_source_or_wrong_field_is_rejected_before_a_model_call(tmp_path):
    with pytest.raises(ValueError, match="source_snapshot_not_unique"):
        adapter.find_source(tmp_path, "missing", "synthetic-topic")
    directory = tmp_path / "pipeline/handoff"
    directory.mkdir(parents=True)
    adapter.save(
        directory / "import.json",
        {
            "snapshot_id": "source",
            "topics": [{"topic": {"ml_topic_id": "other-topic"}}],
        },
    )
    with pytest.raises(ValueError, match="source_topic_differs"):
        adapter.find_source(tmp_path, "source", "synthetic-topic")
