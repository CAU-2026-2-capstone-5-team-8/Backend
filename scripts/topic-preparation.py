#!/usr/bin/env python3
"""Private worker adapter: JSON stdin/stdout; no shell, account data, or API keys in output.

Backend resolves names locally to reviewed topic rules. Collection uses
Data-Pipeline provider filters unchanged. This adapter never generates diagnostic readiness.
Run with Data-Pipeline's Python environment and a server-controlled workspace path.
"""

import hashlib
import json
import os
import subprocess
import sys
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime
from pathlib import Path
from uuid import UUID

ROOT = Path(__file__).resolve().parents[2]
PIPELINE = ROOT / "Data-Pipeline"
sys.path.insert(0, str(PIPELINE / "src"))

import httpx
from data_pipeline.collection_scope import (
    CollectionScope,
    collection_scope,
    common_field_scope,
    google_books_parameters,
    merge_scope_catalogs,
    normalize_google_scope_response,
    normalize_scope_response,
    open_library_parameters,
)
from data_pipeline.collectors.base import InvalidProviderResponse
from data_pipeline.collectors.google_books import GoogleBooksCollector
from data_pipeline.collectors.national_library import NationalLibraryCollector
from data_pipeline.collectors.open_library import OpenLibraryCollector
from data_pipeline.collectors.yes24 import Yes24Collector
from data_pipeline.common_fields import common_field, resolve_common_field
from data_pipeline.diagnostics import NormalizationDiagnostics
from data_pipeline.models import CanonicalDataset
from data_pipeline.national_library import normalize_national_library_response
from data_pipeline.normalizers import normalize_yes24_response
from data_pipeline.query_discovery import discover_categories, discovery_policy
from data_pipeline.storage import (
    RawArtifact,
    raw_artifact_path,
    read_dataset,
    read_raw_response,
    write_dataset,
    write_raw_response,
)
from data_pipeline.topics import TOPIC_REGISTRY
from data_pipeline.validation import validate_dataset
from dotenv import load_dotenv


def digest(data):
    return "sha256:" + hashlib.sha256(data).hexdigest()


def encode(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True) + "\n").encode()


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_bytes(encode(value))
    temporary.replace(path)


def pipeline_identity():
    paths = sorted(PIPELINE.glob("src/data_pipeline/**/*.py")) + sorted(
        PIPELINE.glob("configs/*.json")
    )
    code = b"".join(
        str(p.relative_to(PIPELINE)).encode() + b"\0" + p.read_bytes() for p in paths
    )
    code += Path(__file__).read_bytes()
    revision = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=PIPELINE, text=True
    ).strip()
    return revision, digest(code)


def discover_categories_only(payload, workspace):
    identifier = str(UUID(payload["discoveryId"]))
    directory = workspace / "discoveries" / identifier
    directory.mkdir(parents=True, exist_ok=True)
    pointer = directory / "raw-path.json"
    query = payload["name"]
    if pointer.exists():
        artifact = read_raw_response(Path(json.loads(pointer.read_text())["path"]))
        if artifact.request_parameters != Yes24Collector.query_parameters(query, 100):
            raise ValueError("discovery_query_changed")
    else:
        with Yes24Collector() as collector:
            response = collector.search_query(query, candidate_limit=100)
        artifact = RawArtifact(
            provider="yes24",
            topic="query-discovery",
            requested_limit=100,
            retrieved_at=datetime.now(UTC),
            request_parameters=Yes24Collector.query_parameters(query, 100),
            response=response,
        )
        raw_path = raw_artifact_path(directory, artifact)
        write_raw_response(artifact, raw_path)
        save(pointer, {"path": str(raw_path)})
    groups = discover_categories(artifact.response, query, artifact.retrieved_at)
    result = {
        "provider": "yes24",
        "protocolVersion": "common-fields-v1",
        "query": query,
        "groups": groups,
        "status": "FOUND" if groups else "NO_RESULTS",
    }
    save(directory / "result.json", result)
    return result


