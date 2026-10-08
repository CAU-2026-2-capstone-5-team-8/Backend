#!/usr/bin/env python3
"""Private bounded question worker. No book text or account data is sent to Gemini."""

import hashlib
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "Question-Generation/src"))

from question_generation.concept_batch import (
    generate_concept_batch,
    generation_identity,
    save_json,
)
from question_generation.concept_contract import content_hash
from question_generation.concept_generation import file_hash
from question_generation.config import load_settings


def main():
    payload = json.load(sys.stdin)
    directory = Path(sys.argv[1]).resolve() / f"request-{int(payload['requestId'])}"
    source = Path(payload["contentReportPath"]).resolve(strict=True)
    if (
        not source.is_relative_to(directory)
        or file_hash(source) != payload["contentReportHash"]
    ):
        raise ValueError("content_report_changed")
    content = json.loads(source.read_text())
    if (
        content["status"] != "CONCEPTS_READY"
        or content["topicId"] != payload["slug"]
        or content["sourceSnapshotId"] != payload["sourceSnapshotId"]
    ):
        raise ValueError("content_source_differs")
    allowed = {
        "outline.json",
        "book-profiles.jsonl",
        "targets.json",
        "blueprint.json",
        "configs/features.yaml",
        "configs/concept_graph.yaml",
        "configs/concept_matching_v2.yaml",
    }
    for name, expected in content["artifactHashes"].items():
        if name not in allowed:
            raise ValueError("unknown_content_artifact")
        path = (source.parent / name).resolve(strict=True)
        if not path.is_relative_to(source.parent) or file_hash(path) != expected:
            raise ValueError("content_artifact_changed")
    blueprint = source.parent / "blueprint.json"
    # v5 question contracts use English analysis text. Do not rewrite the legacy .env
    # language setting; use a separate worker-specific setting in this child process.
    os.environ["QUESTION_GENERATION_LANGUAGE"] = os.getenv(
        "TOPIC_QUESTION_GENERATION_LANGUAGE", "en-US"
    )
    settings = load_settings(require_api_key=True)
    identity = {
        "sourceSnapshotId": payload["sourceSnapshotId"],
        "contentReportHash": payload["contentReportHash"],
        "generation": generation_identity(blueprint, settings),
        "adapterHash": "sha256:"
        + hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
    }
    output = directory / "questions" / content_hash(identity)[7:31]
    report = generate_concept_batch(blueprint, output, settings, max_new=2)
    report.update(
        {
            "sourceSnapshotId": identity["sourceSnapshotId"],
            "contentReportHash": identity["contentReportHash"],
        }
    )
    save_json(output / "generation-report.json", report)
    return {
        "status": report["status"],
        "slug": payload["slug"],
        "sourceSnapshotId": identity["sourceSnapshotId"],
        "reportPath": str(output / "generation-report.json"),
        "reportHash": file_hash(output / "generation-report.json"),
    }


if __name__ == "__main__":
    try:
        print(json.dumps(main(), ensure_ascii=False))
    except Exception as exc:  # noqa: BLE001 - sanitized worker boundary
        print(
            json.dumps(
                {"error": "question_preparation_failed", "kind": type(exc).__name__}
            )
        )
        sys.exit(1)
