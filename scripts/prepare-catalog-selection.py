#!/usr/bin/env python3
"""Replay Data-Pipeline filters, then prepare a complete selection for an imported catalog.

No provider rules live here. Raw responses and existing catalog artifacts are read-only.
New books require a catalog import first; this command never silently drops new identities.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess


def digest(path):
    return "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("source-manifest", "raw-root", "pipeline-root", "pipeline-python", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--pipeline-revision", required=True)
    parser.add_argument("--snapshot-id", required=True)
    parser.add_argument("--legacy-yes24", action="store_true",
                        help="Explicit compatibility replay for old single-request YES24 raw files")
    args = parser.parse_args()
    import re
    if not re.fullmatch(r"[0-9a-f]{40}", args.pipeline_revision):
        parser.error("pipeline revision must be the full commit hash of the supplied checkout")
    source = json.loads(args.source_manifest.read_text())
    if source["contract_version"] != "discovery-catalog-import-v1":
        raise ValueError("expected an imported discovery catalog manifest")
    args.output.mkdir(parents=True, exist_ok=False)
    engine = hashlib.sha256()
    for folder in ("src", "configs"):
        for path in sorted((args.pipeline_root / folder).rglob("*")):
            if path.is_file() and "__pycache__" not in path.parts:
                engine.update(str(path.relative_to(args.pipeline_root)).encode() + b"\0")
                engine.update(path.read_bytes())
    result = {
        "contract_version": "catalog-selection-v1", "snapshot_id": args.snapshot_id,
        "source_snapshot_id": source["snapshot_id"],
        "source_manifest_sha256": digest(args.source_manifest),
        "pipeline_revision": args.pipeline_revision,
        "pipeline_code_sha256": "sha256:" + engine.hexdigest(), "topics": [],
    }
    counts = {}
    env = dict(os.environ, PYTHONPATH=str(args.pipeline_root.resolve() / "src"))
    for batch in source["topics"]:
        topic = batch["topic"]["ml_topic_id"]
        raw = sorted(args.raw_root.glob("*/" + topic + "/*.json"))
        if not raw:
            raise ValueError("missing explicit raw inputs for " + topic)
        original_path = args.source_manifest.parent / batch["books_path"]
        if digest(original_path) != batch["books_sha256"]:
            raise ValueError("source catalog hash mismatch")
        original = {json.loads(line)["book_id"] for line in original_path.read_text().splitlines() if line}
        output = args.output / topic
        command = [str(args.pipeline_python.absolute()), "-m", "data_pipeline.cli", "build"]
        if args.legacy_yes24:
            command = [str(args.pipeline_python.absolute()),
                       str(Path(__file__).with_name("replay-legacy-yes24.py").resolve()), "--topic", topic]
        for path in raw:
            command += ["--raw", str(path.resolve())]
        command += ["--output", str(output.resolve())]
        with (args.output / (topic + ".log")).open("xb") as log:
            subprocess.run(command, cwd=args.pipeline_root, env=env, stdout=log,
                           stderr=subprocess.STDOUT, check=True, timeout=120)
        filtered_path = output / "books.jsonl"
        filtered = {json.loads(line)["book_id"] for line in filtered_path.read_text().splitlines() if line}
        if not filtered <= original:
            raise ValueError("new canonical books must be imported before selection activation")
        result["topics"].append({
            "ml_topic_id": topic, "raw_sha256": [digest(p) for p in raw],
            "filtered_books_sha256": digest(filtered_path),
            "decisions": [{"book_id": book, "included": book in filtered,
                           "reason": "provider_filter_pass" if book in filtered else "provider_filter_rejected"}
                          for book in sorted(original)],
        })
        counts[topic] = {"collected": len(original), "included": len(filtered),
                         "excluded": len(original - filtered)}
    (args.output / "selection-manifest.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    (args.output / "summary.json").write_text(json.dumps(counts, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(counts, ensure_ascii=False))


if __name__ == "__main__":
    main()