def discover(payload, workspace):
    field = resolve_common_field(payload["name"])
    if field is None:
        return discover_categories_only(payload, workspace)
    identifier = str(UUID(payload["discoveryId"]))
    directory = workspace / "discoveries" / identifier
    run = directory / field.slug
    run.mkdir(parents=True, exist_ok=True)
    done = directory / "result.json"
    if done.exists():
        previous = json.loads(done.read_text())
        if previous.get("query") != payload["name"]:
            raise ValueError("discovery_query_changed")
        if previous.get("registryHash") != field.registry_hash:
            raise ValueError("common_field_definition_changed")
        return previous
    spec, scope = field.spec(), common_field_scope(field)
    dataset = CanonicalDataset(books=[], documents=[], toc=[], sources=[])
    report, raw_hashes = {}, []
    providers = ("yes24", "open_library", "google_books", "national_library")
    # Independent sources run together; a slow unavailable source cannot consume the
    # combined worker timeout before another source even gets a chance to respond.
    with ThreadPoolExecutor(max_workers=4) as executor:
        futures = {
            provider: executor.submit(common_provider_catalog, field, provider, run)
            for provider in providers
        }
        for provider in providers:
            if provider == "national_library" and not os.environ.get(
                "NL_GO_KR_API_KEY"
            ):
                report[provider] = {"status": "not_configured", "bookCount": 0}
                continue
            try:
                artifact, fresh, decisions = futures[provider].result()
                raw_hashes.append(artifact.content_hash)
                before = len(dataset.books)
                dataset, overlaps = merge_scope_catalogs(dataset, fresh)
                report[provider] = {
                    "status": "collected",
                    "bookCount": len(fresh.books),
                    "addedBookCount": len(dataset.books) - before,
                    "rawSha256": artifact.content_hash,
                }
                save(
                    run / (provider.replace("_", "-") + "-filter-audit.json"),
                    {"decisions": decisions, "overlaps": overlaps},
                )
            except (httpx.HTTPError, InvalidProviderResponse) as exc:
                report[provider] = {
                    "status": "provider_failed",
                    "bookCount": 0,
                    "kind": type(exc).__name__,
                }
                if isinstance(exc, httpx.HTTPStatusError):
                    report[provider]["statusCode"] = exc.response.status_code
    if validate_dataset(dataset):
        raise ValueError("invalid_common_field_dataset")
    write_dataset(dataset, run / "canonical")
    save(run / "provider-report.json", report)
    save(
        run / "discovery-policy.json",
        {
            "version": "common-field-v1",
            "discoveryId": identifier,
            "query": payload["name"],
            "commonFieldId": field.id,
            "registryHash": field.registry_hash,
            "policy": spec.model_dump(mode="json"),
            "collectionScope": scope.to_dict(),
            "rawHashes": raw_hashes,
            "canonicalHashes": {
                name: digest((run / "canonical" / name).read_bytes())
                for name in (
                    "books.jsonl",
                    "documents.jsonl",
                    "toc.jsonl",
                    "sources.jsonl",
                )
            },
            "diagnosisReady": False,
        },
    )
    preview = [b for b in dataset.books if b.language == "ko"][:2] + [
        b for b in dataset.books if b.language == "en"
    ][:2]
    preview += [b for b in dataset.books if b not in preview][: 4 - len(preview)]
    providers = [
        {
            "id": provider,
            "status": item["status"],
            "bookCount": item["bookCount"],
            **({"statusCode": item["statusCode"]} if "statusCode" in item else {}),
        }
        for provider, item in report.items()
    ]
    fields = (
        [
            {
                "id": field.id,
                "slug": field.slug,
                "name": field.name,
                "englishName": field.english_name,
                "parentCode": field.parent_code,
                "parentName": field.domain_name,
                "registryHash": field.registry_hash,
                "bookCount": len(dataset.books),
                "providers": providers,
                "samples": [
                    {"title": b.title, "authors": b.authors, "language": b.language}
                    for b in preview
                ],
            }
        ]
        if dataset.books
        else []
    )
    result = {
        "provider": "common-fields",
        "protocolVersion": "common-fields-v1",
        "query": payload["name"],
        "status": "FOUND" if fields else "NO_RESULTS",
        "groups": [],
        "fields": fields,
        "registryHash": field.registry_hash,
    }
    save(done, result)
    return result


def common_provider_catalog(field, provider, run):
    spec, scope = field.spec(), common_field_scope(field)
    if provider == "national_library":
        if not os.environ.get("NL_GO_KR_API_KEY"):
            return None
        artifact, _ = collect_foreign_artifact(
            provider,
            NationalLibraryCollector,
            NationalLibraryCollector.query_parameters(field.korean_query),
            run,
            run,
            spec.slug,
            fresh=True,
        )
        fresh, decisions = normalize_national_library_response(
            artifact.response, scope, spec, artifact.retrieved_at
        )
        return artifact, fresh, decisions
    if provider == "yes24":
        artifact = common_domestic_artifact(field, run)
        diagnostics = NormalizationDiagnostics(provider="yes24")
        fresh = normalize_yes24_response(
            artifact.response,
            topic=spec.slug,
            limit=20,
            retrieved_at=artifact.retrieved_at,
            diagnostics=diagnostics,
            discovery_spec=spec,
        )
        decisions = vars(diagnostics) | {
            "edition_mismatch_book_ids": sorted(diagnostics.edition_mismatch_book_ids)
        }
    else:
        collector, parameters, normalize = {
            "open_library": (
                OpenLibraryCollector,
                open_library_parameters,
                normalize_scope_response,
            ),
            "google_books": (
                GoogleBooksCollector,
                google_books_parameters,
                normalize_google_scope_response,
            ),
        }[provider]
        artifact, _ = collect_foreign_artifact(
            provider,
            collector,
            parameters(scope),
            run,
            run,
            spec.slug,
            fresh=True,
        )
        fresh, decisions = normalize(
            artifact.response, scope, spec, artifact.retrieved_at
        )
    return artifact, fresh, decisions


