"""The real storefront renderer gates publication before any AI review call."""
import importlib.util
import json
from pathlib import Path

import pytest

spec = importlib.util.spec_from_file_location(
    "topic_review_adapter", Path(__file__).resolve().parents[1] / "topic-question-review.py"
)
adapter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(adapter)


def candidate(tmp_path, text):
    path = tmp_path / ("q_" + "a" * 20 + ".json")
    path.write_text(json.dumps({"stem": text, "choices": ["1", "2", "3", "4"],
                                "explanation": r"The result is \(2\)."}))


def test_valid_content_has_exact_frozen_field_count(tmp_path):
    candidate(tmp_path, r"Find \(1+1\).")
    result = adapter.validate_rendering(tmp_path, 1)
    assert result["status"] == "PASSED" and result["fields"] == 6
    assert result["rendererHash"].startswith("sha256:")
    with pytest.raises(ValueError, match="count_differs"):
        adapter.validate_rendering(tmp_path, 2)


@pytest.mark.parametrize("text", [r"Find \(\unknownCommand{2}\).", r"An inline \[2+2\] formula."])
def test_bad_math_cannot_pass_by_having_valid_json(tmp_path, text):
    candidate(tmp_path, text)
    with pytest.raises(ValueError, match="not_renderable"):
        adapter.validate_rendering(tmp_path, 1)


def test_missing_renderer_never_means_approval(tmp_path, monkeypatch):
    monkeypatch.delenv("TOPIC_CONTENT_RENDER_NODE", raising=False)
    monkeypatch.setattr(adapter.shutil, "which", lambda _: None)
    with pytest.raises(ValueError, match="unavailable"):
        adapter.validate_rendering(tmp_path, 1)
