package com.cau.capstone8.backend.topic;

import com.cau.capstone8.backend.account.AccountException;
import com.cau.capstone8.backend.common.error.ResourceNotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class TopicRequestService {
    private final JdbcTemplate jdbc;
    private final TopicRepository topics;
    private final TopicNameResolver resolver;
    private final TopicDiscoveryService discoveries;
    private final boolean preparationEnabled;
    private final boolean contentEnabled;
    private final boolean questionsEnabled;
    private final JsonMapper json=JsonMapper.builder().build();
    public TopicRequestService(JdbcTemplate jdbc, TopicRepository topics,TopicNameResolver resolver,TopicDiscoveryService discoveries,
            @Value("${topic-preparation.enabled:false}") boolean preparationEnabled,
            @Value("${topic-content-preparation.enabled:false}") boolean contentEnabled,
            @Value("${topic-question-preparation.enabled:false}") boolean questionsEnabled) {
        this.jdbc = jdbc; this.topics = topics; this.resolver=resolver;this.discoveries=discoveries; this.preparationEnabled = preparationEnabled; this.contentEnabled=contentEnabled;
        this.questionsEnabled=questionsEnabled;
    }

    public record Input(Long categoryId, String name, String scope,String selectedSlug,java.util.UUID discoveryId,String providerCategory,String commonFieldId) {
        public Input(Long categoryId,String name,String scope,String selectedSlug,java.util.UUID discoveryId,String providerCategory){this(categoryId,name,scope,selectedSlug,discoveryId,providerCategory,null);}
        public Input(Long categoryId,String name,String scope,String selectedSlug){this(categoryId,name,scope,selectedSlug,null,null,null);}
    }
    public record Request(long id, Long categoryId, String categoryName, String name,
                          String scope, String status, Instant createdAt, String message,
                          Long topicId, int bookCount, Content content,List<TopicNameResolver.Candidate> candidates) {}
    public record Concept(String name,int bookCount) {}
    public record Content(String status,int conceptCount,int mappedBookCount,int questionSpecCount,List<Concept> concepts,QuestionPreparation questions) {}
    public record QuestionPreparation(String status,int generatedCount,int plannedCount,boolean repairPending) {}
    public record Retry(String scope, String name,String selectedSlug) {}
    public record Submission(Request request, Long existingTopicId, boolean replayed) {}

    private static final String SELECT = """
            SELECT r.id,r.category_id,t.name AS category_name,r.name,r.scope,r.status,r.created_at,
                   r.message,r.topic_id,r.book_count,p.status AS content_status,p.report::text AS content_report,
                   s.manifest->>'source_snapshot_id' AS source_snapshot,q.status AS question_status,q.generated_count,q.planned_count,q.review_report::text AS question_review_report
            FROM backend.topic_request r LEFT JOIN backend.topic t ON t.id=r.category_id
            LEFT JOIN backend.catalog_selection_current c ON c.topic_id=r.topic_id
            LEFT JOIN backend.catalog_selection_snapshot s ON s.snapshot_id=c.snapshot_id
            LEFT JOIN backend.topic_content_preparation p ON p.topic_id=r.topic_id AND p.source_snapshot_id=s.manifest->>'source_snapshot_id'
            LEFT JOIN backend.topic_question_preparation q ON q.topic_id=p.topic_id AND q.source_snapshot_id=p.source_snapshot_id AND q.content_report_hash=p.report_hash
            """;

    @Transactional
    public Submission submit(long userId, Input input) {
        if (input == null) throw invalid("분야 이름을 입력해 주세요.");
        String name = clean(input.name(), 2, 120, "분야 이름을 2~120자로 입력해 주세요.");
        String scope = clean(input.scope() == null ? "" : input.scope(), 0, 1000, "분야 설명은 1000자 이내로 입력해 주세요.");
        if (!name.matches(".*[\\p{L}\\p{N}].*")) throw invalid("분야 이름의 뜻을 알 수 있도록 입력해 주세요.");
        // Keep older clients compatible, while new requests need only a field name.
        if (input.categoryId()!=null) {
            var category = topics.findById(input.categoryId()).orElseThrow(() -> invalid("큰 분류를 다시 선택해 주세요."));
            if (category.getParentId() != null) throw invalid("학습 분야 대신 큰 분류를 선택해 주세요.");
        }
        String normalized = normalize(name);
        boolean isDiscovery=input.discoveryId()!=null || input.providerCategory()!=null || input.commonFieldId()!=null;
        if(isDiscovery && (input.selectedSlug()!=null || input.categoryId()!=null || !scope.isBlank())) throw invalid("검색 결과에서 분류를 다시 선택해 주세요.");
        String discovery=isDiscovery?discoveries.selection(userId,name,input.discoveryId(),input.providerCategory(),input.commonFieldId()):null;
        String discoveredSlug=discovery==null?null:json.readTree(discovery).path("slug").asString();
        var resolution=resolver.resolve(name,input.selectedSlug());
        var match=resolution.match();
        var all = topics.findAllByOrderByIdAsc();
        var existing = all.stream().filter(t -> (discoveredSlug!=null && discoveredSlug.equals(t.getMlTopicId())) || (discoveredSlug==null && (normalize(t.getName()).equals(normalized)
                || normalize(t.getCode()).equals(normalized)
                || (t.getMlTopicId() != null && (normalize(t.getMlTopicId()).equals(normalized)
                    || (match!=null && t.getMlTopicId().equals(match.slug()))))))).filter(t->input.selectedSlug()==null || t.getParentId()!=null).findFirst();
        if (existing.isPresent()) {
            var topic = existing.get();
            if (topic.getParentId() == null) throw invalid("선택한 큰 분류 안에서 더 구체적인 학습 분야를 입력해 주세요.");
            return new Submission(null, topic.getId(), false);
        }
        // Serialize submissions per account so retries and the pending limit stay atomic.
        if (jdbc.queryForList("SELECT id FROM backend.app_user WHERE id=? FOR UPDATE", userId).isEmpty())
            throw new AccountException(401, "AUTH_REQUIRED", "로그인이 필요합니다.");
        var prior = jdbc.query(SELECT + " WHERE coalesce(r.discovery_selection->>'slug','')=? AND r.user_id=? AND (?::text IS NOT NULL OR r.normalized_name=?) AND (?::bigint IS NULL OR r.category_id=? OR r.category_id IS NULL) ORDER BY r.id",
                this::map, discoveredSlug==null?"":discoveredSlug,userId, discoveredSlug, normalized, input.categoryId(), input.categoryId());
        if (!prior.isEmpty()) {
            if (input.selectedSlug()!=null) {
                var saved=prior.getFirst();
                return new Submission(retry(userId,saved.id(),new Retry(null,null,input.selectedSlug())),null,true);
            }
            if (input.scope()!=null && !prior.getFirst().scope().equals(scope)) throw new AccountException(409, "TOPIC_REQUEST_EXISTS",
                    "같은 분야의 요청이 이미 있어요. 내 요청에서 기존 내용을 확인해 주세요.");
            return new Submission(prior.getFirst(), null, true);
        }
        Integer count = jdbc.queryForObject("SELECT count(*) FROM backend.topic_request WHERE user_id=? AND status<>'BOOKS_READY'", Integer.class, userId);
        if (count != null && count >= 20) throw new AccountException(409, "TOPIC_REQUEST_LIMIT",
                "검토 대기 중인 요청이 20개예요. 기존 요청을 먼저 확인해 주세요.");
        Long id = jdbc.queryForObject("""
                INSERT INTO backend.topic_request(user_id,category_id,name,normalized_name,scope,status,selected_slug,discovery_selection)
                VALUES (?,?,?,?,?,?,?,cast(? as jsonb)) RETURNING id
                """, Long.class, userId, input.categoryId(), name, normalized, scope,
                preparationEnabled ? "QUEUED" : "NEEDS_REVIEW",input.selectedSlug(),discovery);
        return new Submission(detail(userId, id), null, false);
    }

    @Transactional(readOnly = true)
    public List<Request> list(long userId) {
        return jdbc.query(SELECT + " WHERE r.user_id=? ORDER BY r.id DESC LIMIT 20", this::map, userId);
    }

    @Transactional(readOnly = true)
    public Request detail(long userId, long id) {
        return jdbc.query(SELECT + " WHERE r.user_id=? AND r.id=?", this::map, userId, id)
                .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("요청을 찾을 수 없습니다."));
    }

    @Transactional
    public Request retry(long userId, long id, Retry input) {
        if (!preparationEnabled) throw new AccountException(409, "PREPARATION_UNAVAILABLE", "지금은 분야 준비를 시작할 수 없어요. 잠시 후 다시 확인해 주세요.");
        jdbc.queryForList("SELECT id FROM backend.app_user WHERE id=? FOR UPDATE",userId);
        // Lock and verify ownership before touching a job or its clarification.
        var owned = jdbc.queryForList("SELECT id,selected_slug,discovery_selection::text FROM backend.topic_request WHERE id=? AND user_id=? FOR UPDATE", id, userId);
        if (owned.isEmpty()) throw new ResourceNotFoundException("요청을 찾을 수 없습니다.");
        var request = detail(userId, id);
        if (!List.of("FAILED", "NEEDS_INPUT", "NEEDS_REVIEW").contains(request.status())) return request;
        String name=input==null || input.name()==null ? request.name()
                : clean(input.name(),2,120,"분야 이름을 2~120자로 입력해 주세요.");
        if (!name.matches(".*[\\p{L}\\p{N}].*")) throw invalid("분야 이름의 뜻을 알 수 있도록 입력해 주세요.");
        String normalized=normalize(name);
        if(owned.getFirst().get("discovery_selection")!=null) {
            if(!normalized.equals(normalize(request.name())) || (input!=null && input.selectedSlug()!=null)) throw invalid("바꾼 분야 이름으로 책을 다시 검색해 주세요.");
            jdbc.update("UPDATE backend.topic_request SET status='QUEUED',message=null,claim_token=null,lease_until=null,updated_at=now() WHERE id=?",id);
            return detail(userId,id);
        }
        boolean changed=!normalized.equals(normalize(request.name()));
        String selected=input!=null && input.selectedSlug()!=null ? input.selectedSlug()
                : changed?null:(String)owned.getFirst().get("selected_slug");
        resolver.resolve(name,selected);
        if (selected==null && topics.findAllByOrderByIdAsc().stream().anyMatch(t->t.getParentId()==null &&
                (normalize(t.getName()).equals(normalized) || normalize(t.getCode()).equals(normalized))))
            throw invalid("더 구체적인 학습 분야 이름을 입력해 주세요.");
        boolean changedChoice=!java.util.Objects.equals(selected,owned.getFirst().get("selected_slug"));
        if (changed && !jdbc.queryForList("SELECT id FROM backend.topic_request WHERE user_id=? AND normalized_name=? AND id<>?",userId,normalized,id).isEmpty())
            throw new AccountException(409,"TOPIC_REQUEST_EXISTS","이미 접수한 분야예요. 내 요청에서 확인해 주세요.");
        String scope = input == null || input.scope() == null ? request.scope()
                : clean(input.scope(), 0, 1000, "분야 설명은 1000자 이내로 입력해 주세요.");
        if (changed || changedChoice) scope="";
        jdbc.update("""
                UPDATE backend.topic_request SET name=?,normalized_name=?,category_id=?,scope=?,selected_slug=?,status='QUEUED',message=null,resolved_slug=null,
                    claim_token=null,lease_until=null,updated_at=now() WHERE id=?
                """, name,normalized,changed||changedChoice?null:request.categoryId(),scope,selected,id);
        return detail(userId, id);
    }

    private Request map(ResultSet row, int index) throws SQLException {
        String state=row.getString("content_status"),snapshot=row.getString("source_snapshot");
        Content content=null;
        if (state==null && contentEnabled && snapshot!=null && (snapshot.startsWith("topic-request-") || snapshot.startsWith("book-search-"))) state="QUEUED";
        if (state!=null) {
            var report=json.readTree(row.getString("content_report")==null ? "{}" : row.getString("content_report"));
            var concepts=new java.util.ArrayList<Concept>();
            for (var concept:report.path("concepts")) if (concept.path("bookCount").asInt()>0)
                concepts.add(new Concept(concept.path("name").asString(),concept.path("bookCount").asInt()));
            String questionState=row.getString("question_status");QuestionPreparation questions=null;
            if (questionState!=null) {
                var review=json.readTree(row.getString("question_review_report")==null?"{}":row.getString("question_review_report"));
                questions=new QuestionPreparation(questionState,row.getInt("generated_count"),row.getInt("planned_count"),questionState.equals("REVIEW_BLOCKED") && review.path("revisionPossible").asBoolean());
            }
            else if (questionsEnabled && state.equals("CONCEPTS_READY")) questions=new QuestionPreparation("QUEUED",0,report.path("questionSpecCount").asInt(),false);
            content=new Content(state,report.path("conceptCount").asInt(),report.path("mappedBookCount").asInt(),
                    report.path("questionSpecCount").asInt(),List.copyOf(concepts),questions);
        }
        return new Request(row.getLong("id"), row.getObject("category_id",Long.class), row.getString("category_name"),
                row.getString("name"), row.getString("scope"), row.getString("status"), row.getTimestamp("created_at").toInstant(),
                row.getString("message"), row.getObject("topic_id", Long.class), row.getInt("book_count"),content,
                "NEEDS_INPUT".equals(row.getString("status"))?resolver.inspect(row.getString("name")).candidates():List.of());
    }
    static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Z}_-]+", "");
    }
    private String clean(String value, int min, int max, String message) {
        if (value == null) throw invalid(message);
        String clean = Normalizer.normalize(value, Normalizer.Form.NFC).strip().replaceAll("[\\s\\p{Z}]+", " ");
        if (clean.length() < min || clean.length() > max || clean.chars().anyMatch(Character::isISOControl)) throw invalid(message);
        return clean;
    }
    private AccountException invalid(String message) {
        return new AccountException(400, "INVALID_TOPIC_REQUEST", message);
    }
}