def common_domestic_artifact(field, run):
    pointer = run / "raw-path.json"
    parameters = Yes24Collector.query_parameters(field.korean_query, 100)
    if pointer.exists():
        artifact = read_raw_response(Path(json.loads(pointer.read_text())["path"]))
        if artifact.provider != "yes24" or artifact.request_parameters != parameters:
            raise ValueError("common_domestic_scope_changed")
        return artifact
    with Yes24Collector() as collector:
        response = collector.search_query(field.korean_query, candidate_limit=100)
    artifact = RawArtifact(
        provider="yes24",
        topic=field.slug,
        requested_limit=100,
        retrieved_at=datetime.now(UTC),
        request_parameters=parameters,
        response=response,
    )
    path = raw_artifact_path(run, artifact)
    write_raw_response(artifact, path)
    save(pointer, {"path": str(path)})
    return artifact


def collect_common_field(payload, directory):
    selected = payload["discovery"]
    field = common_field(selected["commonFieldId"])
    if (
        payload["slug"] != field.slug
        or payload["parentCode"] != field.parent_code
        or payload["parentName"] != field.domain_name
        or selected["registryHash"] != field.registry_hash
    ):
        raise ValueError("common_field_selection_changed")
    origin = directory.parent / "discoveries" / str(UUID(selected["id"])) / field.slug
    policy = json.loads((origin / "discovery-policy.json").read_text())
    if (
        policy["query"] != selected["query"]
        or policy["commonFieldId"] != field.id
        or policy["registryHash"] != field.registry_hash
        or policy["policy"] != field.spec().model_dump(mode="json")
    ):
        raise ValueError("common_field_evidence_changed")
    for filename, expected in policy["canonicalHashes"].items():
        if (
            filename
            not in {"books.jsonl", "documents.jsonl", "toc.jsonl", "sources.jsonl"}
            or digest((origin / "canonical" / filename).read_bytes()) != expected
        ):
            raise ValueError("common_field_canonical_changed")
    dataset = read_dataset(origin / "canonical")
    report = json.loads((origin / "provider-report.json").read_text())
    # Bind the selected evidence to immutable original responses, not client counts.
    for provider, result in report.items():
        if result["status"] == "collected":
            pointer = origin / (
                "raw-path.json"
                if provider == "yes24"
                else provider.replace("_", "-") + "-raw-path.json"
            )
            artifact = read_raw_response(Path(json.loads(pointer.read_text())["path"]))
            if artifact.content_hash != result["rawSha256"]:
                raise ValueError("common_field_raw_changed")
    revision, code_hash = pipeline_identity()
    run = directory / (field.slug + "-" + code_hash[7:23])
    done = run / "result.json"
    if done.exists():
        return json.loads(done.read_text())
    run.mkdir(parents=True, exist_ok=True)
    save(run / "provider-report.json", report)
    save(run / "discovery-policy.json", policy)
    save(run / "collection-scope.json", common_field_scope(field).to_dict())
    result = write_handoff(
        payload | {"name": field.name},
        run,
        dataset,
        policy["rawHashes"],
        revision,
        code_hash,
        f"book-search-{payload['requestId']}-{field.slug}-{code_hash[7:19]}",
    )
    save(done, result)
    return result


