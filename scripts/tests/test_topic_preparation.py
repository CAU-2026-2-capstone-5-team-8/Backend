"""Offline protocol/evidence tests; synthetic responses do not validate AI accuracy."""

import importlib.util
import io
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "topic_preparation", Path(__file__).resolve().parents[1] / "topic-preparation.py"
)
adapter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(adapter)


class PreparationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.payload = {
            "requestId": 1,
            "name": "컴퓨터 네트워크",
            "scope": "",
            "parentCode": "CS",
            "parentName": "컴퓨터과학",
            "slug": "computer-networks",
        }
        self.env = patch.dict(
            os.environ, {"YES24_API_KEY": "synthetic", "NL_GO_KR_API_KEY": ""}
        )
        self.env.start()
        self.addCleanup(self.env.stop)
        google = patch.object(
            adapter.GoogleBooksCollector, "search_scope", return_value={"items": []}
        )
        self.google_search = google.start()
        self.addCleanup(google.stop)

    def test_resolution_registry_matches_collection_policy(self):
        path = adapter.ROOT / "Backend/src/main/resources/topic-name-resolution.json"
        registry = json.loads(path.read_text())
        self.assertEqual(
            {r["slug"] for r in registry["topics"]}, set(adapter.TOPIC_REGISTRY)
        )
        domains = {"CS": "computer-science", "MAT": "mathematics"}
        for row in registry["topics"]:
            policy = adapter.TOPIC_REGISTRY[row["slug"]]
            self.assertEqual(policy.domain, domains[row["parentCode"]])
            self.assertEqual(policy.korean_query, row["name"])

    def test_legacy_external_resolution_action_is_disabled(self):
        payload = self.payload | {"action": "resolve"}
        with (
            patch.object(adapter.sys, "stdin", io.StringIO(json.dumps(payload))),
            patch.object(adapter.sys, "argv", ["adapter", str(self.directory)]),
            patch.object(adapter, "load_dotenv"),
            self.assertRaisesRegex(ValueError, "unsupported_action"),
        ):
            adapter.main()

    def test_new_query_discovery_selection_reuses_original_response_without_gemini(
        self,
    ):
        query = {"name": "경제", "discoveryId": "00000000-0000-0000-0000-000000000001"}
        response = self.response("경제학 입문")
        response["data"]["items"][0]["goodsSortNm"] = "국내도서-경제 경영"
        with (
            patch.object(
                adapter.Yes24Collector, "search_query", return_value=response
            ) as search,
            patch.object(
                adapter.OpenLibraryCollector,
                "search_scope",
                return_value={
                    "docs": [
                        {
                            "key": "/works/OL1W",
                            "title": "Economics",
                            "author_name": ["Author"],
                            "subject": ["Economics"],
                            "editions": {
                                "docs": [
                                    {
                                        "key": "/books/OL1M",
                                        "title": "Economics",
                                        "language": ["eng"],
                                        "isbn": ["9780134853987"],
                                    }
                                ]
                            },
                        }
                    ]
                },
            ) as foreign_search,
        ):
            found = adapter.discover_categories_only(query, self.directory)
            self.assertEqual(found["status"], "FOUND")
            self.assertEqual(
                adapter.discover_categories_only(query, self.directory), found
            )
            policy = adapter.discovery_policy("경제", "국내도서-경제 경영")
            scope = {
                "id": query["discoveryId"],
                "query": "경제",
                "category": "국내도서-경제 경영",
            }
            payload = self.payload | {
                "name": "경제",
                "slug": policy.slug,
                "parentCode": "SRC-" + policy.domain[9:],
                "parentName": "경제 경영",
                "discovery": scope,
            }
            result = adapter.collect(payload, self.directory / "request-1")
            self.assertEqual(
                adapter.collect(payload, self.directory / "request-1"), result
            )
            search.assert_called_once()
            foreign_search.assert_called_once()
        manifest = json.loads(Path(result["importManifest"]).read_text())
        self.assertTrue(manifest["snapshot_id"].startswith("book-search-"))
        self.assertEqual(manifest["topics"][0]["topic"]["parent_name"], "경제 경영")
        self.assertIsNone(manifest["topics"][0]["candidates_path"])
        run = Path(result["importManifest"]).parents[1]
        common = json.loads((run / "collection-scope.json").read_text())
        self.assertEqual(common["english_query"], "economics")
        self.assertEqual(common["source_category"], "국내도서-경제 경영")
        self.assertEqual(result["bookCount"], 2)
        selection = json.loads((run / "handoff/selection.json").read_text())
        self.assertEqual(len(selection["topics"][0]["raw_sha256"]), 3)

    def test_google_overlap_keeps_both_sources_and_description(self):
        original = self.google_search.return_value
        self.google_search.return_value = {
            "items": [
                {
                    "id": "economics",
                    "volumeInfo": {
                        "title": "Economics",
                        "language": "en",
                        "authors": ["Author"],
                        "categories": ["Business & Economics"],
                        "industryIdentifiers": [
                            {"type": "ISBN_13", "identifier": "9780134853987"}
                        ],
                        "description": "Public catalog description",
                    },
                }
            ]
        }
        self.test_new_query_discovery_selection_reuses_original_response_without_gemini()
        self.google_search.assert_called_once()
        reports = list(self.directory.glob("request-1/*/provider-report.json"))
        report = json.loads(reports[0].read_text())
        self.assertEqual(report["google_books"]["bookCount"], 1)
        self.assertEqual(report["google_books"]["addedBookCount"], 0)
        canonical = reports[0].parent / "canonical"
        self.assertEqual(len((canonical / "sources.jsonl").read_text().splitlines()), 3)
        self.assertEqual(
            len((canonical / "documents.jsonl").read_text().splitlines()), 1
        )
        self.google_search.return_value = original

    def test_google_quota_failure_does_not_discard_other_sources(self):
        request = adapter.httpx.Request(
            "GET", "https://www.googleapis.com/books/v1/volumes"
        )
        response = adapter.httpx.Response(429, request=request)
        self.google_search.side_effect = adapter.httpx.HTTPStatusError(
            "quota", request=request, response=response
        )
        # Reuse the domestic + Open Library path, with Google's response independently failing.
        # This helper also proves the completed partial checkpoint makes no further requests.
        query = {"name": "경제", "discoveryId": "00000000-0000-0000-0000-000000000003"}
        domestic = self.response("경제학 입문")
        domestic["data"]["items"][0]["goodsSortNm"] = "국내도서-경제 경영"
        policy = adapter.discovery_policy("경제", "국내도서-경제 경영")
        payload = self.payload | {
            "name": "경제",
            "slug": policy.slug,
            "parentCode": "SRC-" + policy.domain[9:],
            "parentName": "경제 경영",
            "discovery": {
                "id": query["discoveryId"],
                "query": "경제",
                "category": "국내도서-경제 경영",
            },
        }
        with (
            patch.object(adapter.Yes24Collector, "search_query", return_value=domestic),
            patch.object(
                adapter.OpenLibraryCollector, "search_scope", return_value={"docs": []}
            ),
        ):
            adapter.discover_categories_only(query, self.directory)
            result = adapter.collect(payload, self.directory / "request-1")
            self.assertEqual(
                adapter.collect(payload, self.directory / "request-1"), result
            )
        self.assertEqual(result["bookCount"], 1)
        self.google_search.assert_called_once()
        report = json.loads(
            (
                Path(result["importManifest"]).parents[1] / "provider-report.json"
            ).read_text()
        )
        self.assertEqual(report["google_books"]["statusCode"], 429)
        self.assertEqual(report["yes24"]["bookCount"], 1)

    def test_compatible_original_response_replays_across_code_versions(self):
        slug = adapter.discovery_policy("경제", "국내도서-경제 경영").slug
        params = adapter.google_books_parameters(
            adapter.collection_scope("경제", "국내도서-경제 경영")
        )
        first = self.directory / (slug + "-old-code")
        second = self.directory / (slug + "-new-code")
        first.mkdir()
        second.mkdir()
        artifact, reuse = adapter.collect_foreign_artifact(
            "google_books",
            adapter.GoogleBooksCollector,
            params,
            first,
            self.directory,
            slug,
        )
        replayed, reuse = adapter.collect_foreign_artifact(
            "google_books",
            adapter.GoogleBooksCollector,
            params,
            second,
            self.directory,
            slug,
        )
        self.assertEqual(artifact.model_dump(), replayed.model_dump())
        self.assertEqual(reuse, "previous_run")
        self.google_search.assert_called_once()

    def test_foreign_failure_keeps_domestic_catalog_and_audits_partial_result(self):
        query = {"name": "경제", "discoveryId": "00000000-0000-0000-0000-000000000002"}
        response = self.response("경제학 입문")
        response["data"]["items"][0]["goodsSortNm"] = "국내도서-경제 경영"
        policy = adapter.discovery_policy("경제", "국내도서-경제 경영")
        payload = self.payload | {
            "name": "경제",
            "slug": policy.slug,
            "parentCode": "SRC-" + policy.domain[9:],
            "parentName": "경제 경영",
            "discovery": {
                "id": query["discoveryId"],
                "query": "경제",
                "category": "국내도서-경제 경영",
            },
        }
        with (
            patch.object(adapter.Yes24Collector, "search_query", return_value=response),
            patch.object(
                adapter.OpenLibraryCollector,
                "search_scope",
                side_effect=adapter.httpx.ConnectError("offline"),
            ),
        ):
            adapter.discover_categories_only(query, self.directory)
            result = adapter.collect(payload, self.directory / "request-1")
        self.assertEqual(result["bookCount"], 1)
        report = json.loads(
            (
                Path(result["importManifest"]).parents[1] / "provider-report.json"
            ).read_text()
        )
        self.assertEqual(report["open_library"]["status"], "provider_failed")

    def refresh_payload(self):
        self.test_new_query_discovery_selection_reuses_original_response_without_gemini()
        path = next(self.directory.glob("request-1/*/handoff/selection.json"))
        selected = json.loads(path.read_text())
        policy = adapter.discovery_policy("경제", "국내도서-경제 경영")
        return self.payload | {
            "slug": policy.slug,
            "name": "경제",
            "parentCode": "SRC-" + policy.domain[9:],
            "parentName": "경제 경영",
            "baselineSelection": selected["snapshot_id"],
            "baselineSelectionHash": adapter.digest(path.read_bytes()),
            "refreshId": "00000000-0000-0000-0000-000000000004",
            "providers": ["google_books"],
        }

    def test_explicit_failed_provider_refresh_fetches_fresh_and_preserves_every_old_book(
        self,
    ):
        payload = self.refresh_payload()
        self.google_search.reset_mock()
        request = adapter.httpx.Request(
            "GET", "https://www.googleapis.com/books/v1/volumes"
        )
        self.google_search.side_effect = adapter.httpx.HTTPStatusError(
            "quota",
            request=request,
            response=adapter.httpx.Response(429, request=request),
        )
        with (
            patch.object(adapter.Yes24Collector, "search_query") as domestic,
            patch.object(adapter.OpenLibraryCollector, "search_scope") as foreign,
        ):
            result = adapter.refresh_catalog(payload, self.directory)
            self.assertEqual(adapter.refresh_catalog(payload, self.directory), result)
        domestic.assert_not_called()
        foreign.assert_not_called()
        self.google_search.assert_called_once()
        self.assertEqual(result["bookCount"], 2)
        self.assertEqual(result["addedBookCount"], 0)
        self.assertEqual(result["providers"]["google_books"]["statusCode"], 429)
        self.assertEqual(result["baselineSelection"], payload["baselineSelection"])
        selection = json.loads(Path(result["selectionManifest"]).read_text())
        refreshed = payload | {
            "baselineSelection": selection["snapshot_id"],
            "baselineSelectionHash": adapter.digest(
                Path(result["selectionManifest"]).read_bytes()
            ),
        }
        self.assertTrue(adapter.catalog_state(refreshed, self.directory)["available"])

    def test_refresh_adds_new_verified_book_and_replays_interrupted_raw_checkpoint(
        self,
    ):
        payload = self.refresh_payload()
        self.google_search.reset_mock()
        self.google_search.return_value = {
            "items": [
                {
                    "id": "second-economics",
                    "volumeInfo": {
                        "title": "Economics textbook",
                        "language": "en",
                        "categories": ["Business & Economics"],
                        "authors": ["Second Author"],
                        "industryIdentifiers": [
                            {"type": "ISBN_13", "identifier": "9780262033848"}
                        ],
                    },
                }
            ]
        }
        with (
            patch.object(
                adapter, "write_handoff", side_effect=RuntimeError("interrupted")
            ),
            self.assertRaises(RuntimeError),
        ):
            adapter.refresh_catalog(payload, self.directory)
        self.assertFalse(
            (
                self.directory
                / "catalog-refresh"
                / payload["refreshId"]
                / "result.json"
            ).exists()
        )
        result = adapter.refresh_catalog(payload, self.directory)
        self.google_search.assert_called_once()
        self.assertEqual(result["bookCount"], 3)
        self.assertEqual(result["addedBookCount"], 1)
        self.assertEqual(result["providers"]["google_books"]["status"], "collected")

    def test_refresh_rejects_changed_baseline_or_invalid_provider_before_network(self):
        payload = self.refresh_payload()
        self.google_search.reset_mock()
        with self.assertRaisesRegex(ValueError, "baseline_selection_changed"):
            adapter.refresh_catalog(
                payload | {"baselineSelectionHash": "sha256:" + "0" * 64},
                self.directory,
            )
        with self.assertRaisesRegex(ValueError, "invalid_refresh_providers"):
            adapter.refresh_catalog(
                payload | {"providers": ["arbitrary"]}, self.directory
            )
        original = next(self.directory.glob("request-1/*/canonical/books.jsonl"))
        original.write_text(original.read_text().replace("Economics", "Tampered title"))
        with self.assertRaisesRegex(ValueError, "baseline_books_changed"):
            adapter.refresh_catalog(payload, self.directory)
        self.google_search.assert_not_called()

    def common_discovery(self, domestic_failure=False):
        field = adapter.common_field("microeconomics")
        query = {
            "name": "Microeconomics",
            "discoveryId": "00000000-0000-0000-0000-000000000009",
        }
        domestic = self.response("미시경제학 입문")
        domestic["data"]["items"][0]["goodsSortNm"] = "국내도서-대학교재"
        foreign = {
            "docs": [
                {
                    "key": "/works/OL1W",
                    "title": "Microeconomics",
                    "author_name": ["Author"],
                    "subject": ["Microeconomics"],
                    "editions": {
                        "docs": [
                            {
                                "key": "/books/OL1M",
                                "title": "Microeconomics",
                                "language": ["eng"],
                                "isbn": ["9780134853987"],
                            }
                        ]
                    },
                }
            ]
        }
        with (
            patch.object(
                adapter.Yes24Collector, "search_query", return_value=domestic
            ) as yes,
            patch.object(
                adapter.OpenLibraryCollector, "search_scope", return_value=foreign
            ) as ol,
        ):
            if domestic_failure:
                yes.side_effect = adapter.httpx.ConnectError("synthetic")
            found = adapter.discover(query, self.directory)
            self.assertEqual(adapter.discover(query, self.directory), found)
            yes.assert_called_once_with("미시경제학", candidate_limit=100)
            ol.assert_called_once()
        self.assertEqual(found["status"], "FOUND")
        self.assertEqual(found["fields"][0]["id"], field.id)
        return field, query, found

    def common_payload(self, field, query):
        return self.payload | {
            "name": query["name"],
            "slug": field.slug,
            "parentCode": field.parent_code,
            "parentName": field.domain_name,
            "discovery": {
                "id": query["discoveryId"],
                "query": query["name"],
                "commonFieldId": field.id,
                "registryHash": field.registry_hash,
            },
        }

    def test_common_field_collection_reuses_all_three_sources_and_keeps_diagnosis_closed(
        self,
    ):
        field, query, found = self.common_discovery()
        with (
            patch.object(adapter.Yes24Collector, "search_query") as yes,
            patch.object(adapter.OpenLibraryCollector, "search_scope") as ol,
        ):
            result = adapter.collect(
                self.common_payload(field, query), self.directory / "request-1"
            )
            yes.assert_not_called()
            ol.assert_not_called()
        self.assertEqual(result["bookCount"], 2)
        self.assertEqual(found["fields"][0]["bookCount"], 2)
        run = Path(result["importManifest"]).parents[1]
        policy = json.loads((run / "discovery-policy.json").read_text())
        self.assertFalse(policy["diagnosisReady"])
        self.assertEqual(
            {
                b["language"]
                for b in [
                    json.loads(line)
                    for line in (run / "canonical/books.jsonl").read_text().splitlines()
                ]
            },
            {"ko", "en"},
        )
        topic = json.loads(Path(result["importManifest"]).read_text())["topics"][0][
            "topic"
        ]
        self.assertEqual(topic["name"], "미시경제학")
        self.assertEqual(topic["ml_topic_id"], "field-microeconomics")

    def test_common_field_foreign_discovery_survives_domestic_failure_and_google_quota(
        self,
    ):
        request = adapter.httpx.Request(
            "GET", "https://www.googleapis.com/books/v1/volumes"
        )
        self.google_search.side_effect = adapter.httpx.HTTPStatusError(
            "quota",
            request=request,
            response=adapter.httpx.Response(429, request=request),
        )
        field, query, found = self.common_discovery(domestic_failure=True)
        self.assertEqual(found["fields"][0]["bookCount"], 1)
        self.assertEqual(
            {p["id"]: p["status"] for p in found["fields"][0]["providers"]},
            {
                "yes24": "provider_failed",
                "open_library": "collected",
                "google_books": "provider_failed",
                "national_library": "not_configured",
            },
        )
        self.assertEqual(
            adapter.collect(
                self.common_payload(field, query), self.directory / "request-1"
            )["bookCount"],
            1,
        )

    def test_common_field_rejects_changed_canonical_evidence_before_publication(self):
        field, query, _ = self.common_discovery()
        books = (
            self.directory
            / "discoveries"
            / query["discoveryId"]
            / field.slug
            / "canonical/books.jsonl"
        )
        books.write_text(
            books.read_text().replace("Microeconomics", "Different subject")
        )
        with self.assertRaisesRegex(ValueError, "common_field_canonical_changed"):
            adapter.collect(
                self.common_payload(field, query), self.directory / "request-1"
            )

    def test_national_library_enriches_discovery_and_replays_into_handoff(self):
        response = {
            "docs": [
                {
                    "TITLE": "미시경제학 입문",
                    "EA_ISBN": "9780306406157",
                    "AUTHOR": "Synthetic Author",
                    "SUBJECT": "미시경제학",
                    "KDC": "320",
                    "BOOK_TB_CNT": "제1장 수요와 공급\n1.1 균형 가격",
                    "BOOK_INTRODUCTION": "가상 책소개",
                }
            ]
        }
        with (
            patch.dict(os.environ, {"NL_GO_KR_API_KEY": "synthetic-nl-key"}),
            patch.object(
                adapter.NationalLibraryCollector, "search_scope", return_value=response
            ) as nl,
        ):
            field, query, found = self.common_discovery()
            self.assertEqual(found["fields"][0]["bookCount"], 2)
            nl.assert_called_once()
            self.assertEqual(nl.call_args.args[0]["title"], "미시경제학")
            result = adapter.collect(
                self.common_payload(field, query), self.directory / "request-1"
            )
            nl.assert_called_once()
        canonical = Path(result["importManifest"]).parents[1] / "canonical"
        dataset = adapter.read_dataset(canonical)
        self.assertEqual(len(dataset.books), 2)
        self.assertTrue(any(s.provider == "national_library" for s in dataset.sources))
        self.assertTrue(dataset.toc)
        self.assertFalse(adapter.validate_dataset(dataset))
        for path in self.directory.rglob("*.json"):
            self.assertNotIn("synthetic-nl-key", path.read_text())

    def test_national_library_failure_preserves_other_sources(self):
        with (
            patch.dict(os.environ, {"NL_GO_KR_API_KEY": "synthetic-nl-key"}),
            patch.object(
                adapter.NationalLibraryCollector,
                "search_scope",
                side_effect=adapter.InvalidProviderResponse("invalid key"),
            ),
        ):
            _, _, found = self.common_discovery()
        self.assertEqual(found["fields"][0]["bookCount"], 2)
        report = {p["id"]: p for p in found["fields"][0]["providers"]}
        self.assertEqual(report["national_library"]["status"], "provider_failed")

    def test_national_library_refresh_uses_new_env_key_and_preserves_old_books(self):
        field, query, _ = self.common_discovery()
        result = adapter.collect(
            self.common_payload(field, query), self.directory / "request-1"
        )
        selection = Path(result["selectionManifest"])
        payload = self.common_payload(field, query) | {
            "refreshId": "00000000-0000-0000-0000-000000000012",
            "providers": ["national_library"],
            "baselineSelection": json.loads(selection.read_text())["snapshot_id"],
            "baselineSelectionHash": adapter.digest(selection.read_bytes()),
        }
        with (
            patch.dict(os.environ, {"NL_GO_KR_API_KEY": "synthetic-nl-key"}),
            patch.object(
                adapter.NationalLibraryCollector,
                "search_scope",
                return_value={
                    "docs": [
                        {
                            "TITLE": "미시경제학 새 책",
                            "EA_ISBN": "9780262033848",
                            "AUTHOR": "Author",
                        }
                    ]
                },
            ) as nl,
        ):
            updated = adapter.refresh_catalog(payload, self.directory)
            self.assertEqual(updated["bookCount"], 3)
            self.assertEqual(
                updated["providers"]["national_library"]["status"], "collected"
            )
            self.assertEqual(nl.call_args.args[0]["title"], "미시경제학")
            self.assertEqual(adapter.refresh_catalog(payload, self.directory), updated)
            nl.assert_called_once()

    def test_common_field_refresh_uses_korean_query_even_when_requested_in_english(
        self,
    ):
        field, query, _ = self.common_discovery()
        result = adapter.collect(
            self.common_payload(field, query), self.directory / "request-1"
        )
        selection = Path(result["selectionManifest"])
        payload = self.common_payload(field, query) | {
            "refreshId": "00000000-0000-0000-0000-000000000010",
            "providers": ["yes24"],
            "baselineSelection": json.loads(selection.read_text())["snapshot_id"],
            "baselineSelectionHash": adapter.digest(selection.read_bytes()),
        }
        with patch.object(
            adapter.Yes24Collector,
            "search_query",
            return_value=self.response("미시경제학 입문"),
        ) as yes:
            updated = adapter.refresh_catalog(payload, self.directory)
            yes.assert_called_once_with("미시경제학", candidate_limit=100)
        self.assertGreaterEqual(updated["bookCount"], 2)
        self.assertTrue(
            adapter.catalog_state(
                payload
                | {
                    "baselineSelection": json.loads(
                        Path(updated["selectionManifest"]).read_text()
                    )["snapshot_id"],
                    "baselineSelectionHash": adapter.digest(
                        Path(updated["selectionManifest"]).read_bytes()
                    ),
                },
                self.directory,
            )["available"]
        )

    def response(self, title="컴퓨터 네트워크 원리"):
        return {
            "data": {
                "items": [
                    {
                        "itemId": 123,
                        "title": title,
                        "goodsSortNm": "국내도서-IT 모바일-네트워크",
                        "isbn13": "9780306406157",
                        "author": "Synthetic author",
                    }
                ]
            }
        }

    def test_filtered_collection_preserves_raw_and_canonical_and_reuses_checkpoint(
        self,
    ):
        normalize = adapter.normalize_yes24_response

        def annotated(*args, **kwargs):
            dataset = normalize(*args, **kwargs)
            dataset.books[0].en_title = "Synthetic English analysis title"
            return dataset

        with (
            patch.object(
                adapter.Yes24Collector, "search_books", return_value=self.response()
            ) as search,
            patch.object(adapter, "normalize_yes24_response", side_effect=annotated),
        ):
            result = adapter.collect(self.payload, self.directory)
            self.assertEqual(adapter.collect(self.payload, self.directory), result)
            search.assert_called_once()
        self.assertEqual(result["bookCount"], 1)
        manifest = json.loads(Path(result["importManifest"]).read_text())
        self.assertIsNone(manifest["topics"][0]["candidates_path"])
        run = Path(result["importManifest"]).parents[1]
        canonical = json.loads((run / "canonical/books.jsonl").read_text())
        handoff = json.loads((run / "handoff/books.jsonl").read_text())
        self.assertIn("en_title", canonical)
        self.assertNotIn("en_title", handoff)
        self.assertEqual(canonical["book_id"], handoff["book_id"])
        self.assertEqual(len(list(run.glob("raw/yes24/computer-networks/*.json"))), 1)

    def test_interrupted_normalization_reuses_raw_and_empty_collection_never_publishes(
        self,
    ):
        with patch.object(
            adapter.Yes24Collector, "search_books", return_value=self.response()
        ) as search:
            with (
                patch.object(
                    adapter, "write_dataset", side_effect=RuntimeError("interrupted")
                ),
                self.assertRaises(RuntimeError),
            ):
                adapter.collect(self.payload, self.directory)
            adapter.collect(self.payload, self.directory)
            search.assert_called_once()
        other = self.directory / "empty"
        with (
            patch.object(
                adapter.Yes24Collector,
                "search_books",
                return_value=self.response("소셜 네트워크 마케팅"),
            ),
            self.assertRaisesRegex(ValueError, "no_eligible_books"),
        ):
            adapter.collect(self.payload, other)
        self.assertFalse(list(other.glob("**/result.json")))
        self.assertTrue(list(other.glob("**/filter-audit.json")))
        self.assertTrue(list(other.glob("**/raw/**/*.json")))


if __name__ == "__main__":
    unittest.main()
