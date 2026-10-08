"""Reject stale/tampered original artifacts before staging a data-preserving backfill."""

import importlib.util
import json
from pathlib import Path

import pytest

spec = importlib.util.spec_from_file_location(
    "existing_catalog",
    Path(__file__).resolve().parents[1] / "prepare-existing-catalog.py",
)
adapter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(adapter)


def test_modified_canonical_is_rejected_before_creating_output(tmp_path):
    topic = "synthetic"
    canonical = tmp_path / "topics" / topic / "processed"
    canonical.mkdir(parents=True)
    for name in ["books", "documents", "toc", "sources"]:
        (canonical / (name + ".jsonl")).write_text("{}\n")
    source = {
        "contract_version": "discovery-catalog-import-v1",
        "snapshot_id": "original",
        "topics": [
            {
                "topic": {"ml_topic_id": topic},
                "canonical_hashes": {
                    n.name: adapter.adapter.digest(n.read_bytes())
                    for n in canonical.iterdir()
                },
            }
        ],
    }
    original = tmp_path / "import.json"
    original.write_text(json.dumps(source))
    selection = tmp_path / "selection.json"
    selection.write_text(
        json.dumps(
            {
                "source_snapshot_id": "original",
                "source_manifest_sha256": adapter.adapter.digest(original.read_bytes()),
                "topics": [{"ml_topic_id": topic}],
            }
        )
    )
    (canonical / "toc.jsonl").write_text("modified\n")
    output = tmp_path / "output"
    with pytest.raises(ValueError, match="canonical_source_modified"):
        adapter.prepare(original, selection, tmp_path / "topics", [topic], output)
    assert not output.exists()


def test_selection_from_different_import_is_rejected(tmp_path):
    original = tmp_path / "import.json"
    original.write_text(
        json.dumps(
            {
                "contract_version": "discovery-catalog-import-v1",
                "snapshot_id": "original",
            }
        )
    )
    selection = tmp_path / "selection.json"
    selection.write_text(
        json.dumps(
            {
                "source_snapshot_id": "other",
                "source_manifest_sha256": adapter.adapter.digest(original.read_bytes()),
            }
        )
    )
    with pytest.raises(ValueError, match="source_selection_mismatch"):
        adapter.prepare(
            original, selection, tmp_path, ["synthetic"], tmp_path / "output"
        )