def collect_foreign_artifact(
    provider, collector_class, parameters, run, directory, slug, *, fresh=False
):
    """Reuse compatible original responses across code versions of the same request.

    Parameters contain no credentials. A new user request uses a different directory;
    this is replay, not a shared result cache or a claim of current provider freshness.
    """
    filename = provider.replace("_", "-") + "-raw-path.json"
    pointer = run / filename
    if pointer.exists():
        artifact = read_raw_response(Path(json.loads(pointer.read_text())["path"]))
        if (
            artifact.provider != provider
            or artifact.topic != slug
            or artifact.request_parameters != parameters
        ):
            raise ValueError("foreign_scope_changed")
        return artifact, "current_run"
    for prior in (
        [] if fresh else sorted(directory.glob(slug + "-*/" + filename), reverse=True)
    ):
        artifact = read_raw_response(Path(json.loads(prior.read_text())["path"]))
        if (
            artifact.provider == provider
            and artifact.topic == slug
            and artifact.request_parameters == parameters
        ):
            save(pointer, json.loads(prior.read_text()))
            return artifact, "previous_run"
    key_name = {
        "google_books": "GOOGLE_BOOKS_API_KEY",
        "national_library": "NL_GO_KR_API_KEY",
    }.get(provider)
    kwargs = {"api_key": os.environ.get(key_name)} if key_name else {}
    with collector_class(**kwargs) as collector:
        response = collector.search_scope(parameters)
    artifact = RawArtifact(
        provider=provider,
        topic=slug,
        requested_limit=30,
        retrieved_at=datetime.now(UTC),
        request_parameters=parameters,
        response=response,
    )
    path = raw_artifact_path(run, artifact)
    write_raw_response(artifact, path)
    save(pointer, {"path": str(path)})
    return artifact, "fetched"


