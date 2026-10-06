package com.cau.capstone8.backend.topic;

import com.cau.capstone8.backend.account.AccountException;
import com.cau.capstone8.backend.book.CatalogSelectionService;
import com.cau.capstone8.backend.book.DiscoveryCatalogImportService;
import com.cau.capstone8.backend.common.error.ResourceNotFoundException;
import java.nio.file.Path;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Shared browsing catalog, authenticated bounded refreshes; diagnosis is never activated here. */
@Service
public class TopicCatalogRefreshService {
    private final JdbcTemplate jdbc;
    private final TopicPreparationAdapter adapter;
    private final DiscoveryCatalogImportService imports;
    private final CatalogSelectionService selections;
    private final boolean enabled;
    private final JsonMapper json=JsonMapper.builder().build();
    private static final List<String> PROVIDERS=List.of("yes24","open_library","google_books");
    public TopicCatalogRefreshService(JdbcTemplate jdbc,TopicPreparationAdapter adapter,DiscoveryCatalogImportService imports,
            CatalogSelectionService selections,@Value("${topic-preparation.enabled:false}") boolean enabled) {
        this.jdbc=jdbc;this.adapter=adapter;this.imports=imports;this.selections=selections;this.enabled=enabled;
    }
    public record Provider(String id,String status,int bookCount,Integer statusCode) {}
    public record State(boolean available,String status,int bookCount,int addedBookCount,String updatedAt,List<Provider> providers) {}
    public record Input(String mode) {}
    public record Job(UUID id,UUID token,long topicId,String slug,String name,String parentCode,String parentName,
                      String baseline,String baselineHash,List<String> providers) {
        public TopicPreparationService.Job adapterJob() {return new TopicPreparationService.Job(topicId,token,name,"",parentCode,parentName);}
        public Map<String,Object> input() {return Map.of("refreshId",id.toString(),"baselineSelection",baseline,
                "baselineSelectionHash",baselineHash,"providers",providers);}
    }
    private Map<String,Object> topic(long id) {
        var rows=jdbc.queryForList("""
                SELECT t.id,t.name,t.ml_topic_id,p.code AS parent_code,p.name AS parent_name,
                       c.snapshot_id,s.manifest_hash,
                       (SELECT count(*) FROM backend.catalog_visible_book_topic v WHERE v.topic_id=t.id) AS book_count
                FROM backend.topic t LEFT JOIN backend.topic p ON p.id=t.parent_id
                LEFT JOIN backend.catalog_selection_current c ON c.topic_id=t.id
                LEFT JOIN backend.catalog_selection_snapshot s ON s.snapshot_id=c.snapshot_id WHERE t.id=?
                """,id);
        if(rows.isEmpty())throw new ResourceNotFoundException("분야를 찾을 수 없습니다.");
        return rows.getFirst();
    }
    private boolean eligible(Map<String,Object> t) {
        return enabled && t.get("ml_topic_id") instanceof String slug && slug.matches("(?:search-[0-9a-f]{20}|field-[a-z][a-z0-9-]{1,80})")
                && t.get("snapshot_id") instanceof String snapshot && snapshot.startsWith("book-search-");
    }
    private Job baseline(Map<String,Object> t) {
        return new Job(UUID.randomUUID(),null,((Number)t.get("id")).longValue(),(String)t.get("ml_topic_id"),(String)t.get("name"),
                (String)t.get("parent_code"),(String)t.get("parent_name"),(String)t.get("snapshot_id"),(String)t.get("manifest_hash"),List.of());
    }
    private JsonNode evidence(Map<String,Object> t) {
        try {var job=baseline(t);return adapter.run(job.adapterJob(),"catalog-state",job.slug(),job.input());}
        catch(Exception e) {return json.readTree("{\"available\":false,\"providers\":{}}");}
    }
    private List<Provider> providers(JsonNode report) {
        return PROVIDERS.stream().map(id->{var p=report.path(id);return new Provider(id,p.path("status").asString("not_collected"),
                p.path("bookCount").asInt(0),p.has("statusCode")?p.path("statusCode").asInt():null);}).toList();
    }
    public State state(long topicId) {
        var t=topic(topicId);int count=((Number)t.get("book_count")).intValue();
        if(!eligible(t))return new State(false,"UNAVAILABLE",count,0,null,List.of());
        var data=evidence(t);
        var recent=jdbc.queryForList("SELECT status,added_book_count,published_selection,baseline_selection,updated_at FROM backend.topic_catalog_refresh WHERE topic_id=? ORDER BY created_at DESC LIMIT 1",topicId);
        String status="IDLE",updated=null;int added=0;
        if(!recent.isEmpty()) {
            var r=recent.getFirst();String latest=(String)r.get("status");
            if(Objects.equals(t.get("snapshot_id"),r.get("published_selection")) || Objects.equals(t.get("snapshot_id"),r.get("baseline_selection"))) {
                status=latest;added=((Number)r.get("added_book_count")).intValue();updated=r.get("updated_at").toString();
            }
        }
        return new State(data.path("available").asBoolean(),status,count,added,updated,providers(data.path("providers")));
    }
    @Transactional
    public State start(long user,long topicId,Input input) {
        String mode=input==null?"ALL":input.mode();
        if(!Set.of("ALL","FAILED").contains(mode==null?"":mode))throw new AccountException(400,"INVALID_REFRESH_MODE","갱신 방법을 다시 선택해 주세요.");
        jdbc.queryForList("SELECT id FROM backend.app_user WHERE id=? FOR UPDATE",user);
        jdbc.queryForList("SELECT id FROM backend.topic WHERE id=? FOR UPDATE",topicId);
        var active=jdbc.queryForList("SELECT id FROM backend.topic_catalog_refresh WHERE topic_id=? AND status IN ('QUEUED','RUNNING')",topicId);
        if(!active.isEmpty())return state(topicId);
        var t=topic(topicId);
        if(!eligible(t))throw unavailable();
        var data=evidence(t);if(!data.path("available").asBoolean())throw unavailable();
        // The server derives failed providers from the active evidence. Clients cannot supply commands or scopes.
        var chosen=mode.equals("ALL")?PROVIDERS:providers(data.path("providers")).stream()
                .filter(p->p.status().equals("provider_failed")).map(Provider::id).toList();
        if(chosen.isEmpty())throw new AccountException(409,"NO_FAILED_PROVIDERS","다시 시도할 출처가 없어요.");
        if(jdbc.queryForObject("SELECT count(*) FROM backend.topic_catalog_refresh WHERE topic_id=? AND created_at>now()-interval '1 minute'",Integer.class,topicId)>0
                || jdbc.queryForObject("SELECT count(*) FROM backend.topic_catalog_refresh WHERE user_id=? AND created_at>now()-interval '1 hour'",Integer.class,user)>=10)
            throw new AccountException(429,"CATALOG_REFRESH_LIMIT","조금 뒤에 다시 갱신해 주세요.");
        var job=baseline(t);
        jdbc.update("INSERT INTO backend.topic_catalog_refresh(id,user_id,topic_id,baseline_selection,baseline_hash,providers,status) VALUES (?,?,?,?,?,cast(? as jsonb),'QUEUED')",
                job.id(),user,topicId,job.baseline(),job.baselineHash(),json.writeValueAsString(chosen));
        return state(topicId);
    }
    @Transactional
    public Job claim() {
        if(!enabled)return null;
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext('topic-preparation-v1'))",Object.class);
        jdbc.update("UPDATE backend.topic_catalog_refresh SET status='FAILED',claim_token=null,lease_until=null,updated_at=now() WHERE status='RUNNING' AND lease_until<now()");
        if(jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM backend.topic_catalog_refresh WHERE status='RUNNING')
                     + (SELECT count(*) FROM backend.topic_discovery WHERE status='SEARCHING')
                     + (SELECT count(*) FROM backend.topic_request WHERE status IN ('CHECKING','COLLECTING'))
                     + (SELECT count(*) FROM backend.topic_content_preparation WHERE status='PREPARING')
                     + (SELECT count(*) FROM backend.topic_question_preparation WHERE status IN ('GENERATING','REVIEWING'))
                """,Integer.class)>0)return null;
        var rows=jdbc.queryForList("SELECT * FROM backend.topic_catalog_refresh WHERE status='QUEUED' ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED");
        if(rows.isEmpty())return null;
        var r=rows.getFirst();var t=topic(((Number)r.get("topic_id")).longValue());
        UUID id=(UUID)r.get("id"),token=UUID.randomUUID();
        jdbc.update("UPDATE backend.topic_catalog_refresh SET status='RUNNING',claim_token=?,lease_until=now()+interval '4 minutes',updated_at=now() WHERE id=?",token,id);
        var chosen=new ArrayList<String>();for(var p:json.readTree(r.get("providers").toString()))chosen.add(p.asString());
        return new Job(id,token,((Number)t.get("id")).longValue(),(String)t.get("ml_topic_id"),(String)t.get("name"),
                (String)t.get("parent_code"),(String)t.get("parent_name"),(String)r.get("baseline_selection"),(String)r.get("baseline_hash"),List.copyOf(chosen));
    }
    @Transactional
    public void publish(Job job,JsonNode result,Path importPath,Path selectionPath) {
        var locked=jdbc.queryForList("SELECT id FROM backend.topic_catalog_refresh WHERE id=? AND status='RUNNING' AND claim_token=? AND lease_until>now() FOR UPDATE",job.id(),job.token());
        if(locked.size()!=1)throw new IllegalStateException("refresh claim expired");
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext('local-catalog-import-v1'))",Object.class);
        // The current pointer is fenced before either import or activation. A stale job never replaces a newer selection.
        var current=jdbc.queryForList("SELECT snapshot_id FROM backend.catalog_selection_current WHERE topic_id=? FOR UPDATE",String.class,job.topicId());
        if(current.size()!=1 || !current.getFirst().equals(job.baseline()))throw new IllegalStateException("refresh baseline changed");
        if(!"COLLECTED".equals(result.path("status").asString()) || !job.slug().equals(result.path("slug").asString())
                || !job.baseline().equals(result.path("baselineSelection").asString()))throw new IllegalArgumentException("invalid refresh result");
        var declared=new ArrayList<String>();for(var p:result.path("refreshedProviders"))declared.add(p.asString());
        if(!declared.equals(job.providers()))throw new IllegalArgumentException("refresh providers changed");
        var imported=imports.importManifest(importPath);
        if(!imported.snapshotId().equals("book-search-refresh-"+job.id()) || imported.topics().size()!=1
                || !imported.topics().containsKey(job.slug()) || imported.topics().get(job.slug()).topicId()!=job.topicId()
                || imported.bookCount()!=result.path("bookCount").asInt())throw new IllegalArgumentException("refresh catalog differs");
        var selected=selections.activate(selectionPath);
        if(!selected.snapshotId().equals(imported.snapshotId()+"-selected") || selected.included()!=imported.bookCount())throw new IllegalArgumentException("refresh selection differs");
        int missing=jdbc.queryForObject("""
                SELECT count(*) FROM backend.catalog_selection_member old WHERE old.snapshot_id=? AND old.topic_id=? AND old.included
                AND NOT EXISTS (SELECT 1 FROM backend.catalog_selection_member fresh WHERE fresh.snapshot_id=? AND fresh.topic_id=old.topic_id AND fresh.book_id=old.book_id AND fresh.included)
                """,Integer.class,job.baseline(),job.topicId(),selected.snapshotId());
        if(missing!=0)throw new IllegalArgumentException("refresh dropped existing books");
        int previous=jdbc.queryForObject("SELECT count(*) FROM backend.catalog_selection_member WHERE snapshot_id=? AND topic_id=? AND included",Integer.class,job.baseline(),job.topicId());
        var reports=providers(result.path("providers"));
        if(reports.stream().anyMatch(p->!Set.of("collected","provider_failed","unmapped_language","unmapped_category").contains(p.status())))throw new IllegalArgumentException("invalid provider status");
        String status=reports.stream().anyMatch(p->p.status().equals("provider_failed"))?"PARTIAL":"COMPLETE";
        // Keep only the public status projection; private raw paths and exception details stay in the workspace.
        jdbc.update("UPDATE backend.topic_catalog_refresh SET status=?,report=cast(? as jsonb),added_book_count=?,published_selection=?,claim_token=null,lease_until=null,updated_at=now() WHERE id=?",
                status,json.writeValueAsString(reports),selected.included()-previous,selected.snapshotId(),job.id());
        jdbc.update("UPDATE backend.topic_request SET book_count=? WHERE topic_id=? AND status='BOOKS_READY'",selected.included(),job.topicId());
    }
    @Transactional
    public void fail(Job job) {
        jdbc.update("UPDATE backend.topic_catalog_refresh SET status='FAILED',claim_token=null,lease_until=null,updated_at=now() WHERE id=? AND status='RUNNING' AND claim_token=?",job.id(),job.token());
    }
    private AccountException unavailable() {return new AccountException(409,"CATALOG_REFRESH_UNAVAILABLE","이 분야의 책 갱신은 아직 준비 중이에요.");}
}
