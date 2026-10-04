package com.cau.capstone8.backend.book;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Developer-only, selected-book analysis transitions; no public mutation endpoint. */
@Service
public class CatalogTransitionService {
    private static final JsonMapper JSON = new JsonMapper();
    private final JdbcTemplate jdbc;
    private final LocalCatalogImportService importer;
    private final TransactionTemplate transaction;

    public CatalogTransitionService(JdbcTemplate jdbc, LocalCatalogImportService importer, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.importer = importer;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Entry(String mlBookId, Long projectionId, String version, int concepts,
                        int prerequisites, int sourceLinkedConcepts, String contentHash) {}
    public record Result(String token, UUID transitionId, String snapshotId, List<Entry> before, List<Entry> after) {}

    public Result preview(Path manifest) { return execute(manifest, null, null); }
    public Result apply(Path manifest, String token, String reason) {
        require(token != null && token.matches("sha256:[0-9a-f]{64}"), "preview token required");
        reason(reason);
        return execute(manifest, token, reason);
    }

    private Result execute(Path path, String expected, String reason) {
        byte[] bytes = LocalCatalogImportService.manifestBytes(path);
        var manifest = LocalCatalogImportService.parseManifest(bytes);
        require(manifest.topic() != null && manifest.selectedBookIds() != null && !manifest.selectedBookIds().isEmpty(),
                "explicit topic and selected books required");
        List<String> selected = manifest.selectedBookIds().stream().sorted().toList();
        String manifestHash = LocalCatalogImportService.hash(bytes);
        return transaction.execute(status -> {
            lock();
            if (expected != null) {
                var prior = jdbc.queryForList("select * from backend.catalog_transition where plan_token=?", expected);
                if (!prior.isEmpty()) {
                    var row = prior.getFirst();
                    require(manifestHash.equals(row.get("manifest_hash")), "preview is stale: manifest changed");
                    require(row.get("rolled_back_at") == null, "transition was rolled back; prepare a new snapshot");
                    var after = entries(row.get("after_state"));
                    require(after.equals(state(manifest.topic().mlTopicId(), selected)), "active catalog changed since transition");
                    return new Result(expected, (UUID) row.get("id"), manifest.snapshotId(), entries(row.get("before_state")), after);
                }
            }
            var before = state(manifest.topic().mlTopicId(), selected);
            String token = LocalCatalogImportService.hash((manifestHash + "\n" + JSON.writeValueAsString(before)).getBytes(StandardCharsets.UTF_8));
            require(expected == null || expected.equals(token), "preview is stale; preview the current inputs again");
            require(jdbc.queryForObject("select count(*) from backend.local_catalog_import where snapshot_id=?", Integer.class,
                    manifest.snapshotId()) == 0, "transition requires a new snapshot_id");
            // Same writer lock as both importers. Readers see all-old or all-new active rows.
            for (Entry old : before) if (old.projectionId() != null)
                jdbc.update("update backend.book_ranking_v2_projection set active=false where id=?", old.projectionId());
            var imported = importer.importManifest(path, bytes);
            var after = state(manifest.topic().mlTopicId(), selected);
            UUID id = expected == null ? null : UUID.randomUUID();
            if (expected == null) status.setRollbackOnly();
            else jdbc.update("""
                insert into backend.catalog_transition(id,topic_id,snapshot_id,manifest_hash,plan_token,before_state,after_state,reason)
                values (?,?,?,?,?,cast(? as jsonb),cast(? as jsonb),?)
                """, id, imported.topicId(), manifest.snapshotId(), manifestHash, token,
                    JSON.writeValueAsString(before), JSON.writeValueAsString(after), reason);
            return new Result(token, id, manifest.snapshotId(), before, after);
        });
    }

    public void rollback(UUID transitionId, String reason) {
        reason(reason);
        transaction.executeWithoutResult(status -> {
            lock();
            var rows = jdbc.queryForList("select * from backend.catalog_transition where id=? for update", transitionId);
            require(rows.size() == 1, "unknown transition");
            var row = rows.getFirst();
            require(row.get("rolled_back_at") == null, "transition already rolled back");
            var before = entries(row.get("before_state"));
            var after = entries(row.get("after_state"));
            String topic = jdbc.queryForObject("select ml_topic_id from backend.topic where id=?", String.class, row.get("topic_id"));
            require(after.equals(state(topic, after.stream().map(Entry::mlBookId).toList())), "active catalog changed; rollback refused");
            for (Entry old : before) if (old.projectionId() != null) {
                var saved = jdbc.queryForList("select (to_jsonb(p)-'active'-'updated_at')::text as content from backend.book_ranking_v2_projection p where id=?", old.projectionId());
                require(saved.size() == 1 && old.contentHash().equals(hashContent(saved.getFirst().get("content").toString())),
                        "previous projection changed; rollback refused");
            }
            for (Entry entry : after) jdbc.update("update backend.book_ranking_v2_projection set active=false where id=?", entry.projectionId());
            for (Entry entry : before) if (entry.projectionId() != null)
                jdbc.update("update backend.book_ranking_v2_projection set active=true where id=?", entry.projectionId());
            jdbc.update("update backend.catalog_transition set rolled_back_at=clock_timestamp(),rollback_reason=? where id=?", reason, transitionId);
        });
    }

    private List<Entry> state(String topic, List<String> selected) {
        List<Entry> entries = new ArrayList<>();
        for (String book : selected) {
            require(jdbc.queryForObject("""
                select count(*) from backend.book b join backend.book_topic bt on bt.book_id=b.id
                join backend.topic t on t.id=bt.topic_id where b.ml_book_id=? and t.ml_topic_id=?
                """, Integer.class, book, topic) == 1,
                    "existing book-topic mapping required; use the ordinary importer for new catalog entries");
            var found = jdbc.query("""
                select p.id,p.version,jsonb_array_length(p.covered_concepts) concepts,
                    jsonb_array_length(p.prerequisite_concepts) prerequisites,
                    (select count(*) from jsonb_array_elements(p.covered_concepts) c
                     where jsonb_typeof(c->'evidence')='array' and jsonb_array_length(c->'evidence')>0) linked,
                    (to_jsonb(p)-'active'-'updated_at')::text content
                from backend.book_ranking_v2_projection p join backend.book b on b.id=p.book_id
                join backend.topic t on t.id=p.topic_id where b.ml_book_id=? and t.ml_topic_id=? and p.active
                """, (rs,n) -> new Entry(book, rs.getLong("id"), rs.getString("version"), rs.getInt("concepts"),
                    rs.getInt("prerequisites"), rs.getInt("linked"), hashContent(rs.getString("content"))), book, topic);
            require(found.size() <= 1, "multiple active projections");
            entries.add(found.isEmpty() ? new Entry(book, null, null, 0, 0, 0, null) : found.getFirst());
        }
        return List.copyOf(entries);
    }

    private static String hashContent(String value) { return LocalCatalogImportService.hash(value.getBytes(StandardCharsets.UTF_8)); }
    private List<Entry> entries(Object json) { return JSON.readValue(json.toString(), new TypeReference<List<Entry>>() {}); }
    private void lock() { jdbc.queryForObject("select pg_advisory_xact_lock(hashtext('local-catalog-import-v1'))", Object.class); }
    private static void reason(String value) { require(value != null && !value.isBlank() && value.length() <= 1000, "reason required (1..1000 characters)"); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