def collect(payload, directory):
    slug = payload["slug"]
    discovery = payload.get("discovery")
    if discovery and discovery.get("commonFieldId"):
        return collect_common_field(payload, directory)
    if discovery:
        spec = discovery_policy(discovery["query"], discovery["category"])
        if (
            spec.slug != slug
            or "SRC-" + spec.domain[9:] != payload["parentCode"]
            or discovery["category"].split("-")[1] != payload["parentName"]
        ):
            raise ValueError("discovery_scope_mismatch")
    else:
        spec = TOPIC_REGISTRY[slug]
        if spec.domain != {"CS": "computer-science", "MAT": "mathematics"}.get(
            payload["parentCode"]
        ):
            raise ValueError("category_mismatch")
    revision, code_hash = pipeline_identity()
    run = directory / (slug + "-" + code_hash[7:23])
    done = run / "result.json"
    if done.exists():
        return json.loads(done.read_text())
    run.mkdir(parents=True, exist_ok=True)
    raw_pointer = run / "raw-path.json"
    if raw_pointer.exists():
        artifact = read_raw_response(Path(json.loads(raw_pointer.read_text())["path"]))
    elif discovery:
        origin = (
            directory.parent
            / "discoveries"
            / str(UUID(discovery["id"]))
            / "raw-path.json"
        )
        artifact = read_raw_response(Path(json.loads(origin.read_text())["path"]))
        if artifact.request_parameters != Yes24Collector.query_parameters(
            discovery["query"], 100
        ):
            raise ValueError("discovery_query_changed")
        save(raw_pointer, json.loads(origin.read_text()))
    else:
        with Yes24Collector() as collector:
            response = collector.search_books(slug, candidate_limit=100)
        artifact = RawArtifact(
            provider="yes24",
            topic=slug,
            requested_limit=20,
            retrieved_at=datetime.now(UTC),
            request_parameters=Yes24Collector.search_parameters(slug, 100),
            response=response,
        )
        raw_path = raw_artifact_path(run, artifact)
        write_raw_response(artifact, raw_path)
        save(raw_pointer, {"path": str(raw_path)})
    diagnostics = NormalizationDiagnostics(provider="yes24")
    dataset = normalize_yes24_response(
        artifact.response,
        topic=slug,
        limit=20,
        retrieved_at=artifact.retrieved_at,
        diagnostics=diagnostics,
        discovery_spec=spec if discovery else None,
    )
    raw_hashes = [artifact.content_hash]
    provider_report = {
        "yes24": {"status": "collected", "bookCount": len(dataset.books)}
    }
    if discovery:
        scope = collection_scope(discovery["query"], discovery["category"])
        save(run / "collection-scope.json", scope.to_dict())
        providers = [
            (
                "open_library",
                OpenLibraryCollector,
                open_library_parameters,
                normalize_scope_response,
            ),
            (
                "google_books",
                GoogleBooksCollector,
                google_books_parameters,
                normalize_google_scope_response,
            ),
        ]
        for provider, collector_class, query_builder, normalize in providers:
            foreign_report = {"status": scope.foreign_status, "bookCount": 0}
            if scope.foreign_status == "ready":
                parameters = query_builder(scope)
                try:
                    foreign_artifact, replay = collect_foreign_artifact(
                        provider,
                        collector_class,
                        parameters,
                        run,
                        directory,
                        slug,
                    )
                    raw_hashes.append(foreign_artifact.content_hash)
                    foreign, decisions = normalize(
                        foreign_artifact.response,
                        scope,
                        spec,
                        foreign_artifact.retrieved_at,
                    )
                    before_merge = len(dataset.books)
                    dataset, overlaps = merge_scope_catalogs(dataset, foreign)
                    save(
                        run / (provider.replace("_", "-") + "-filter-audit.json"),
                        {"decisions": decisions, "overlaps": overlaps},
                    )
                    foreign_report = {
                        "status": "collected",
                        "bookCount": len(foreign.books),
                        "addedBookCount": len(dataset.books) - before_merge,
                        "rawSha256": foreign_artifact.content_hash,
                        "rawReuse": replay,
                    }
                except (httpx.HTTPError, InvalidProviderResponse) as exc:
                    # Failures are isolated: another source's successfully collected books survive.
                    foreign_report = {
                        "status": "provider_failed",
                        "bookCount": 0,
                        "kind": type(exc).__name__,
                    }
                    if isinstance(exc, httpx.HTTPStatusError):
                        foreign_report["statusCode"] = exc.response.status_code
            provider_report[provider] = foreign_report
        save(run / "provider-report.json", provider_report)
        save(
            run / "discovery-policy.json",
            {
                "discoveryId": discovery["id"],
                "query": discovery["query"],
                "category": discovery["category"],
                "policy": spec.model_dump(mode="json"),
                "collectionScope": scope.to_dict(),
                "rawSha256": artifact.content_hash,
                "rawPath": json.loads(raw_pointer.read_text())["path"],
                "diagnosisReady": False,
            },
        )
    # Keep rejected candidates/counts and raw evidence even when nothing is publishable.
    if not discovery:
        scope = CollectionScope(
            query=spec.korean_query or payload["name"],
            source_provider="common",
            source_category="",
            subject=spec.slug,
            english_query=None,
            evidence_pattern=None,
            book_kind=None,
            audience=None,
            mapping_basis="reviewed_topic",
            foreign_status="unmapped_language",
        )
    if os.environ.get("NL_GO_KR_API_KEY"):
        try:
            nl_artifact, replay = collect_foreign_artifact(
                "national_library",
                NationalLibraryCollector,
                NationalLibraryCollector.query_parameters(scope.query),
                run,
                directory,
                slug,
            )
            extra, audit = normalize_national_library_response(
                nl_artifact.response, scope, spec, nl_artifact.retrieved_at
            )
            previous_count = len(dataset.books)
            dataset, overlaps = merge_scope_catalogs(dataset, extra)
            raw_hashes.append(nl_artifact.content_hash)
            provider_report["national_library"] = {
                "status": "collected",
                "bookCount": len(extra.books),
                "addedBookCount": len(dataset.books) - previous_count,
                "rawSha256": nl_artifact.content_hash,
                "rawReuse": replay,
            }
            save(
                run / "national-library-filter-audit.json",
                {"decisions": audit, "overlaps": overlaps},
            )
        except (httpx.HTTPError, InvalidProviderResponse) as exc:
            provider_report["national_library"] = {
                "status": "provider_failed",
                "bookCount": 0,
                "kind": type(exc).__name__,
            }
            if isinstance(exc, httpx.HTTPStatusError):
                provider_report["national_library"]["statusCode"] = (
                    exc.response.status_code
                )
    else:
        provider_report["national_library"] = {
            "status": "not_configured",
            "bookCount": 0,
        }
    save(run / "provider-report.json", provider_report)
    save(
        run / "filter-audit.json",
        vars(diagnostics)
        | {"edition_mismatch_book_ids": sorted(diagnostics.edition_mismatch_book_ids)},
    )
    prefix = "book-search" if discovery else "topic-request"
    snapshot = f"{prefix}-{payload['requestId']}-{slug}-{code_hash[7:19]}"
    result = write_handoff(
        payload | {"name": spec.korean_query},
        run,
        dataset,
        raw_hashes,
        revision,
        code_hash,
        snapshot,
    )
    save(done, result)
    return result


