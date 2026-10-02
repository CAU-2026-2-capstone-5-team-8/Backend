package com.cau.capstone8.backend.profile;

import com.cau.capstone8.backend.account.AccountModels.Interest;
import com.cau.capstone8.backend.common.error.ResourceNotFoundException;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class ReadinessService {
    private final JdbcTemplate jdbc;
    private final JsonMapper json=JsonMapper.builder().build();
    public ReadinessService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public record Snapshot(long profileId,long sessionId,long topicId,String topicCode,String topicName,
            double vocabulary,double backgroundKnowledge,double comprehension,
            String calculationVersion,Map<String,Object> evidence,OffsetDateTime completedAt,
            int selfReportCount,int multipleChoiceCount,boolean demoCalculation) {}
    public record Overview(List<Snapshot> latestProfiles,List<Interest> unassessedInterests,
            long completedAssessmentCount,List<String> limitations) {}
    public record History(List<Snapshot> content,int page,int size,long totalElements,long totalPages) {}

    private static final String SELECT="""
            SELECT p.id AS profile_id,p.session_id,s.topic_id,t.code,t.name,
                   p.vocabulary,p.background_knowledge,p.comprehension,p.calculation_version,
                   p.evidence,s.completed_at,
                   (SELECT count(*) FROM backend.assessment_question q
                    JOIN backend.assessment_answer a ON a.assessment_question_id=q.id
                    WHERE q.session_id=s.id AND q.answer_mode_snapshot='SELF_REPORT') AS self_count,
                   (SELECT count(*) FROM backend.assessment_question q
                    JOIN backend.assessment_answer a ON a.assessment_question_id=q.id
                    WHERE q.session_id=s.id AND q.answer_mode_snapshot='MULTIPLE_CHOICE') AS choice_count
            FROM backend.reader_profile p
            JOIN backend.assessment_session s ON s.id=p.session_id
            JOIN backend.topic t ON t.id=s.topic_id
            """;
    private static final String COMPLETED=" WHERE s.user_id=? AND s.status='COMPLETED' ";

    public Overview overview(long user) {
        var profiles=jdbc.query("SELECT DISTINCT ON (topic_id) * FROM ("+SELECT+COMPLETED+
                ") snapshots ORDER BY topic_id,completed_at DESC,profile_id DESC",this::snapshot,user);
        var unassessed=jdbc.query("""
                SELECT t.id,t.code,t.name FROM backend.user_interest i
                JOIN backend.topic t ON t.id=i.topic_id
                WHERE i.user_id=? AND NOT EXISTS (
                    SELECT 1 FROM backend.assessment_session s JOIN backend.reader_profile p ON p.session_id=s.id
                    WHERE s.user_id=i.user_id AND s.topic_id=i.topic_id AND s.status='COMPLETED')
                ORDER BY t.id
                """,(rs,n)->new Interest(rs.getLong(1),rs.getString(2),rs.getString(3)),user);
        long count=jdbc.queryForObject("""
                SELECT count(*) FROM backend.reader_profile p JOIN backend.assessment_session s ON s.id=p.session_id
                WHERE s.user_id=? AND s.status='COMPLETED'
                """,Long.class,user);
        return new Overview(profiles,unassessed,count,List.of(
                "점수는 해당 진단 문항에 대한 응답 근거이며 전체 숙련도를 보장하지 않습니다.",
                "자기평가와 객관식 응답 수를 함께 확인하세요. 미진단 분야는 0점이 아닙니다.",
                "계산 버전·문항 구성·설정이 다른 진단 점수를 단순 비교해 성장으로 해석하지 마세요.",
                "상세 진단은 조회 시점 ML 설정으로 계산되며 아래 저장된 프로필을 변경하지 않습니다."));
    }

    public History history(long user,long topic,int page,int size) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM backend.topic WHERE id=?)",Boolean.class,topic))) {
            throw new ResourceNotFoundException("분야를 찾을 수 없습니다.");
        }
        long count=jdbc.queryForObject("""
                SELECT count(*) FROM backend.reader_profile p JOIN backend.assessment_session s ON s.id=p.session_id
                WHERE s.user_id=? AND s.topic_id=? AND s.status='COMPLETED'
                """,Long.class,user,topic);
        var rows=jdbc.query(SELECT+COMPLETED+
                " AND s.topic_id=? ORDER BY s.completed_at DESC,p.id DESC LIMIT ? OFFSET ?",
                this::snapshot,user,topic,size,(long)page*size);
        return new History(rows,page,size,count,(count+size-1)/size);
    }

    public long latestSession(long user,long topic) {
        return jdbc.query("""
                SELECT s.id FROM backend.reader_profile p JOIN backend.assessment_session s ON s.id=p.session_id
                WHERE s.user_id=? AND s.topic_id=? AND s.status='COMPLETED'
                ORDER BY s.completed_at DESC,p.id DESC LIMIT 1
                """,(rs,n)->rs.getLong(1),user,topic).stream().findFirst()
                .orElseThrow(()->new ResourceNotFoundException("완료된 독자 프로필을 찾을 수 없습니다."));
    }

    private Snapshot snapshot(ResultSet rs,int row) throws SQLException {
        String version=rs.getString("calculation_version");
        Map<String,Object> evidence=json.readValue(rs.getString("evidence"),new TypeReference<>() {});
        return new Snapshot(rs.getLong("profile_id"),rs.getLong("session_id"),rs.getLong("topic_id"),
                rs.getString("code"),rs.getString("name"),rs.getDouble("vocabulary"),
                rs.getDouble("background_knowledge"),rs.getDouble("comprehension"),version,evidence,
                rs.getObject("completed_at",OffsetDateTime.class),rs.getInt("self_count"),rs.getInt("choice_count"),
                version.startsWith("stub"));
    }
}
