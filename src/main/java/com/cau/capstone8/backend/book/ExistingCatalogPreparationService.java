package com.cau.capstone8.backend.book;

import java.nio.file.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Explicit migration of existing, unprepared topics. Does not replace active diagnosis banks. */
@Service
public class ExistingCatalogPreparationService {
    private final JdbcTemplate jdbc;
    private final DiscoveryCatalogImportService imports;
    private final CatalogSelectionService selections;
    private final JsonMapper json=JsonMapper.builder().build();
    public ExistingCatalogPreparationService(JdbcTemplate jdbc,DiscoveryCatalogImportService imports,CatalogSelectionService selections) {
        this.jdbc=jdbc;this.imports=imports;this.selections=selections;
    }
    @Transactional(rollbackFor = Exception.class)
    public Map<String,Integer> apply(Path planPath) throws Exception {
        Path root=planPath.toRealPath().getParent();
        var plan=json.readTree(Files.readAllBytes(planPath));
        require("existing-catalog-preparation-v1".equals(plan.path("contractVersion").asString()),"invalid preparation plan");
        var topics=plan.path("topics");
        require(topics.isArray() && !topics.isEmpty() && topics.size()<=20,"explicit topics required");
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext('topic-preparation-v1'))",Object.class);
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext('local-catalog-import-v1'))",Object.class);
        var results=new LinkedHashMap<String,Integer>();
        for(var entry:topics) {
            String slug=entry.path("slug").asString();
            require(!results.containsKey(slug),"duplicate topic");
            var rows=jdbc.queryForList("""
                SELECT t.id,c.snapshot_id,s.manifest_hash FROM backend.topic t
                JOIN backend.catalog_selection_current c ON c.topic_id=t.id
                JOIN backend.catalog_selection_snapshot s ON s.snapshot_id=c.snapshot_id
                WHERE t.ml_topic_id=? FOR UPDATE OF c
                """,slug);
            require(rows.size()==1,"existing selected topic required");
            var row=rows.getFirst();long topic=((Number)row.get("id")).longValue();
            require(jdbc.queryForObject("SELECT count(*) FROM backend.question WHERE topic_id=? AND active",Integer.class,topic)==0,"active diagnosis must be preserved");
            var importedPath=inside(root,entry.path("importManifest").asString());
            var selectionPath=inside(root,entry.path("selectionManifest").asString());
            var selection=json.readTree(Files.readAllBytes(selectionPath));
            // Replaying the exact applied plan is harmless; never revert a subsequent selection.
            boolean replay=row.get("snapshot_id").equals(selection.path("snapshot_id").asString());
            require(replay || (row.get("snapshot_id").equals(entry.path("baselineSelection").asString())
                    && row.get("manifest_hash").equals(entry.path("baselineHash").asString())),"catalog baseline changed");
            var before=new HashSet<>(jdbc.queryForList("SELECT book_id FROM backend.catalog_visible_book_topic WHERE topic_id=?",Long.class,topic));
            var imported=imports.importManifest(importedPath);
            require(imported.snapshotId().startsWith("catalog-backfill-") && imported.topics().size()==1
                    && imported.topics().containsKey(slug) && imported.topics().get(slug).topicId()==topic,"backfill identity differs");
            require(selection.path("source_snapshot_id").asString().equals(imported.snapshotId()),"selection source differs");
            selections.activate(selectionPath);
            var after=new HashSet<>(jdbc.queryForList("SELECT book_id FROM backend.catalog_visible_book_topic WHERE topic_id=?",Long.class,topic));
            require(before.equals(after) && !after.isEmpty(),"backfill must preserve every visible book");
            results.put(slug,after.size());
        }
        return results;
    }
    private static Path inside(Path root,String value) throws Exception {
        Path path=root.resolve(value).toRealPath();
        require(path.startsWith(root) && Files.isRegularFile(path),"artifact outside plan");return path;
    }
    private static void require(boolean condition,String message) { if(!condition)throw new IllegalArgumentException(message); }
}