def write_handoff(payload, run, dataset, raw_hashes, revision, code_hash, snapshot):
    slug = payload["slug"]
    if not dataset.books:
        raise ValueError("no_eligible_books")
    errors = validate_dataset(dataset)
    if errors:
        raise ValueError("invalid_canonical_dataset")
    write_dataset(dataset, run / "canonical")
    # Additive English-analysis fields remain in canonical. The current strict backend
    # contract receives a separate bibliographic projection, not a modified source.
    handoff = run / "handoff"
    handoff.mkdir(exist_ok=True)
    keys = {
        "book_id",
        "isbn_10",
        "isbn_13",
        "title",
        "subtitle",
        "authors",
        "publisher",
        "published_year",
        "language",
        "topics",
    }
    (handoff / "books.jsonl").write_bytes(
        b"".join(
            encode({k: v for k, v in b.model_dump(mode="json").items() if k in keys})
            for b in dataset.books
        )
    )
    for name in ("documents.jsonl", "toc.jsonl", "sources.jsonl"):
        (handoff / name).write_bytes((run / "canonical" / name).read_bytes())
    toc = Counter(t.book_id for t in dataset.toc)
    descriptions = Counter(
        d.book_id for d in dataset.documents if d.document_type == "description"
    )
    sources = Counter(s.book_id for s in dataset.sources)
    evidence = [
        {
            "book_id": b.book_id,
            "toc_entry_count": toc[b.book_id],
            "description_count": descriptions[b.book_id],
            "source_count": sources[b.book_id],
        }
        for b in dataset.books
    ]
    (handoff / "coverage.jsonl").write_bytes(b"".join(encode(row) for row in evidence))
    hashes = {
        name: digest((handoff / name).read_bytes())
        for name in ("books.jsonl", "documents.jsonl", "toc.jsonl", "sources.jsonl")
    }
    manifest = {
        "contract_version": "discovery-catalog-import-v1",
        "snapshot_id": snapshot,
        "accepted_projection_sources": [],
        "topics": [
            {
                "topic": {
                    "code": "AUTO-" + slug,
                    "name": payload["name"],
                    "parent_code": payload["parentCode"],
                    "parent_name": payload["parentName"],
                    "ml_topic_id": slug,
                },
                "books_path": "books.jsonl",
                "books_sha256": hashes["books.jsonl"],
                "evidence_path": "coverage.jsonl",
                "evidence_sha256": digest((handoff / "coverage.jsonl").read_bytes()),
                "candidates_path": None,
                "candidates_sha256": None,
                "canonical_hashes": hashes,
            }
        ],
    }
    save(handoff / "import.json", manifest)
    save(
        handoff / "selection.json",
        {
            "contract_version": "catalog-selection-v1",
            "snapshot_id": snapshot + "-selected",
            "source_snapshot_id": snapshot,
            "source_manifest_sha256": digest((handoff / "import.json").read_bytes()),
            "pipeline_revision": revision,
            "pipeline_code_sha256": code_hash,
            "topics": [
                {
                    "ml_topic_id": slug,
                    "raw_sha256": raw_hashes,
                    "filtered_books_sha256": hashes["books.jsonl"],
                    "decisions": [
                        {
                            "book_id": b.book_id,
                            "included": True,
                            "reason": "provider_filter_pass",
                        }
                        for b in dataset.books
                    ],
                }
            ],
        },
    )
    result = {
        "status": "COLLECTED",
        "slug": slug,
        "bookCount": len(dataset.books),
        "importManifest": str(handoff / "import.json"),
        "selectionManifest": str(handoff / "selection.json"),
    }
    return result


def catalog_baseline(payload, workspace):
    """Only replay a private artifact matching the active database selection and hashes."""
    paths = list(workspace.glob("request-*/*/handoff/selection.json"))
    paths += list(workspace.glob("catalog-refresh/*/handoff/selection.json"))
    for selection_path in paths:
        selected = json.loads(selection_path.read_text())
        if selected["snapshot_id"] != payload["baselineSelection"]:
            continue
        if digest(selection_path.read_bytes()) != payload["baselineSelectionHash"]:
            raise ValueError("baseline_selection_changed")
        manifest_path = selection_path.with_name("import.json")
        if digest(manifest_path.read_bytes()) != selected["source_manifest_sha256"]:
            raise ValueError("baseline_manifest_changed")
        manifest = json.loads(manifest_path.read_text())
        if manifest["snapshot_id"] != selected["source_snapshot_id"]:
            raise ValueError("baseline_source_changed")
        if len(manifest["topics"]) != 1 or len(selected["topics"]) != 1:
            raise ValueError("baseline_topics_changed")
        topic = manifest["topics"][0]
        if (
            topic["topic"]["ml_topic_id"] != payload["slug"]
            or topic["topic"]["parent_code"] != payload["parentCode"]
            or selected["topics"][0]["ml_topic_id"] != payload["slug"]
        ):
            raise ValueError("baseline_topic_changed")
        run = selection_path.parents[1]
        policy_path = run / "discovery-policy.json"
        if not policy_path.exists():
            raise ValueError("baseline_scope_missing")
        policy = json.loads(policy_path.read_text())
        if policy.get("commonFieldId"):
            field = common_field(policy["commonFieldId"])
            spec, parent_code = field.spec(), field.parent_code
            if policy["registryHash"] != field.registry_hash or policy[
                "policy"
            ] != spec.model_dump(mode="json"):
                raise ValueError("baseline_common_field_changed")
        else:
            spec = discovery_policy(policy["query"], policy["category"])
            parent_code = "SRC-" + spec.domain[9:]
        if spec.slug != payload["slug"] or parent_code != payload["parentCode"]:
            raise ValueError("baseline_scope_changed")
        # Canonical analysis fields are additive; compare its bibliographic projection.
        dataset = read_dataset(run / "canonical")
        keys = set(
            json.loads(
                (selection_path.parent / "books.jsonl").read_text().splitlines()[0]
            )
        )
        projected = b"".join(
            encode({k: v for k, v in book.model_dump(mode="json").items() if k in keys})
            for book in dataset.books
        )
        if digest(projected) != topic["canonical_hashes"]["books.jsonl"]:
            raise ValueError("baseline_books_changed")
        for filename in ("documents.jsonl", "toc.jsonl", "sources.jsonl"):
            if (
                digest((run / "canonical" / filename).read_bytes())
                != topic["canonical_hashes"][filename]
            ):
                raise ValueError("baseline_evidence_changed")
        decisions = selected["topics"][0]["decisions"]
        if {d["book_id"] for d in decisions if d["included"]} != {
            b.book_id for b in dataset.books
        }:
            raise ValueError("baseline_selection_incomplete")
        if validate_dataset(dataset):
            raise ValueError("invalid_baseline")
        return run, policy, spec, selected, dataset
    raise ValueError("baseline_not_available")


