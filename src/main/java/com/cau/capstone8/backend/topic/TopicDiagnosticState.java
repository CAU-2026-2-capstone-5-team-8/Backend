package com.cau.capstone8.backend.topic;

import java.util.*;
import com.cau.capstone8.backend.common.error.ResourceNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TopicDiagnosticState {
    private final JdbcTemplate jdbc;
    public TopicDiagnosticState(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Transactional(readOnly=true)
    public Map<String,Object> get(long topic) {
        var rows=jdbc.queryForList("""
            SELECT t.ml_topic_id,p.status AS content_status,q.status AS question_status,p.report,q.generated_count,q.planned_count,q.review_report,EXISTS (SELECT 1 FROM backend.topic_content_preparation previous WHERE previous.topic_id=t.id) AS managed
            FROM backend.topic t LEFT JOIN backend.catalog_selection_current c ON c.topic_id=t.id
            LEFT JOIN backend.catalog_selection_snapshot s ON s.snapshot_id=c.snapshot_id
            LEFT JOIN backend.topic_content_preparation p ON p.topic_id=t.id AND p.source_snapshot_id=s.manifest->>'source_snapshot_id'
            LEFT JOIN backend.topic_question_preparation q ON q.topic_id=p.topic_id AND q.source_snapshot_id=p.source_snapshot_id AND q.content_report_hash=p.report_hash
            WHERE t.id=?
            """,topic);
        if(rows.isEmpty())throw new ResourceNotFoundException("분야를 찾을 수 없습니다.");
        var r=rows.getFirst();String slug=(String)r.get("ml_topic_id");
        boolean managed=slug!=null && (slug.startsWith("field-") || slug.startsWith("search-") || Boolean.TRUE.equals(r.get("managed")));
        if(!managed)return Map.of("topicId",topic,"status","UNMANAGED","generatedCount",0,"plannedCount",0,"reviewedCount",0,"approvedCount",0);
        String status=r.get("question_status") instanceof String q?q:r.get("content_status") instanceof String p?p:"QUEUED";
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        var review=json.readTree(r.get("review_report")==null?"{}":r.get("review_report").toString());
        return Map.of("topicId",topic,"status",status,"generatedCount",r.get("generated_count")==null?0:r.get("generated_count"),
            "plannedCount",r.get("planned_count")==null?0:r.get("planned_count"),"reviewedCount",review.path("reviewedCount").asInt(),"approvedCount",review.path("approvedCount").asInt(),
            "repairPending",status.equals("REVIEW_BLOCKED") && review.path("revisionPossible").asBoolean());
    }
    @Transactional
    public Map<String,Object> retry(long topic) {
        get(topic);
        jdbc.update("""
            UPDATE backend.topic_content_preparation p SET status='QUEUED',report=null,report_hash=null,artifact_path=null,updated_at=now()
            FROM backend.catalog_selection_current c JOIN backend.catalog_selection_snapshot s ON s.snapshot_id=c.snapshot_id
            WHERE p.topic_id=? AND p.topic_id=c.topic_id AND p.source_snapshot_id=s.manifest->>'source_snapshot_id'
                AND p.status='FAILED' AND p.updated_at<now()-interval '10 seconds'
            """,topic);
        jdbc.update("""
            UPDATE backend.topic_question_preparation q SET status=CASE WHEN q.generated_count=q.planned_count THEN 'CANDIDATES_READY' ELSE 'QUEUED' END,updated_at=now()
            FROM backend.catalog_selection_current c JOIN backend.catalog_selection_snapshot s ON s.snapshot_id=c.snapshot_id
            JOIN backend.topic_content_preparation p ON p.topic_id=c.topic_id AND p.source_snapshot_id=s.manifest->>'source_snapshot_id'
            WHERE q.topic_id=? AND q.topic_id=p.topic_id AND q.source_snapshot_id=p.source_snapshot_id
                AND q.content_report_hash=p.report_hash AND p.status='CONCEPTS_READY' AND q.status='FAILED'
                AND q.updated_at<now()-interval '10 seconds'
            """,topic);
        return get(topic);
    }
    @Transactional(readOnly=true)
    public boolean ready(long topic) {
        var status=get(topic).get("status");return status.equals("UNMANAGED") || status.equals("ACTIVE");
    }
}
