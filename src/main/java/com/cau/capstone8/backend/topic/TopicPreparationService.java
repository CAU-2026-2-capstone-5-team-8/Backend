package com.cau.capstone8.backend.topic;

import com.cau.capstone8.backend.book.CatalogSelectionService;
import com.cau.capstone8.backend.book.DiscoveryCatalogImportService;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable queue, fenced claims, and atomic publication. No request holds an HTTP connection open. */
@Service
public class TopicPreparationService {
    private final JdbcTemplate jdbc;
    private final DiscoveryCatalogImportService imports;
    private final CatalogSelectionService selections;
    public TopicPreparationService(JdbcTemplate jdbc, DiscoveryCatalogImportService imports, CatalogSelectionService selections) {
        this.jdbc=jdbc; this.imports=imports; this.selections=selections;
    }
    public record Job(long id, UUID token, String name, String scope, String parentCode, String parentName,String selectedSlug,String discoveryJson) {
        public Job(long id,UUID token,String name,String scope,String parentCode,String parentName) {
            this(id,token,name,scope,parentCode,parentName,null,null);
        }
        public Job(long id,UUID token,String name,String scope,String parentCode,String parentName,String selectedSlug) {
            this(id,token,name,scope,parentCode,parentName,selectedSlug,null);
        }
    }