def catalog_state(payload, workspace):
    try:
        run, _, _, _, dataset = catalog_baseline(payload, workspace)
    except (ValueError, OSError, KeyError, IndexError):
        return {"available": False, "providers": {}}
    report = json.loads((run / "provider-report.json").read_text())
    return {"available": True, "bookCount": len(dataset.books), "providers": report}


def refresh_catalog(payload, workspace):
    identifier = str(UUID(payload["refreshId"]))
    run = workspace / "catalog-refresh" / identifier
    if (run / "result.json").exists():
        result = json.loads((run / "result.json").read_text())
        if result["baselineSelection"] != payload["baselineSelection"]:
            raise ValueError("refresh_baseline_changed")
        return result
    baseline, policy, spec, selected, dataset = catalog_baseline(payload, workspace)
    requested = payload["providers"]
    if (
        not requested
        or len(set(requested)) != len(requested)
        or not set(requested)
        <= {"yes24", "open_library", "google_books", "national_library"}
    ):
        raise ValueError("invalid_refresh_providers")
    run.mkdir(parents=True, exist_ok=True)
    scope = (
        common_field_scope(common_field(policy["commonFieldId"]))
        if policy.get("commonFieldId")
        else collection_scope(policy["query"], policy["category"])
    )
    domestic_query = (
        spec.korean_query if policy.get("commonFieldId") else policy["query"]
    )
    report = json.loads((baseline / "provider-report.json").read_text())
    raw_hashes = list(selected["topics"][0]["raw_sha256"])
    before = len(dataset.books)
    for provider in requested:
        if provider == "national_library" and not os.environ.get("NL_GO_KR_API_KEY"):
            report[provider] = {"status": "not_configured", "bookCount": 0}
            continue
        if (
            provider not in {"yes24", "national_library"}
            and scope.foreign_status != "ready"
        ):
            report[provider] = {"status": scope.foreign_status, "bookCount": 0}
            continue
        try:
            if provider == "yes24":
                pointer = run / "raw-path.json"
                if pointer.exists():
                    artifact = read_raw_response(
                        Path(json.loads(pointer.read_text())["path"])
                    )
                else:
                    with Yes24Collector() as collector:
                        response = collector.search_query(
                            domestic_query, candidate_limit=100
                        )
                    artifact = RawArtifact(
                        provider="yes24",
                        topic="query-discovery",
                        requested_limit=100,
                        retrieved_at=datetime.now(UTC),
                        request_parameters=Yes24Collector.query_parameters(
                            domestic_query, 100
                        ),
                        response=response,
                    )
                    path = raw_artifact_path(run, artifact)
                    write_raw_response(artifact, path)
                    save(pointer, {"path": str(path)})
                if artifact.request_parameters != Yes24Collector.query_parameters(
                    domestic_query, 100
                ):
                    raise ValueError("refresh_query_changed")
                diagnostics = NormalizationDiagnostics(provider="yes24")
                fresh = normalize_yes24_response(
                    artifact.response,
                    topic=spec.slug,
                    limit=20,
                    retrieved_at=artifact.retrieved_at,
                    diagnostics=diagnostics,
                    discovery_spec=spec,
                )
                audit = vars(diagnostics) | {
                    "edition_mismatch_book_ids": sorted(
                        diagnostics.edition_mismatch_book_ids
                    )
                }
            elif provider == "national_library":
                artifact, _ = collect_foreign_artifact(
                    provider,
                    NationalLibraryCollector,
                    NationalLibraryCollector.query_parameters(domestic_query),
                    run,
                    run,
                    spec.slug,
                    fresh=True,
                )
                fresh, audit = normalize_national_library_response(
                    artifact.response, scope, spec, artifact.retrieved_at
                )
            else:
                collector, parameters, normalize = {
                    "open_library": (
                        OpenLibraryCollector,
                        open_library_parameters,
                        normalize_scope_response,
                    ),
                    "google_books": (
                        GoogleBooksCollector,
                        google_books_parameters,
                        normalize_google_scope_response,
                    ),
                }[provider]
                artifact, _ = collect_foreign_artifact(
                    provider,
                    collector,
                    parameters(scope),
                    run,
                    run,
                    spec.slug,
                    fresh=True,
                )
                fresh, audit = normalize(
                    artifact.response, scope, spec, artifact.retrieved_at
                )
            old_count = len(dataset.books)
            dataset, overlaps = merge_scope_catalogs(dataset, fresh)
            raw_hashes.append(artifact.content_hash)
            save(
                run / (provider.replace("_", "-") + "-filter-audit.json"),
                {"decisions": audit, "overlaps": overlaps},
            )
            report[provider] = {
                "status": "collected",
                "bookCount": len(fresh.books),
                "addedBookCount": len(dataset.books) - old_count,
                "updatedAt": artifact.retrieved_at.isoformat(),
                "rawSha256": artifact.content_hash,
            }
        except (httpx.HTTPError, InvalidProviderResponse) as exc:
            report[provider] = {
                "status": "provider_failed",
                "bookCount": 0,
                "kind": type(exc).__name__,
            }
            if isinstance(exc, httpx.HTTPStatusError):
                report[provider]["statusCode"] = exc.response.status_code
    save(run / "provider-report.json", report)
    save(run / "discovery-policy.json", policy)
    save(run / "collection-scope.json", scope.to_dict())
    revision, code_hash = pipeline_identity()
    snapshot = "book-search-refresh-" + identifier
    result = write_handoff(
        payload
        | {
            "name": spec.korean_query
            if policy.get("commonFieldId")
            else policy["query"]
        },
        run,
        dataset,
        sorted(set(raw_hashes)),
        revision,
        code_hash,
        snapshot,
    )
    result.update(
        baselineSelection=payload["baselineSelection"],
        providers=report,
        addedBookCount=len(dataset.books) - before,
        refreshedProviders=requested,
    )
    save(run / "result.json", result)
    return result


