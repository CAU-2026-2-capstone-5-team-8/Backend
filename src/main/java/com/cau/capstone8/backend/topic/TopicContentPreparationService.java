package com.cau.capstone8.backend.topic;

import com.cau.capstone8.backend.account.AccountException;
import com.cau.capstone8.backend.common.error.ResourceNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A separate durable content stage; successful concept proposals never activate a diagnosis. */
@Service
public class TopicContentPreparationService {
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final JsonMapper json=JsonMapper.builder().build();
    public TopicContentPreparationService(JdbcTemplate jdbc,
            @Value("${topic-content-preparation.enabled:false}") boolean enabled) {
        this.jdbc=jdbc; this.enabled=enabled;
    }
    public record Job(long topicId, String snapshotId, UUID token, String slug,
                      TopicPreparationService.Job adapterJob) {}

    @Transactional
    public Job claim() {
        if (!enabled) return null;
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext('topic-preparation-v1'))",Object.class);
        jdbc.update("""
                UPDATE backend.topic_content_preparation SET status='FAILED',claim_token=null,lease_until=null,updated_at=now()
                WHERE status='PREPARING' AND lease_until<now()
                """);
        // Only catalogs published by the request worker qualify. Existing manually prepared
        // question banks and topics remain untouched. Resolve the ORIGINAL source request.
        jdbc.update("""
                INSERT INTO backend.topic_content_preparation(topic_id,source_snapshot_id,source_request_id)
                SELECT c.topic_id,d.snapshot_id,r.id FROM backend.catalog_selection_current c
                JOIN backend.catalog_selection_snapshot s ON s.snapshot_id=c.snapshot_id
                JOIN backend.discovery_catalog_import d ON d.snapshot_id=s.manifest->>'source_snapshot_id'
                LEFT JOIN backend.topic_request r ON r.topic_id=c.topic_id AND r.status='BOOKS_READY'
                    AND d.snapshot_id LIKE 'topic-request-'||r.id||'-%'
                WHERE d.snapshot_id LIKE 'topic-request-%' OR d.snapshot_id LIKE 'book-search-%'
                ON CONFLICT DO NOTHING
                """);
        if (jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM backend.topic_catalog_refresh WHERE status='RUNNING') + (SELECT count(*) FROM backend.topic_discovery WHERE status='SEARCHING')
                     + (SELECT count(*) FROM backend.topic_request WHERE status IN ('CHECKING','COLLECTING'))
                     + (SELECT count(*) FROM backend.topic_content_preparation WHERE status='PREPARING')
                     + (SELECT count(*) FROM backend.topic_question_preparation WHERE status IN ('GENERATING','REVIEWING'))
                """,Integer.class)>0) return null;
        var rows=jdbc.queryForList("""
                SELECT p.topic_id,p.source_snapshot_id,COALESCE(p.workspace_id,p.source_request_id,-p.topic_id) AS source_request_id,t.ml_topic_id,t.name,c.code,c.name AS parent_name
                FROM backend.topic_content_preparation p JOIN backend.topic t ON t.id=p.topic_id
                JOIN backend.topic c ON c.id=t.parent_id
                JOIN backend.catalog_selection_current current ON current.topic_id=p.topic_id
                JOIN backend.catalog_selection_snapshot s ON s.snapshot_id=current.snapshot_id
                WHERE p.status='QUEUED' AND s.manifest->>'source_snapshot_id'=p.source_snapshot_id
                ORDER BY p.source_request_id LIMIT 1 FOR UPDATE OF p SKIP LOCKED
                """);
        if (rows.isEmpty()) return null;
        var row=rows.getFirst();
        long topic=((Number)row.get("topic_id")).longValue();
        String snapshot=(String)row.get("source_snapshot_id"); UUID token=UUID.randomUUID();
        jdbc.update("""
                UPDATE backend.topic_content_preparation SET workspace_id=COALESCE(workspace_id,source_request_id,-topic_id),status='PREPARING',claim_token=?,
                    lease_until=now()+interval '5 minutes',attempts=attempts+1,updated_at=now()
                WHERE topic_id=? AND source_snapshot_id=?
                """,token,topic,snapshot);
        var adapterJob=new TopicPreparationService.Job((row.get("source_request_id")==null ? -topic : ((Number)row.get("source_request_id")).longValue()),token,
                (String)row.get("name"),"",(String)row.get("code"),(String)row.get("parent_name"));
        return new Job(topic,snapshot,token,(String)row.get("ml_topic_id"),adapterJob);
    }

    @Transactional
    public void publish(Job job, Path path, String expectedHash) throws Exception {
        if (jdbc.queryForList("""
                SELECT topic_id FROM backend.topic_content_preparation WHERE topic_id=? AND source_snapshot_id=?
                    AND status='PREPARING' AND claim_token=? AND lease_until>now() FOR UPDATE
                """,job.topicId(),job.snapshotId(),job.token()).isEmpty())
            throw new IllegalStateException("content claim expired");
        byte[] bytes=Files.readAllBytes(path);
        require(bytes.length<=1024*1024 && hash(bytes).equals(expectedHash),"report hash differs");
        JsonNode report=json.readTree(bytes);
        require("topic-content-preparation-v1".equals(report.path("contractVersion").asString())
                && job.slug().equals(report.path("topicId").asString())
                && job.snapshotId().equals(report.path("sourceSnapshotId").asString()),"content source differs");
        String sourceHash=jdbc.queryForObject("SELECT manifest_hash FROM backend.discovery_catalog_import WHERE snapshot_id=?",String.class,job.snapshotId());
        require(sourceHash.equals(report.path("sourceManifestHash").asString()),"source manifest differs");
        require(report.path("diagnosisReady").isBoolean() && !report.path("diagnosisReady").asBoolean()
                && report.path("generatedQuestionCount").isIntegralNumber() && report.path("generatedQuestionCount").asInt()==0
                && "unreviewed".equals(report.path("humanReview").asString()),"invalid review or readiness claim");
        var books=new HashSet<>(jdbc.queryForList("""
                SELECT b.ml_book_id FROM backend.discovery_catalog_member m JOIN backend.book b ON b.id=m.book_id
                WHERE m.snapshot_id=? AND m.topic_id=?
                """,String.class,job.snapshotId(),job.topicId()));
        require(books.size()==report.path("bookCount").asInt(),"catalog count differs");
        Path root=path.toRealPath().getParent().getParent().getParent();
        Path canonical=Path.of(report.path("canonicalDataPath").asString()).toRealPath();
        require(canonical.startsWith(root) && canonical.getFileName().toString().equals("canonical"),"canonical outside job");
        var tocReferences=new HashSet<String>();
        for (String file:List.of("books.jsonl","documents.jsonl","toc.jsonl","sources.jsonl")) {
            byte[] data=Files.readAllBytes(canonical.resolve(file));
            require(hash(data).equals(report.path("canonicalFileHashes").path(file).asString()),"canonical hash differs");
            if (file.equals("toc.jsonl")) for (String line:new String(data,java.nio.charset.StandardCharsets.UTF_8).lines().filter(l->!l.isBlank()).toList()) {
                var entry=json.readTree(line);
                tocReferences.add(entry.path("book_id").asString()+"/"+entry.path("toc_entry_id").asString());
            }
        }
        var allowedArtifacts=Set.of("outline.json","book-profiles.jsonl","targets.json","blueprint.json",
                "configs/features.yaml","configs/concept_graph.yaml","configs/concept_matching_v2.yaml");
        JsonNode artifacts=report.path("artifactHashes");
        require(artifacts.isObject() && artifacts.size()>=5,"artifact hashes required");
        for (String file:artifacts.propertyNames()) {
            require(allowedArtifacts.contains(file),"unknown content artifact");
            Path artifact=path.getParent().resolve(file).toRealPath();
            require(artifact.startsWith(path.getParent().toRealPath()) && hash(Files.readAllBytes(artifact)).equals(artifacts.path(file).asString()),"content artifact differs");
        }
        JsonNode concepts=report.path("concepts");
        require(concepts.isArray() && concepts.size()>=6 && concepts.size()<=12,"invalid concept list");
        var ids=new HashSet<String>(); var mappedBooks=new HashSet<String>(); int grounded=0;
        for (JsonNode concept:concepts) {
            String id=concept.path("id").asString(),name=concept.path("name").asString();
            require(id.startsWith(job.slug()+":") && ids.add(id) && name.length()>=2 && name.length()<=100,"invalid concept");
            var seen=new HashSet<String>(); var conceptBooks=new HashSet<String>();
            JsonNode refs=concept.path("evidenceReferences"); require(refs.isArray(),"invalid evidence list");
            for (JsonNode ref:refs) {
                String book=ref.path("book_id").asString(),toc=ref.path("toc_entry_id").asString();
                require(books.contains(book) && tocReferences.contains(book+"/"+toc) && seen.add(book+"/"+toc),"invalid TOC evidence");
                conceptBooks.add(book); mappedBooks.add(book);
            }
            require(refs.size()==concept.path("tocCount").asInt() && conceptBooks.size()==concept.path("bookCount").asInt(),"evidence count differs");
            if (!conceptBooks.isEmpty()) grounded++;
        }
        require(grounded==report.path("conceptCount").asInt() && mappedBooks.size()==report.path("mappedBookCount").asInt(),"coverage differs");
        String status=report.path("status").asString(); int count=report.path("questionSpecCount").asInt(-1);
        require(List.of("CONCEPTS_READY","NEEDS_EVIDENCE").contains(status),"invalid content status");
        if (status.equals("CONCEPTS_READY")) {
            require(count>=9 && count<=18 && count%3==0,"invalid question specification count");
            require(artifacts.has("blueprint.json") && artifacts.has("targets.json"),"blueprint hashes required");
            JsonNode blueprint=json.readTree(Files.readAllBytes(path.getParent().resolve("blueprint.json")));
            require(job.slug().equals(blueprint.path("topic_id").asString())
                    && blueprint.path("question_specs").size()==count,"blueprint differs");
            var cells=new HashSet<String>();
            for (JsonNode spec:blueprint.path("question_specs")) {
                String concept=spec.path("primary_concept").asString(),ability=spec.path("ability").asString();
                require(ids.contains(concept) && List.of("meaning","application","reasoning").contains(ability)
                        && cells.add(concept+"/"+ability) && job.slug().equals(spec.path("topic_id").asString())
                        && "prior-knowledge".equals(spec.path("measurement_context").asString()),"invalid assessment target");
                var evidenceBooks=new HashSet<String>();
                for (JsonNode ref:spec.path("evidence_references")) {
                    String book=ref.path("book_id").asString();
                    require(books.contains(book) && tocReferences.contains(book+"/"+ref.path("toc_entry_id").asString()),"blueprint evidence differs");
                    evidenceBooks.add(book);
                }
                require(evidenceBooks.size()>=2,"target requires multiple books");
            }
        } else require(count==0,"insufficient evidence cannot publish targets");
        // Fence a changed catalog even if another import ran while the model was responding.
        require(jdbc.queryForObject("""
                SELECT count(*) FROM backend.catalog_selection_current c JOIN backend.catalog_selection_snapshot s
                ON s.snapshot_id=c.snapshot_id WHERE c.topic_id=? AND s.manifest->>'source_snapshot_id'=?
                """,Integer.class,job.topicId(),job.snapshotId())==1,"active catalog changed");
        jdbc.update("""
                UPDATE backend.topic_content_preparation SET status=?,report=?::jsonb,report_hash=?,artifact_path=?,
                    claim_token=null,lease_until=null,updated_at=now() WHERE topic_id=? AND source_snapshot_id=?
                """,status,new String(bytes,java.nio.charset.StandardCharsets.UTF_8),expectedHash,path.toString(),job.topicId(),job.snapshotId());
    }

    @Transactional
    public void defer(Job job) {
        int changed=jdbc.update("""
                UPDATE backend.topic_content_preparation p SET status='QUEUED',claim_token=null,lease_until=null,updated_at=now()
                FROM backend.catalog_selection_current c JOIN backend.catalog_selection_snapshot s ON s.snapshot_id=c.snapshot_id
                WHERE p.topic_id=? AND p.source_snapshot_id=? AND p.status='PREPARING' AND p.claim_token=? AND p.lease_until>now()
                    AND c.topic_id=p.topic_id AND s.manifest->>'source_snapshot_id'=p.source_snapshot_id
                """,job.topicId(),job.snapshotId(),job.token());
        require(changed==1,"content claim expired or active catalog changed");
    }

    @Transactional
    public void fail(Job job) {
        jdbc.update("""
                UPDATE backend.topic_content_preparation SET status='FAILED',claim_token=null,lease_until=null,updated_at=now()
                WHERE topic_id=? AND source_snapshot_id=? AND status='PREPARING' AND claim_token=?
                """,job.topicId(),job.snapshotId(),job.token());
    }

    @Transactional
    public void retry(long userId,long requestId) {
        if (!enabled) throw new AccountException(409,"CONTENT_PREPARATION_UNAVAILABLE","지금은 개념 준비를 시작할 수 없어요.");
        var rows=jdbc.queryForList("SELECT topic_id FROM backend.topic_request WHERE id=? AND user_id=? AND status='BOOKS_READY'",requestId,userId);
        if (rows.isEmpty()) throw new ResourceNotFoundException("준비된 요청을 찾을 수 없습니다.");
        jdbc.update("""
                UPDATE backend.topic_content_preparation p SET status='QUEUED',report=null,report_hash=null,artifact_path=null,updated_at=now()
                FROM backend.catalog_selection_current c JOIN backend.catalog_selection_snapshot s ON s.snapshot_id=c.snapshot_id
                WHERE p.topic_id=? AND p.topic_id=c.topic_id AND p.source_snapshot_id=s.manifest->>'source_snapshot_id'
                    AND p.status IN ('FAILED','NEEDS_EVIDENCE')
                """,rows.getFirst().get("topic_id"));
    }
    static String hash(byte[] bytes) throws Exception { return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static void require(boolean valid,String message) { if (!valid) throw new IllegalArgumentException(message); }
}