    @Transactional
    public Job claim() {
        // Bound provider traffic across server instances, not merely scheduler threads.
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtext('topic-preparation-v1'))", Object.class);
        jdbc.update("UPDATE backend.topic_content_preparation SET status='FAILED',claim_token=null,lease_until=null,updated_at=now() WHERE status='PREPARING' AND lease_until<now()");
        jdbc.update("UPDATE backend.topic_question_preparation SET status='FAILED',claim_token=null,lease_until=null,updated_at=now() WHERE status='GENERATING' AND lease_until<now()");
        jdbc.update("""
                UPDATE backend.topic_request SET status='FAILED',claim_token=null,lease_until=null,
                    message='분야 준비가 중단됐어요. 다시 시도해 주세요.',updated_at=now()
                WHERE status IN ('CHECKING','COLLECTING') AND lease_until<now()
                """);
        jdbc.update("UPDATE backend.topic_request SET status='QUEUED',updated_at=now() WHERE status='NEEDS_REVIEW'");
        if (jdbc.queryForObject("SELECT (SELECT count(*) FROM backend.topic_catalog_refresh WHERE status='RUNNING') + (SELECT count(*) FROM backend.topic_discovery WHERE status='SEARCHING') + (SELECT count(*) FROM backend.topic_request WHERE status IN ('CHECKING','COLLECTING')) + (SELECT count(*) FROM backend.topic_content_preparation WHERE status='PREPARING') + (SELECT count(*) FROM backend.topic_question_preparation WHERE status IN ('GENERATING','REVIEWING'))",
                Integer.class)>0) return null;
        var rows=jdbc.queryForList("""
                SELECT r.id,r.name,r.scope,r.selected_slug,r.discovery_selection::text,t.code,t.name AS parent_name FROM backend.topic_request r
                LEFT JOIN backend.topic t ON t.id=r.category_id WHERE r.status='QUEUED'
                ORDER BY r.id LIMIT 1 FOR UPDATE OF r SKIP LOCKED
                """);
        if (rows.isEmpty()) return null;
        var row=rows.getFirst(); long id=((Number)row.get("id")).longValue(); UUID token=UUID.randomUUID();
        jdbc.update("""
                UPDATE backend.topic_request SET status='CHECKING',claim_token=?,lease_until=now()+interval '15 minutes',
                    attempts=attempts+1,message=null,updated_at=now() WHERE id=?
                """,token,id);
        return new Job(id,token,(String)row.get("name"),(String)row.get("scope"),(String)row.get("code"),(String)row.get("parent_name"),(String)row.get("selected_slug"),(String)row.get("discovery_selection"));
    }

    @Transactional
    public Job classify(Job job,String parentCode) {
        lock(job,"CHECKING");
        if (job.parentCode()!=null || !List.of("CS","MAT").contains(parentCode==null?"":parentCode))
            throw new IllegalArgumentException("invalid app category resolution");
        // These are application taxonomy roots, not a preloaded book catalog.
        // Cold databases must be able to accept the same requests as an existing installation.
        jdbc.update("INSERT INTO backend.topic(code,name) VALUES (?,?) ON CONFLICT(code) DO NOTHING",
                parentCode,"CS".equals(parentCode)?"컴퓨터과학":"수학");
        var categories=jdbc.queryForList("SELECT id,name FROM backend.topic WHERE code=? AND parent_id IS NULL",parentCode);
        if (categories.size()!=1) throw new IllegalArgumentException("app category unavailable");
        var category=categories.getFirst();
        int updated=jdbc.update("UPDATE backend.topic_request SET category_id=?,updated_at=now() WHERE id=? AND category_id IS NULL",category.get("id"),job.id());
        if (updated!=1) throw new IllegalStateException("request category changed");
        return new Job(job.id(),job.token(),job.name(),job.scope(),parentCode,(String)category.get("name"),job.selectedSlug(),job.discoveryJson());
    }

    @Transactional
    public Job classifyDiscovery(Job job,String code,String name) {
        lock(job,"CHECKING");
        if(job.discoveryJson()==null || !code.matches("SRC-[0-9a-f]{16}") || name.isBlank() || name.length()>120) throw new IllegalArgumentException("invalid discovered parent");
        return new Job(job.id(),job.token(),job.name(),job.scope(),code,name,job.selectedSlug(),job.discoveryJson());
    }

    @Transactional
    public boolean resolved(Job job, String slug) {
        lock(job, "CHECKING");
        if (job.parentCode()==null) throw new IllegalArgumentException("app category not resolved");
        if (slug==null || !slug.matches("[a-z][a-z0-9-]{1,119}")) throw new IllegalArgumentException("invalid resolved slug");
        var ids=jdbc.queryForList("""
                SELECT t.id,p.code,p.name FROM backend.topic t JOIN backend.topic p ON p.id=t.parent_id
                WHERE t.ml_topic_id=?
                """,slug);
        if (!ids.isEmpty()) {
            if (ids.size()!=1 || !job.parentCode().equals(ids.getFirst().get("code"))
                    || !job.parentName().equals(ids.getFirst().get("name")))
                throw new IllegalArgumentException("resolved topic category differs");
            long id=((Number)ids.getFirst().get("id")).longValue();
            int count=jdbc.queryForObject("SELECT count(*) FROM backend.catalog_visible_book_topic WHERE topic_id=?",Integer.class,id);
            // An empty field is not a successful preparation result.
            if (count>0) {
                ready(job,id,count,slug);
                return false;
            }
            throw new IllegalStateException("existing empty topic requires explicit catalog repair");
        }
        jdbc.update("UPDATE backend.topic_request SET status='COLLECTING',resolved_slug=?,updated_at=now() WHERE id=?",slug,job.id());
        return true;
    }

    @Transactional
    public void needsInput(Job job, String message) {
        lock(job,"CHECKING");
        if (message==null || message.isBlank()) message="더 구체적인 분야 이름으로 다시 입력해 주세요.";
        jdbc.update("""
                UPDATE backend.topic_request SET status='NEEDS_INPUT',message=?,claim_token=null,lease_until=null,
                    updated_at=now() WHERE id=?
                """,message.substring(0,Math.min(message.length(),500)),job.id());
    }

    @Transactional
    public void publish(Job job, String slug, Path importManifest, Path selectionManifest) {
        publish(job,slug,importManifest,selectionManifest,tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode());
    }

    @Transactional
    public void publish(Job job, String slug, Path importManifest, Path selectionManifest, tools.jackson.databind.JsonNode providerReport) {
        lock(job,"COLLECTING");
        String resolved=jdbc.queryForObject("SELECT resolved_slug FROM backend.topic_request WHERE id=?",String.class,job.id());
        if (!java.util.Objects.equals(slug,resolved)) throw new IllegalArgumentException("resolved topic changed");
        var result=imports.importManifest(importManifest);
        if (!result.topics().keySet().equals(java.util.Set.of(slug)) || result.bookCount()<1)
            throw new IllegalArgumentException("incomplete preparation handoff");
        long topicId=result.topics().get(slug).topicId();
        var parent=jdbc.queryForMap("""
                SELECT p.code,p.name FROM backend.topic t JOIN backend.topic p ON p.id=t.parent_id WHERE t.id=?
                """,topicId);
        if (!job.parentCode().equals(parent.get("code")) || !job.parentName().equals(parent.get("name")))
            throw new IllegalArgumentException("prepared topic category differs");
        var selected=selections.activate(selectionManifest);
        var current=jdbc.queryForList("SELECT snapshot_id FROM backend.catalog_selection_current WHERE topic_id=?",String.class,topicId);
        if (!selected.snapshotId().equals(result.snapshotId()+"-selected") || selected.included()!=result.bookCount()
                || current.size()!=1 || !current.getFirst().equals(selected.snapshotId()))
            throw new IllegalArgumentException("prepared selection differs");
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        jdbc.update("UPDATE backend.catalog_selection_snapshot SET provider_report=cast(? as jsonb) WHERE snapshot_id=?",
                json.writeValueAsString(TopicCatalogRefreshService.providers(providerReport)),selected.snapshotId());
        ready(job,topicId,result.bookCount(),slug);
    }

    @Transactional
    public void fail(Job job) {
        // A late process can never overwrite a newer claim or a completed result.
        jdbc.update("""
                UPDATE backend.topic_request SET status='FAILED',message='분야 준비를 마치지 못했어요. 다시 시도해 주세요.',
                    claim_token=null,lease_until=null,updated_at=now()
                WHERE id=? AND claim_token=? AND status IN ('CHECKING','COLLECTING')
                """,job.id(),job.token());
    }
    private void ready(Job job,long topic,int count,String slug) {
        jdbc.update("""
                UPDATE backend.topic_request SET category_id=coalesce(category_id,(SELECT parent_id FROM backend.topic WHERE id=?)),status='BOOKS_READY',topic_id=?,book_count=?,resolved_slug=?,
                    message='책을 살펴볼 수 있어요. 맞춤 진단은 아직 준비 중이에요.',
                    claim_token=null,lease_until=null,updated_at=now() WHERE id=?
                """,topic,topic,count,slug,job.id());
    }
    private void lock(Job job,String status) {
        List<Long> found=jdbc.queryForList("""
                SELECT id FROM backend.topic_request WHERE id=? AND claim_token=? AND status=? AND lease_until>now()
                FOR UPDATE
                """,Long.class,job.id(),job.token(),status);
        if (found.isEmpty()) throw new IllegalStateException("expired or replaced preparation claim");
    }
}