def main():
    load_dotenv(PIPELINE / ".env", override=False)
    load_dotenv(ROOT / "Question-Generation" / ".env", override=False)
    payload = json.load(sys.stdin)
    request_id = int(payload["requestId"])
    if request_id == 0 or (
        request_id < 0 and payload["action"] not in {"concepts", "questions", "review"}
    ):
        raise ValueError("invalid_request_id")
    workspace = Path(sys.argv[1]).resolve()
    if payload["action"] == "discover":
        return discover(payload, workspace)
    if payload["action"] == "catalog-state":
        return catalog_state(payload, workspace)
    if payload["action"] == "catalog-refresh":
        return refresh_catalog(payload, workspace)
    directory = workspace / f"request-{request_id}"
    if payload["action"] in {"concepts", "questions", "review"}:
        # Component dependencies stay isolated: ML's environment owns canonical matching.
        component, script = {
            "concepts": ("ML", "topic-content-preparation.py"),
            "questions": ("Question-Generation", "topic-question-preparation.py"),
            "review": ("Question-Generation", "topic-question-review.py"),
        }[payload["action"]]
        result = subprocess.run(
            [
                str(ROOT / component / ".venv/bin/python"),
                str(Path(__file__).with_name(script)),
                sys.argv[1],
            ],
            input=json.dumps(payload),
            capture_output=True,
            text=True,
            timeout=165,
            check=True,
        )
        return json.loads(result.stdout)
    if payload["action"] != "collect":
        raise ValueError("unsupported_action")
    return collect(payload, directory)


if __name__ == "__main__":
    try:
        print(json.dumps(main(), ensure_ascii=False))
    except Exception as exc:  # noqa: BLE001 - sanitize every provider error at the process boundary
        # Provider/SDK exceptions may include URLs. Never reflect them to users/logs.
        print(json.dumps({"error": "preparation_failed", "kind": type(exc).__name__}))
        sys.exit(1)
