package com.cau.capstone8.backend.topic;

import com.cau.capstone8.backend.account.AccountException;
import com.cau.capstone8.backend.common.error.ResourceNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Bounded generation only. Candidate readiness never imports a live diagnostic bank. */
@Service
public class TopicQuestionPreparationService {
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final JsonMapper json=JsonMapper.builder().build();
    public TopicQuestionPreparationService(JdbcTemplate jdbc,@Value("${topic-question-preparation.enabled:false}") boolean enabled) {
        this.jdbc=jdbc;this.enabled=enabled;
    }
    public record Job(long topicId,String snapshotId,String contentHash,String contentPath,int plannedCount,
                      UUID token,String slug,TopicPreparationService.Job adapterJob) {}
    @Transactional
    public Job claim() {
        if (!enabled) return null;
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext('topic-preparation-v1'))",Object.class);
        jdbc.update("UPDATE backend.topic_question_preparation SET status='FAILED',claim_token=null,lease_until=null,updated_at=now() WHERE status='GENERATING' AND lease_until<now()");
        jdbc.update("""
                INSERT INTO backend.topic_question_preparation(topic_id,source_snapshot_id,content_report_hash,planned_count)
                SELECT p.topic_id,p.source_snapshot_id,p.report_hash,(p.report->>'questionSpecCount')::int
                FROM backend.topic_content_preparation p JOIN backend.catalog_selection_current c ON c.topic_id=p.topic_id
                JOIN backend.catalog_selection_snapshot s ON s.snapshot_id=c.snapshot_id
                WHERE p.status='CONCEPTS_READY' AND s.manifest->>'source_snapshot_id'=p.source_snapshot_id
                ON CONFLICT DO NOTHING
                """);
        if (jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM backend.topic_catalog_refresh WHERE status='RUNNING') + (SELECT count(*) FROM backend.topic_discovery WHERE status='SEARCHING')
                     + (SELECT count(*) FROM backend.topic_request WHERE status IN ('CHECKING','COLLECTING'))
                    +(SELECT count(*) FROM backend.topic_content_preparation WHERE status='PREPARING')
                    +(SELECT count(*) FROM backend.topic_question_preparation WHERE status IN ('GENERATING','REVIEWING'))
                """,Integer.class)>0) return null;
        var rows=jdbc.queryForList("""
                SELECT q.*,COALESCE(p.workspace_id,p.source_request_id,-p.topic_id) AS source_request_id,p.artifact_path AS content_path,t.ml_topic_id,t.name,c.code,c.name AS parent_name
                FROM backend.topic_question_preparation q JOIN backend.topic_content_preparation p
                ON p.topic_id=q.topic_id AND p.source_snapshot_id=q.source_snapshot_id AND p.report_hash=q.content_report_hash
                JOIN backend.topic t ON t.id=q.topic_id JOIN backend.topic c ON c.id=t.parent_id
                JOIN backend.catalog_selection_current current ON current.topic_id=q.topic_id
                JOIN backend.catalog_selection_snapshot s ON s.snapshot_id=current.snapshot_id
                WHERE q.status='QUEUED' AND p.status='CONCEPTS_READY' AND s.manifest->>'source_snapshot_id'=q.source_snapshot_id
                ORDER BY p.source_request_id LIMIT 1 FOR UPDATE OF q SKIP LOCKED
                """);
        if (rows.isEmpty()) return null;
        var row=rows.getFirst();long topic=((Number)row.get("topic_id")).longValue();
        String snapshot=(String)row.get("source_snapshot_id"),hash=(String)row.get("content_report_hash");UUID token=UUID.randomUUID();
        jdbc.update("""
                UPDATE backend.topic_question_preparation SET status='GENERATING',claim_token=?,lease_until=now()+interval '5 minutes',attempts=attempts+1,updated_at=now()
                WHERE topic_id=? AND source_snapshot_id=? AND content_report_hash=?
                """,token,topic,snapshot,hash);
        return new Job(topic,snapshot,hash,(String)row.get("content_path"),((Number)row.get("planned_count")).intValue(),token,
                (String)row.get("ml_topic_id"),new TopicPreparationService.Job((row.get("source_request_id")==null ? -topic : ((Number)row.get("source_request_id")).longValue()),token,
                (String)row.get("name"),"",(String)row.get("code"),(String)row.get("parent_name")));
    }
    @Transactional
    public void publish(Job job,Path path,String expectedHash) throws Exception {
        var locked=jdbc.queryForList("""
                SELECT generated_count,artifact_path FROM backend.topic_question_preparation WHERE topic_id=? AND source_snapshot_id=? AND content_report_hash=?
                AND status='GENERATING' AND claim_token=? AND lease_until>now() FOR UPDATE
                """,job.topicId(),job.snapshotId(),job.contentHash(),job.token());
        require(!locked.isEmpty(),"question claim expired");
        byte[] data=Files.readAllBytes(path);require(data.length<=1024*1024 && TopicContentPreparationService.hash(data).equals(expectedHash),"generation report hash differs");
        var report=json.readTree(data);
        require("concept-generation-batch-v1".equals(report.path("contractVersion").asString()) && job.slug().equals(report.path("topicId").asString())
                && job.snapshotId().equals(report.path("sourceSnapshotId").asString()) && job.contentHash().equals(report.path("contentReportHash").asString()),"question source differs");
        require("pending".equals(report.path("contentReview").asString()) && report.path("diagnosisReady").isBoolean() && !report.path("diagnosisReady").asBoolean(),"invalid question approval claim");
        int count=report.path("generatedCount").asInt(-1),planned=report.path("plannedCount").asInt(-1);
        String state=report.path("status").asString();
        // A corrected generation implementation produces a NEW immutable run directory.
        // Preserve the previous run, while exposing progress of the newly versioned run.
        String previousPath=(String)locked.getFirst().get("artifact_path");
        boolean sameRun=previousPath==null || Path.of(previousPath).getParent().equals(path.getParent());
        int previousCount=((Number)locked.getFirst().get("generated_count")).intValue();
        require(planned==job.plannedCount() && count>(sameRun?previousCount:0) && count<=planned,"invalid generation progress");
        require((count==planned && state.equals("CANDIDATES_READY")) || (count<planned && state.equals("GENERATING")),"invalid generation status");
        var content=json.readTree(Files.readAllBytes(Path.of(job.contentPath())));
        require(TopicContentPreparationService.hash(Files.readAllBytes(Path.of(job.contentPath()))).equals(job.contentHash()),"content report changed");
        require(content.path("artifactHashes").path("blueprint.json").asString().equals(report.path("blueprintHash").asString()),"blueprint hash differs");
        var hashes=report.path("candidateHashes");require(hashes.isObject() && hashes.size()==count,"candidate count differs");
        for (String file:hashes.propertyNames()) {
            require(file.matches("q_[0-9a-f]{20}\\.json"),"invalid candidate filename");
            Path candidate=path.getParent().resolve(file).toRealPath();require(candidate.startsWith(path.getParent().toRealPath()),"candidate outside workspace");
            byte[] bytes=Files.readAllBytes(candidate);require(TopicContentPreparationService.hash(bytes).equals(hashes.path(file).asString()),"candidate hash differs");
            var question=json.readTree(bytes);
            require(job.slug().equals(question.path("topic_id").asString()) && report.path("blueprintHash").asString().equals(question.path("input_artifact_hash").asString())
                    && (question.path("question_spec_id").asString()+".json").equals(file) && "generated-question-v5".equals(question.path("generated_question_version").asString()),"candidate provenance differs");
        }
        require(jdbc.queryForObject("""
                SELECT count(*) FROM backend.topic_content_preparation p JOIN backend.catalog_selection_current c ON c.topic_id=p.topic_id
                JOIN backend.catalog_selection_snapshot s ON s.snapshot_id=c.snapshot_id
                WHERE p.topic_id=? AND p.source_snapshot_id=? AND p.report_hash=? AND p.status='CONCEPTS_READY' AND s.manifest->>'source_snapshot_id'=p.source_snapshot_id
                """,Integer.class,job.topicId(),job.snapshotId(),job.contentHash())==1,"active content changed");
        jdbc.update("""
                UPDATE backend.topic_question_preparation SET status=?,generated_count=?,report_hash=?,artifact_path=?,claim_token=null,lease_until=null,updated_at=now()
                WHERE topic_id=? AND source_snapshot_id=? AND content_report_hash=?
                """,count==planned?"CANDIDATES_READY":"QUEUED",count,expectedHash,path.toString(),job.topicId(),job.snapshotId(),job.contentHash());
    }
    @Transactional
    public void fail(Job job) {
        jdbc.update("""
                UPDATE backend.topic_question_preparation SET status='FAILED',claim_token=null,lease_until=null,updated_at=now()
                WHERE topic_id=? AND source_snapshot_id=? AND content_report_hash=? AND status='GENERATING' AND claim_token=?
                """,job.topicId(),job.snapshotId(),job.contentHash(),job.token());
    }
    @Transactional
    public void retry(long userId,long requestId) {
        if (!enabled) throw new AccountException(409,"QUESTION_PREPARATION_UNAVAILABLE","지금은 문제 생성을 시작할 수 없어요.");
        var rows=jdbc.queryForList("SELECT topic_id FROM backend.topic_request WHERE id=? AND user_id=? AND status='BOOKS_READY'",requestId,userId);
        if (rows.isEmpty()) throw new ResourceNotFoundException("준비된 요청을 찾을 수 없습니다.");
        jdbc.update("""
                UPDATE backend.topic_question_preparation q SET status=CASE WHEN generated_count=planned_count THEN 'CANDIDATES_READY' ELSE 'QUEUED' END,updated_at=now() FROM backend.topic_content_preparation p
                WHERE q.topic_id=? AND q.topic_id=p.topic_id AND q.source_snapshot_id=p.source_snapshot_id AND q.content_report_hash=p.report_hash
                AND p.status='CONCEPTS_READY' AND q.status='FAILED'
                """,rows.getFirst().get("topic_id"));
    }
    private static void require(boolean valid,String message) { if (!valid) throw new IllegalArgumentException(message); }
}
