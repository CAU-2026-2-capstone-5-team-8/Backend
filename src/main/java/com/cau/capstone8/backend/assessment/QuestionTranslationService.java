package com.cau.capstone8.backend.assessment;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class QuestionTranslationService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper json = JsonMapper.builder().build();
    private record StoredText(String passage, String prompt, List<String> choices) {}
    public record DisplayTranslation(String language, String passage, String prompt, List<String> choices) {}
    public record Job(long questionId, String sourceHash, UUID token, Map<String,Object> input) {}
    public QuestionTranslationService(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.tx=new TransactionTemplate(manager);
    }
    public Job claim() {
        return tx.execute(ignored -> {
            jdbc.update("""
                INSERT INTO backend.question_display_translation(question_id,source_hash,source_text)
                SELECT q.id,q.generated_content_hash,jsonb_build_object('passage',q.passage,'prompt',q.prompt,'choices',q.choices)
                FROM backend.question q WHERE q.active AND q.version='generated-question-v5'
                AND q.generated_content_hash IS NOT NULL
                AND NOT EXISTS (SELECT 1 FROM backend.question_display_translation t
                    WHERE t.question_id=q.id AND t.source_hash=q.generated_content_hash AND t.language='ko')
                ON CONFLICT DO NOTHING
                """);
            var rows=jdbc.queryForList("""
                SELECT question_id,source_hash,source_text::text FROM backend.question_display_translation
                WHERE attempts<3 AND (status='QUEUED' OR (status='TRANSLATING' AND lease_until<now()))
                ORDER BY question_id DESC LIMIT 1 FOR UPDATE SKIP LOCKED
                """);
            if(rows.isEmpty())return null;
            var row=rows.getFirst(); var token=UUID.randomUUID();
            long id=((Number)row.get("question_id")).longValue();String hash=(String)row.get("source_hash");
            jdbc.update("""
                UPDATE backend.question_display_translation SET status='TRANSLATING',claim_token=?,lease_until=now()+interval '3 minutes',attempts=attempts+1
                WHERE question_id=? AND source_hash=? AND language='ko'
                """,token,id,hash);
            var source=json.readValue((String)row.get("source_text"),StoredText.class);
            Map<String,Object> input=new java.util.LinkedHashMap<>();
            input.put("passage",source.passage());input.put("prompt",source.prompt());input.put("choices",source.choices());
            input.put("questionId",id);input.put("sourceHash",hash);
            return new Job(id,hash,token,input);
        });
    }
    public void publish(Job job, tools.jackson.databind.JsonNode result) {
        if(!"READY".equals(result.path("status").asString()) || !job.sourceHash().equals(result.path("sourceHash").asString())
            || !"ko".equals(result.path("language").asString()) || !"question-translation-ko-v1".equals(result.path("version").asString())
            || result.path("prompt").asString().isBlank() || result.path("model").asString().isBlank()
            || result.path("choices").size()!=4) throw new IllegalArgumentException("invalid translation result");
        var sources=jdbc.queryForList("""
            SELECT source_text::text FROM backend.question_display_translation
            WHERE question_id=? AND source_hash=? AND language='ko' AND status='TRANSLATING'
                AND claim_token=? AND lease_until>now()
            """,String.class,job.questionId(),job.sourceHash(),job.token());
        if(sources.isEmpty())return;
        var source=json.readValue(sources.getFirst(),StoredText.class);
        var passage=result.path("passage");
        boolean validPassage=source.passage()==null ? passage.isNull()
                : passage.isString() && !passage.asString().isBlank();
        boolean validChoices=result.path("choices").isArray() && source.choices()!=null
                && result.path("choices").size()==source.choices().size();
        for(var choice:result.path("choices"))validChoices &= choice.isString() && !choice.asString().isBlank();
        if(!validPassage || !validChoices || !result.path("prompt").isString())
            throw new IllegalArgumentException("invalid translation result");
        var text=json.createObjectNode();text.set("passage",result.path("passage"));text.set("prompt",result.path("prompt"));text.set("choices",result.path("choices"));
        jdbc.update("""
            UPDATE backend.question_display_translation SET status='READY',translated_text=?::jsonb,model=?,claim_token=null,lease_until=null
            WHERE question_id=? AND source_hash=? AND language='ko' AND status='TRANSLATING' AND claim_token=? AND lease_until>now()
            """,json.writeValueAsString(text),result.path("model").asString(),job.questionId(),job.sourceHash(),job.token());
    }
    public void fail(Job job) {
        jdbc.update("""
            UPDATE backend.question_display_translation SET status=CASE WHEN attempts<3 THEN 'QUEUED' ELSE 'FAILED' END,claim_token=null,lease_until=null
            WHERE question_id=? AND source_hash=? AND status='TRANSLATING' AND claim_token=?
            """,job.questionId(),job.sourceHash(),job.token());
    }
    public DisplayTranslation display(AssessmentQuestion question) {
        if(question.getGeneratedContentHashSnapshot()==null)return null;
        var rows=jdbc.queryForList("""
            SELECT source_text::text,translated_text::text FROM backend.question_display_translation
            WHERE question_id=? AND source_hash=? AND language='ko' AND status='READY'
            """,question.getQuestionId(),question.getGeneratedContentHashSnapshot());
        if(rows.isEmpty())return null;
        var source=json.readValue((String)rows.getFirst().get("source_text"),StoredText.class);
        if(!Objects.equals(source.passage(),question.getPassageSnapshot())
            || !source.prompt().equals(question.getPromptSnapshot())
            || !source.choices().equals(question.getChoicesSnapshot()))return null;
        var text=json.readValue((String)rows.getFirst().get("translated_text"),StoredText.class);
        return new DisplayTranslation("ko",text.passage(),text.prompt(),text.choices());
    }
}
