#!/usr/bin/env python3
"""Stage selected existing books for the normal preparation worker without collecting again.

Read-only originals must match the imported source and selected snapshot. A separate
local Java command applies this plan with database baseline/identity checks.
"""

import argparse
import importlib.util
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "Data-Pipeline/src"))
from data_pipeline.datasets import without_books
from data_pipeline.storage import read_dataset

spec = importlib.util.spec_from_file_location(
    "collection_adapter", Path(__file__).with_name("topic-preparation.py")
)
adapter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(adapter)


def prepare(source_path, selection_path, canonical_root, topics, output):
    source = json.loads(source_path.read_text())
    selected = json.loads(selection_path.read_text())
    source_hash = adapter.digest(source_path.read_bytes())
    if (
        source["contract_version"] != "discovery-catalog-import-v1"
        or selected["source_snapshot_id"] != source["snapshot_id"]
        or selected["source_manifest_sha256"] != source_hash
    ):
        raise ValueError("source_selection_mismatch")
    if not topics or len(topics) != len(set(topics)):
        raise ValueError("explicit_unique_topics_required")
    batches = {b["topic"]["ml_topic_id"]: b for b in source["topics"]}
    selections = {b["ml_topic_id"]: b for b in selected["topics"]}
    datasets = {}
    for slug in topics:
        batch = batches[slug]
        canonical = canonical_root / slug / "processed"
        for name, expected in batch["canonical_hashes"].items():
            if adapter.digest((canonical / name).read_bytes()) != expected:
                raise ValueError("canonical_source_modified")
        dataset = read_dataset(canonical)
        ids = {b.book_id for b in dataset.books}
        decisions = selections[slug]["decisions"]
        if len(decisions) != len(ids) or {d["book_id"] for d in decisions} != ids:
            raise ValueError("selection_members_differ")
        included = {d["book_id"] for d in decisions if d["included"]}
        datasets[slug] = without_books(dataset, ids - included)
    revision, code_hash = adapter.pipeline_identity()
    identity = adapter.digest(
        adapter.encode(
            {
                "source": source_hash,
                "selection": adapter.digest(selection_path.read_bytes()),
                "topics": sorted(topics),
                "code": code_hash,
            }
        )
    )
    output.mkdir(parents=True, exist_ok=False)
    plan = {"contractVersion": "existing-catalog-preparation-v1", "topics": []}
    for slug in topics:
        topic = batches[slug]["topic"]
        run = output / slug
        run.mkdir()
        snapshot = "catalog-backfill-" + slug + "-" + identity[7:19]
        result = adapter.write_handoff(
            {
                "slug": slug,
                "name": topic["name"],
                "topicCode": topic["code"],
                "parentCode": topic["parent_code"],
                "parentName": topic["parent_name"],
            },
            run,
            datasets[slug],
            selections[slug]["raw_sha256"],
            revision,
            code_hash,
            snapshot,
        )
        plan["topics"].append(
            {
                "slug": slug,
                "baselineSelection": selected["snapshot_id"],
                "baselineHash": adapter.digest(selection_path.read_bytes()),
                "importManifest": str(
                    Path(result["importManifest"]).relative_to(output)
                ),
                "selectionManifest": str(
                    Path(result["selectionManifest"]).relative_to(output)
                ),
            }
        )
    adapter.save(output / "plan.json", plan)
    return {
        "plan": str(output / "plan.json"),
        "books": {slug: len(d.books) for slug, d in datasets.items()},
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in (
        "source-manifest",
        "selection-manifest",
        "canonical-topics-dir",
        "output",
    ):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--topic", action="append", required=True)
    args = parser.parse_args()
    print(
        json.dumps(
            prepare(
                args.source_manifest,
                args.selection_manifest,
                args.canonical_topics_dir,
                args.topic,
                args.output.resolve(),
            ),
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
