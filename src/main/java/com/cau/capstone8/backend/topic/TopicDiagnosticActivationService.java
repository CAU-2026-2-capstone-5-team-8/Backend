package com.cau.capstone8.backend.topic;

import com.cau.capstone8.backend.assessment.ApprovedQuestionImportService;
import com.cau.capstone8.backend.integration.ml.MlGateway;
import com.cau.capstone8.backend.learning.LearningEvidence;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Source-bound AI review and atomic bank/projection publication. No human approval is invented. */
@Service
public class TopicDiagnosticActivationService {
    private final JdbcTemplate jdbc;
    private final ApprovedQuestionImportService importer;
    private final MlGateway ml;
    private final Path registry;
    private final boolean enabled;
    private final JsonMapper json=JsonMapper.builder().enable(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    public TopicDiagnosticActivationService(JdbcTemplate jdbc,ApprovedQuestionImportService importer,MlGateway ml,
            @Value("${topic-question-preparation.enabled:false}") boolean enabled,
            @Value("${topic-preparation.workspace:.local/topic-preparation}") String workspace) {
        this.jdbc=jdbc;this.importer=importer;this.ml=ml;this.enabled=enabled;
        registry=Path.of(workspace).toAbsolutePath().normalize().resolve("runtime-topics");
    }
    public record Job(TopicQuestionPreparationService.Job question,String generationPath,String generationHash,boolean allowRevision) {}
    @Transactional
    public Job claim() {
        if(!enabled)return null;
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext('topic-preparation-v1'))",Object.class);
        jdbc.update("UPDATE backend.topic_question_preparation SET status='FAILED',claim_token=null,lease_until=null,updated_at=now() WHERE status='REVIEWING' AND lease_until<now()");
        if(jdbc.queryForObject("""
            SELECT (SELECT count(*) FROM backend.topic_catalog_refresh WHERE status='RUNNING')
                +(SELECT count(*) FROM backend.topic_discovery WHERE status='SEARCHING')
                +(SELECT count(*) FROM backend.topic_request WHERE status IN ('CHECKING','COLLECTING'))
                +(SELECT count(*) FROM backend.topic_content_preparation WHERE status='PREPARING')
                +(SELECT count(*) FROM backend.topic_question_preparation WHERE status IN ('GENERATING','REVIEWING'))
            """,Integer.class)>0)return null;
        var rows=jdbc.queryForList("""
            SELECT q.*,COALESCE(p.workspace_id,p.source_request_id,-p.topic_id) AS source_request_id,p.artifact_path AS content_path,t.ml_topic_id,t.name,c.code,c.name AS parent_name
            FROM backend.topic_question_preparation q JOIN backend.topic_content_preparation p
                ON p.topic_id=q.topic_id AND p.source_snapshot_id=q.source_snapshot_id AND p.report_hash=q.content_report_hash
            JOIN backend.topic t ON t.id=q.topic_id JOIN backend.topic c ON c.id=t.parent_id
            JOIN backend.catalog_selection_current current ON current.topic_id=q.topic_id
            JOIN backend.catalog_selection_snapshot s ON s.snapshot_id=current.snapshot_id
            WHERE (q.status IN ('CANDIDATES_READY','REVIEW_PENDING') OR (q.status='REVIEW_BLOCKED' AND q.review_report->>'revisionPossible'='true')) AND p.status='CONCEPTS_READY'
                AND s.manifest->>'source_snapshot_id'=q.source_snapshot_id
            ORDER BY q.updated_at,q.topic_id LIMIT 1 FOR UPDATE OF q SKIP LOCKED
            """);
        if(rows.isEmpty())return null;
        var r=rows.getFirst();long topic=((Number)r.get("topic_id")).longValue();UUID token=UUID.randomUUID();
        String source=(String)r.get("source_snapshot_id"),hash=(String)r.get("content_report_hash");
        jdbc.update("""
            UPDATE backend.topic_question_preparation SET status='REVIEWING',claim_token=?,lease_until=now()+interval '5 minutes',updated_at=now()
            WHERE topic_id=? AND source_snapshot_id=? AND content_report_hash=?
            """,token,topic,source,hash);
        long request=r.get("source_request_id")==null?-topic:((Number)r.get("source_request_id")).longValue();
        return new Job(new TopicQuestionPreparationService.Job(topic,source,hash,(String)r.get("content_path"),
            ((Number)r.get("planned_count")).intValue(),token,(String)r.get("ml_topic_id"),
            new TopicPreparationService.Job(request,token,(String)r.get("name"),"",(String)r.get("code"),(String)r.get("parent_name"))),
            (String)r.get("artifact_path"),(String)r.get("report_hash"),"REVIEW_BLOCKED".equals(r.get("status")));
    }
    @Transactional(rollbackFor=Exception.class)
    public void publish(Job job,Path path,String digest) throws Exception {
        var q=job.question();
        require(!jdbc.queryForList("""
            SELECT topic_id FROM backend.topic_question_preparation WHERE topic_id=? AND source_snapshot_id=? AND content_report_hash=?
                AND status='REVIEWING' AND claim_token=? AND lease_until>now() FOR UPDATE
            """,q.topicId(),q.snapshotId(),q.contentHash(),q.token()).isEmpty(),"review lease expired");
        // Lock the current selection so refresh cannot replace it during publication.
        require(!jdbc.queryForList("""
            SELECT c.topic_id FROM backend.catalog_selection_current c JOIN backend.catalog_selection_snapshot s ON s.snapshot_id=c.snapshot_id
            JOIN backend.topic_content_preparation p ON p.topic_id=c.topic_id AND p.source_snapshot_id=s.manifest->>'source_snapshot_id'
            WHERE c.topic_id=? AND p.source_snapshot_id=? AND p.report_hash=? FOR UPDATE OF c,p
            """,q.topicId(),q.snapshotId(),q.contentHash()).isEmpty(),"active content changed");
        var report=checked(path,digest);var generation=checked(Path.of(job.generationPath()),job.generationHash());
        var content=checked(Path.of(q.contentPath()),q.contentHash());
        require("automatic-content-review-v1".equals(report.path("contractVersion").asString())
            && q.slug().equals(report.path("topicId").asString()) && q.snapshotId().equals(report.path("sourceSnapshotId").asString())
            && q.contentHash().equals(report.path("contentReportHash").asString())
            && job.generationHash().equals(report.path("generationReportHash").asString())
            && generation.path("candidateHashes").equals(report.path("candidateHashes"))
            && generation.path("blueprintHash").equals(report.path("blueprintHash"))
            && report.path("plannedCount").asInt()==q.plannedCount()
            && "ai".equals(report.path("reviewerType").asString()) && "content-only".equals(report.path("validationScope").asString())
            && report.path("diagnosisReady").isBoolean() && !report.path("diagnosisReady").asBoolean(),"review source differs");
        var rendering=report.path("renderValidation");
        require("PASSED".equals(rendering.path("status").asString())
            && rendering.path("questions").asInt()==q.plannedCount()
            && rendering.path("fields").asInt()==q.plannedCount()*6
            && rendering.path("invalidMath").asInt(-1)==0
            && rendering.path("rendererHash").asString().matches("sha256:[0-9a-f]{64}")
            && rendering.path("validatorHash").asString().matches("sha256:[0-9a-f]{64}"),"question rendering not validated");
        Path generationDir=Path.of(job.generationPath()).toRealPath().getParent(),reviewDir=path.toRealPath().getParent();
        var specs=new HashMap<String,JsonNode>();
        var blueprint=json.readTree(checkedBytes(Path.of(q.contentPath()).getParent().resolve("blueprint.json"),generation.path("blueprintHash").asString()));
        for(var spec:blueprint.path("question_specs"))require(specs.put(spec.path("question_id").asString(),spec)==null,"duplicate question spec");
        require(specs.size()==q.plannedCount(),"review blueprint count differs");
        var candidateIds=new HashSet<String>();
        for(String file:generation.path("candidateHashes").propertyNames()) {
            require(file.matches("q_[0-9a-f]{20}\\.json"),"invalid candidate name");
            var candidate=checked(generationDir.resolve(file),generation.path("candidateHashes").path(file).asString());
            var spec=specs.get(candidate.path("question_spec_id").asString());require(spec!=null,"unknown candidate spec");
            for(String field:List.of("topic_id","primary_concept","ability","cognitive_operation","measurement_context","target_difficulty","assessment_objective","evidence_references"))
                require(spec.path(field).equals(candidate.path(field)),"candidate objective differs");
            require(candidateIds.add(candidate.path("generated_question_id").asString()),"duplicate candidate ID");
        }
        Path reviews=reviewDir.resolve("reviews.jsonl");
        require(TopicContentPreparationService.hash(Files.readAllBytes(reviews)).equals(report.path("reviewsHash").asString()),"reviews changed");
        var ids=new HashSet<String>();var rejectedIds=new HashSet<String>();int approved=0;
        for(String line:Files.readAllLines(reviews).stream().filter(l->!l.isBlank()).toList()) {
            var ai=json.readTree(line);var judgment=ai.path("review");String id=judgment.path("generated_question_id").asString();
            require("ai-question-review-v1".equals(ai.path("review_version").asString()) && "ai".equals(ai.path("reviewer_type").asString())
                && report.path("reviewerName").asString().equals(ai.path("reviewer_name").asString())
                && "content-only".equals(ai.path("validation_scope").asString()) && candidateIds.contains(id) && ids.add(id),"review identity differs");
            if("approve".equals(judgment.path("status").asString()) && judgment.path("correct").asBoolean()
                && judgment.path("difficulty_appropriate").asBoolean() && judgment.path("concept_alignment").asInt()>=4
                && judgment.path("distractor_quality").asInt()>=4 && judgment.path("explanation_quality").asInt()>=4
                && judgment.path("notes").asString().strip().length()>=10)approved++;
            else rejectedIds.add(id);
        }
        require(ids.size()==report.path("reviewedCount").asInt() && approved==report.path("approvedCount").asInt(),"review count differs");
        Path contentDir=Path.of(q.contentPath()).toRealPath().getParent();
        for(String file:content.path("artifactHashes").propertyNames())checkedBytes(contentDir.resolve(file),content.path("artifactHashes").path(file).asString());
        var outline=json.readTree(Files.readAllBytes(contentDir.resolve("outline.json")));
        var graphReview=checked(reviewDir.resolve("graph-review.json"),report.path("graphReviewHash").asString());
        var edges=new HashSet<String>();for(var edge:outline.path("edges"))edges.add(edge.path("prerequisite").asString()+"/"+edge.path("dependent").asString());
        var reviewedEdges=new HashSet<String>();boolean graphApproved=graphReview.path("appropriate").asBoolean();
        for(var edge:graphReview.path("edges")) {
            require(reviewedEdges.add(edge.path("prerequisite").asString()+"/"+edge.path("dependent").asString()),"duplicate reviewed edge");
            graphApproved &= edge.path("appropriate").asBoolean();
        }
        require(edges.equals(reviewedEdges) && graphApproved==report.path("graphApproved").asBoolean(),"graph review differs");
        String status=report.path("status").asString();
        boolean complete=ids.equals(candidateIds);
        require((status.equals("REVIEW_PENDING") && !complete) || (status.equals("REVIEW_BLOCKED") && complete && (approved!=q.plannedCount() || !graphApproved))
            || (status.equals("APPROVED") && complete && approved==q.plannedCount() && graphApproved),"invalid review status");
        boolean revisionPossible=status.equals("REVIEW_BLOCKED") && graphApproved && generation.path("revisionRound").asInt()==0 && rejectedIds.size()>=1 && rejectedIds.size()<=2;
        require(report.path("revisionPossible").asBoolean()==revisionPossible,"revision eligibility differs");
        if(report.has("revisedGenerationPath")) {
            require(job.allowRevision() && revisionPossible,"revision round not eligible");
            Path revisedPath=Path.of(report.path("revisedGenerationPath").asString()).toRealPath();
            require(revisedPath.startsWith(generationDir.resolve("revisions")) && revisedPath.getFileName().toString().equals("generation-report.json"),"revision outside generation workspace");
            String revisedHash=report.path("revisedGenerationHash").asString();var revised=checked(revisedPath,revisedHash);
            require("concept-generation-batch-v1".equals(revised.path("contractVersion").asString()) && "CANDIDATES_READY".equals(revised.path("status").asString())
                && "pending".equals(revised.path("contentReview").asString()) && revised.path("diagnosisReady").isBoolean() && !revised.path("diagnosisReady").asBoolean()
                && q.slug().equals(revised.path("topicId").asString()) && q.snapshotId().equals(revised.path("sourceSnapshotId").asString())
                && q.contentHash().equals(revised.path("contentReportHash").asString()) && revised.path("revisionRound").asInt()==1
                && revised.path("plannedCount").asInt()==q.plannedCount() && revised.path("generatedCount").asInt()==q.plannedCount()
                && job.generationHash().equals(revised.path("previousGenerationReportHash").asString())
                && TopicContentPreparationService.hash(Files.readAllBytes(reviewDir.resolve("review-report.json"))).equals(revised.path("previousReviewReportHash").asString())
                && generation.path("blueprintHash").equals(revised.path("blueprintHash"))
                && generation.path("candidateHashes").propertyNames().equals(revised.path("candidateHashes").propertyNames()),"revision source differs");
            var revisedIds=new HashSet<String>();
            for(String file:generation.path("candidateHashes").propertyNames()) {
                var original=checked(generationDir.resolve(file),generation.path("candidateHashes").path(file).asString());
                var candidate=checked(revisedPath.getParent().resolve(file),revised.path("candidateHashes").path(file).asString());
                var spec=specs.get(original.path("question_spec_id").asString());
                for(String field:List.of("question_spec_id","topic_id","primary_concept","ability","cognitive_operation","measurement_context","target_difficulty","assessment_objective","evidence_references"))
                    require(spec.path(field.equals("question_spec_id")?"question_id":field).equals(candidate.path(field)),"revision objective differs");
                if(rejectedIds.contains(original.path("generated_question_id").asString())) {
                    require(!candidateIds.contains(candidate.path("generated_question_id").asString()),"rejected content was not revised");
                } else require(generation.path("candidateHashes").path(file).equals(revised.path("candidateHashes").path(file)),"accepted content was changed");
                require(revisedIds.add(candidate.path("generated_question_id").asString()),"duplicate revised candidate");
            }
            jdbc.update("""
                UPDATE backend.topic_question_preparation SET status='CANDIDATES_READY',report_hash=?,artifact_path=?,review_report=null,review_report_hash=null,
                    review_artifact_path=null,claim_token=null,lease_until=null,updated_at=now() WHERE topic_id=? AND source_snapshot_id=? AND content_report_hash=?
                """,revisedHash,revisedPath.toString(),q.topicId(),q.snapshotId(),q.contentHash());
            return;
        }
        if(status.equals("APPROVED")) {
            activate(q,content,outline,report,path,digest,generation,generationDir,reviews);
            status="ACTIVE";
        }
        jdbc.update("""
            UPDATE backend.topic_question_preparation SET status=?,review_report=?::jsonb,review_report_hash=?,review_artifact_path=?,
                claim_token=null,lease_until=null,updated_at=now() WHERE topic_id=? AND source_snapshot_id=? AND content_report_hash=?
            """,status,json.writeValueAsString(report),digest,path.toString(),q.topicId(),q.snapshotId(),q.contentHash());
    }
    private void activate(TopicQuestionPreparationService.Job q,JsonNode content,JsonNode outline,JsonNode review,Path reportPath,
            String reportHash,JsonNode generation,Path generationDir,Path reviews) throws Exception {
        Map<String,String> labels=new TreeMap<>();
        for(var c:outline.path("concepts"))labels.put(q.slug()+":"+c.path("id").asString(),c.path("name").asString());
        List<Map<String,Object>> edges=new ArrayList<>();
        for(var e:outline.path("edges"))edges.add(Map.of("topic",q.slug(),"prerequisite",q.slug()+":"+e.path("prerequisite").asString(),"dependent",q.slug()+":"+e.path("dependent").asString()));
        var graph=new LinkedHashMap<String,Object>(Map.of("contract_version","topic-runtime-graph-v1","topic_id",q.slug(),
            "source_snapshot_id",q.snapshotId(),"content_report_hash",q.contentHash(),"review_report_hash",reportHash,
            "nodes",Map.of(q.slug(),labels.keySet()),"labels",labels,"accepted_edges",edges,
            "concept_graph_version","concept-graph-v1","concept_graph_hash",content.path("artifactHashes").path("configs/concept_graph.yaml").asString()));
        graph.put("graph_review_version","ai-concept-graph-review-v1");graph.put("graph_review_hash",review.path("graphReviewHash").asString());graph.put("reviewer_type","ai");
        Path graphPath=reportPath.getParent().resolve("runtime-graph.json");byte[] graphBytes=json.writeValueAsBytes(graph);
        graphBytes=saveImmutableGraph(graphPath,graphBytes);
        Files.createDirectories(registry);
        Path pointer=registry.resolve(q.slug()+".json"),temporary=Files.createTempFile(registry,"publish-",".tmp");
        Files.write(temporary,json.writeValueAsBytes(Map.of("topicId",q.slug(),"graphPath",graphPath.toString(),"graphHash",TopicContentPreparationService.hash(graphBytes))));
        // Immutable graph + atomic pointer. Until DB commit no bank is exposed; a failed probe is retryable.
        Files.move(temporary,pointer,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        var served=ml.conceptGraph(q.slug());
        require(q.slug().equals(served.get("topicId")) && TopicContentPreparationService.hash(graphBytes).equals(served.get("configHash")),"ML runtime graph not loaded");
        var probe=ml.learningFit(Map.of("modelVersion","concept-learning-v2","topicId",q.slug(),"ability","meaning", "observations",List.of(),"limit",1,
            "candidateBooks",List.of(Map.of("bookId","runtime-readiness-check","coveredConcepts",List.of(labels.keySet().iterator().next()),
                "sourceArtifactVersion",q.snapshotId(),"sourceArtifactHash",content.path("artifactHashes").path("book-profiles.jsonl").asString()))));
        require("concept-learning-v2".equals(probe.get("modelVersion")) && q.slug().equals(probe.get("topicId"))
            && TopicContentPreparationService.hash(graphBytes).equals(probe.get("configHash")),"ML learning model not ready");
        publishProjections(q,content,labels.keySet());
        String reviewText=Files.readString(reviews);
        // This transaction either imports the entire reviewed bank and book mappings or none of them.
        for(String file:generation.path("candidateHashes").propertyNames())importer.importApproved(Files.readString(generationDir.resolve(file)),reviewText);
        List<String> activeIds=new ArrayList<>();for(String file:generation.path("candidateHashes").propertyNames())activeIds.add(json.readTree(Files.readAllBytes(generationDir.resolve(file))).path("generated_question_id").asString());
        // Old questions and issued assessments remain stored; only the current topic bank changes.
        jdbc.update("UPDATE backend.question SET active=false WHERE topic_id=? AND active AND NOT (generated_question_id=ANY(?))",q.topicId(),activeIds.toArray(String[]::new));
    }
    private void publishProjections(TopicQuestionPreparationService.Job q,JsonNode content,Set<String> nodes) throws Exception {
        Path dir=Path.of(q.contentPath()).getParent(),canonical=Path.of(content.path("canonicalDataPath").asString());
        var toc=new HashMap<String,JsonNode>();var sources=new HashMap<String,JsonNode>();
        for(String line:Files.readAllLines(canonical.resolve("toc.jsonl")))if(!line.isBlank()){var row=json.readTree(line);toc.put(row.path("book_id").asString()+"/"+row.path("toc_entry_id").asString(),row);}
        for(String line:Files.readAllLines(canonical.resolve("sources.jsonl")))if(!line.isBlank()){var row=json.readTree(line);sources.put(row.path("source_id").asString(),row);}
        var books=new HashMap<String,Long>();jdbc.query("""
            SELECT b.id,b.ml_book_id FROM backend.discovery_catalog_member m JOIN backend.book b ON b.id=m.book_id WHERE m.topic_id=? AND m.snapshot_id=?
            """,rs->{books.put(rs.getString(2),rs.getLong(1));},q.topicId(),q.snapshotId());
        jdbc.update("UPDATE backend.book_ranking_v2_projection SET active=false WHERE topic_id=? AND active",q.topicId());
        int mapped=0;var seen=new HashSet<String>();
        for(String line:Files.readAllLines(dir.resolve("book-profiles.jsonl")))if(!line.isBlank()) {
            var profile=json.readTree(line);String book=profile.path("book_id").asString();
            require(books.containsKey(book) && seen.add(book) && q.slug().equals(profile.path("topic_id").asString()),"projection book differs");
            List<Map<String,Object>> concepts=new ArrayList<>();
            for(var covered:profile.path("covered_concepts")) {
                String concept=covered.path("concept_id").asString();require(nodes.contains(concept),"projection concept differs");
                List<Map<String,Object>> evidence=new ArrayList<>();
                for(var mapping:profile.path("toc_mappings"))if(concept.equals(mapping.path("concept_id").asString())) {
                    String id=mapping.path("toc_entry_id").asString(),sourceId=mapping.path("source_id").asString();
                    var row=toc.get(book+"/"+id);var source=sources.get(sourceId);
                    require(row!=null && source!=null && sourceId.equals(row.path("source_id").asString()) && book.equals(source.path("book_id").asString()),"projection TOC differs");
                    var ref=new LinkedHashMap<String,Object>(Map.of("concept_id",concept,"evidence_id",id,"source_id",sourceId,
                        "evidence_type","toc_unspecified","edition_relation","canonical_record",
                        "toc_path",json.convertValue(mapping.path("toc_path"),List.class),"matching_alias",mapping.path("matching_alias").asString(),
                        "match_method",mapping.path("match_method").asString(),"provenance_hash",content.path("canonicalFileHashes").path("toc.jsonl").asString()));
                    ref.put("source_url",source.path("url").isString()?source.path("url").asString():null);evidence.add(ref);
                }
                require(!evidence.isEmpty(),"projection requires real TOC evidence");
                concepts.add(Map.of("concept",concept,"weight",covered.path("coverage_weight").asDouble(),"evidence",evidence));
            }
            LearningEvidence.fromConcepts(concepts);
            if(concepts.isEmpty())continue;mapped++;
            String version="auto-diagnostic-"+q.contentHash().substring(7,31);
            jdbc.update("""
                INSERT INTO backend.book_ranking_v2_projection(book_id,topic_id,version,active,topic_distribution,covered_concepts,prerequisite_concepts,
                    lexical_difficulty,syntactic_complexity,concept_density,prerequisite_demand,config_version,config_hash,source_artifact_version,source_artifact_hash)
                VALUES (?,?,?,true,?::jsonb,?::jsonb,'[]'::jsonb,null,null,null,null,'topic-content-preparation-v1',?,?,?)
                ON CONFLICT(book_id,topic_id,version) DO UPDATE SET active=true
                """,books.get(book),q.topicId(),version,json.writeValueAsString(Map.of(q.slug(),1.0)),json.writeValueAsString(concepts),q.contentHash(),q.snapshotId(),
                content.path("artifactHashes").path("book-profiles.jsonl").asString());
        }
        require(mapped==content.path("mappedBookCount").asInt() && seen.equals(books.keySet()) && mapped>0,"projection coverage differs");
    }
    @Transactional
    public void fail(Job job) {
        var q=job.question();jdbc.update("""
            UPDATE backend.topic_question_preparation SET status='FAILED',claim_token=null,lease_until=null,updated_at=now()
            WHERE topic_id=? AND source_snapshot_id=? AND content_report_hash=? AND status='REVIEWING' AND claim_token=?
            """,q.topicId(),q.snapshotId(),q.contentHash(),q.token());
    }
    private byte[] checkedBytes(Path path,String digest) throws Exception {
        byte[] data=Files.readAllBytes(path);require(data.length<=16*1024*1024 && TopicContentPreparationService.hash(data).equals(digest),"artifact hash differs");return data;
    }
    static byte[] saveImmutableGraph(Path path,byte[] proposed) throws Exception {
        if(Files.exists(path)) {
            byte[] saved=Files.readAllBytes(path);var mapper=JsonMapper.builder().build();
            require(mapper.readTree(saved).equals(mapper.readTree(proposed)),"runtime graph already differs");
            // Map iteration order must not change immutable bytes/hash on restart.
            return saved;
        }
        Files.write(path,proposed,StandardOpenOption.CREATE_NEW);return proposed;
    }
    private JsonNode checked(Path path,String digest) throws Exception {return json.readTree(checkedBytes(path,digest));}
    private static void require(boolean valid,String message){if(!valid)throw new IllegalArgumentException(message);}
}
