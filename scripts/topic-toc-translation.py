#!/usr/bin/env python3
"""Add stored English TOCs in bounded batches, preserving the collected originals."""

import hashlib
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "Data-Pipeline/src"))

from data_pipeline.english import (
    DEFAULT_MODEL,
    PROMPT_VERSION,
    GeminiTranslator,
    enrich_english,
)


def prepare(source: Path, directory: Path):
    model = os.getenv(
        "TOPIC_TOC_TRANSLATION_MODEL",
        os.getenv("QUESTION_GENERATION_MODEL", DEFAULT_MODEL),
    )
    identity = {
        "version": "topic-toc-english-v1",
        "model": model,
        "promptVersion": PROMPT_VERSION,
        "sourceHashes": {
            name: "sha256:" + hashlib.sha256((source / name).read_bytes()).hexdigest()
            for name in ("books.jsonl", "documents.jsonl", "toc.jsonl", "sources.jsonl")
        },
        "implementationHash": hashlib.sha256(
            (ROOT / "Data-Pipeline/src/data_pipeline/english.py").read_bytes()
        ).hexdigest(),
    }
    key = hashlib.sha256(json.dumps(identity, sort_keys=True).encode()).hexdigest()[:24]
    output = directory / "content-english" / key / "canonical"
    # Two chunks may belong to the same book and therefore run sequentially.
    # Allow for three provider attempts per chunk within the parent's 145s limit.
    translator = GeminiTranslator(os.getenv("GEMINI_API_KEY", ""), model, timeout=20)
    try:
        summary = enrich_english(
            source, output, translator, scope="toc", workers=2, max_new_requests=2
        )
    finally:
        translator.close()
    if summary["failures"]:
        raise ValueError("toc_translation_failed")
    # Recheck immutable inputs before returning a dataset to the matching stage.
    if any(
        "sha256:" + hashlib.sha256((source / name).read_bytes()).hexdigest() != digest
        for name, digest in identity["sourceHashes"].items()
    ):
        raise ValueError("translation_source_changed")
    return {
        # Keep the outline model call out of a worker batch that already made
        # translation calls. The next pass reads the completed cache locally.
        "status": "PREPARING"
        if summary["pending_books"] or summary["api_requests"]
        else "READY",
        "canonicalDataPath": str(output),
        "identity": identity,
        "summaryPath": str(output / "translation-run.json"),
    }


if __name__ == "__main__":
    result = prepare(Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve())
    print(json.dumps(result, ensure_ascii=False))
