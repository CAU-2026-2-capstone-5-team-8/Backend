#!/usr/bin/env python3
"""Replay the September single-request format using Data-Pipeline's current rules.

Its CLI now requires a larger candidate request. Do not rewrite historical request
metadata to satisfy that plan: validate the original single-request identity here.
No selection heuristics are implemented in this adapter.
"""
import argparse
from pathlib import Path
from data_pipeline.collectors.yes24 import Yes24Collector
from data_pipeline.datasets import merge_datasets
from data_pipeline.normalizers import normalize_yes24_response
from data_pipeline.storage import read_raw_response, write_dataset
from data_pipeline.validation import validate_dataset

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--raw", type=Path, action="append", required=True)
p.add_argument("--output", type=Path, required=True)
p.add_argument("--topic", required=True)
args = p.parse_args()
datasets = []
for path in args.raw:
    a = read_raw_response(path)
    if (a.provider != "yes24" or a.topic != args.topic or "pages" in a.response
            or a.request_parameters != Yes24Collector.legacy_search_parameters(a.topic, a.requested_limit)):
        raise ValueError("unsupported legacy request identity: " + str(path))
    datasets.append(normalize_yes24_response(a.response, topic=a.topic,
                    limit=a.requested_limit, retrieved_at=a.retrieved_at))
dataset = merge_datasets(datasets)
errors = validate_dataset(dataset)
if errors:
    raise ValueError(str(errors))
write_dataset(dataset, args.output)
