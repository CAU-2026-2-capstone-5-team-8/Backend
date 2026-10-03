package com.cau.capstone8.backend.book;

import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CatalogSummaryService {
    private final JdbcTemplate jdbc;
    public CatalogSummaryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record TopicCount(long id, String name, String mlTopicId, long bookCount,
                             long tocBookCount, long rankingCandidateCount, long conceptBookCount,
                             boolean assessmentReady) {}
    public record Summary(long bookCount, long tocBookCount, long rankingCandidateCount,
                          long conceptBookCount, List<TopicCount> topics) {}

    public Set<Long> readyTopicIds() {
        return Set.copyOf(jdbc.queryForList("""
                SELECT topic_id FROM (
                    SELECT topic_id,measurement_area FROM backend.question WHERE active
                    GROUP BY topic_id,measurement_area HAVING count(*)>=3
                ) areas GROUP BY topic_id HAVING count(*)=3
                """,Long.class));
    }

    public Summary summary() {
        var ready = readyTopicIds();
        var topics = jdbc.query("""
                WITH coverage AS (
                    SELECT DISTINCT ON (m.book_id,m.topic_id) m.book_id,m.topic_id,m.toc_entry_count
                    FROM backend.discovery_catalog_member m
                    JOIN backend.discovery_catalog_import i ON i.snapshot_id=m.snapshot_id
                    ORDER BY m.book_id,m.topic_id,i.created_at DESC,i.snapshot_id DESC
                )
                SELECT t.id,t.name,t.ml_topic_id,count(bt.book_id) books,
                    count(bt.book_id) FILTER (WHERE c.toc_entry_count>0) toc_books,
                    count(p.id) candidates,
                    count(p.id) FILTER (WHERE jsonb_array_length(p.covered_concepts)>0) concept_books
                FROM backend.topic t LEFT JOIN backend.book_topic bt ON bt.topic_id=t.id
                LEFT JOIN coverage c ON c.book_id=bt.book_id AND c.topic_id=t.id
                LEFT JOIN backend.book_ranking_v2_projection p ON p.book_id=bt.book_id AND p.topic_id=t.id AND p.active
                WHERE t.ml_topic_id IS NOT NULL GROUP BY t.id ORDER BY t.id
                """,(rs,n)->new TopicCount(rs.getLong("id"),rs.getString("name"),rs.getString("ml_topic_id"),
                rs.getLong("books"),rs.getLong("toc_books"),rs.getLong("candidates"),
                rs.getLong("concept_books"),ready.contains(rs.getLong("id"))));
        // Global counts are distinct books, even when another catalog assigns multiple topics.
        long toc = jdbc.queryForObject("""
                SELECT count(DISTINCT book_id) FROM (
                    SELECT DISTINCT ON (m.book_id,m.topic_id) m.book_id,m.toc_entry_count
                    FROM backend.discovery_catalog_member m
                    JOIN backend.discovery_catalog_import i ON i.snapshot_id=m.snapshot_id
                    ORDER BY m.book_id,m.topic_id,i.created_at DESC,i.snapshot_id DESC
                ) latest WHERE toc_entry_count>0
                """,Long.class);
        long candidates = jdbc.queryForObject("SELECT count(DISTINCT book_id) FROM backend.book_ranking_v2_projection WHERE active",Long.class);
        long concepts = jdbc.queryForObject("SELECT count(DISTINCT book_id) FROM backend.book_ranking_v2_projection WHERE active AND jsonb_array_length(covered_concepts)>0",Long.class);
        return new Summary(jdbc.queryForObject("SELECT count(*) FROM backend.book",Long.class),toc,candidates,concepts,topics);
    }
}
