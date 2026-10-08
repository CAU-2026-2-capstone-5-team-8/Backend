package com.cau.capstone8.backend.topic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name="topic-preparation.enabled",havingValue="true")
public class TopicPreparationWorker {
    private static final Logger LOG=LoggerFactory.getLogger(TopicPreparationWorker.class);
    private final TopicCatalogRefreshService catalogs;
    private final TopicPreparationService jobs;
    private final TopicPreparationAdapter adapter;
    private final TopicContentPreparationService content;
    private final TopicQuestionPreparationService questions;
    private final TopicDiagnosticActivationService activation;
    private final TopicNameResolver resolver;
    private final TopicDiscoveryService discoveries;
    private final tools.jackson.databind.json.JsonMapper json=tools.jackson.databind.json.JsonMapper.builder().build();
    public TopicPreparationWorker(TopicPreparationService jobs,TopicPreparationAdapter adapter,TopicContentPreparationService content,TopicQuestionPreparationService questions,TopicNameResolver resolver,TopicDiscoveryService discoveries,TopicCatalogRefreshService catalogs,TopicDiagnosticActivationService activation) {
        this.activation=activation;
        this.catalogs=catalogs;
        this.jobs=jobs; this.adapter=adapter; this.content=content;this.questions=questions;this.resolver=resolver;this.discoveries=discoveries;
    }
    @Scheduled(fixedDelayString="${topic-preparation.poll-ms:3000}",initialDelayString="${topic-preparation.initial-delay-ms:5000}")
    public void tick() {
        var refreshJob=catalogs.claim();
        if(refreshJob!=null) {
            try {
                var result=adapter.run(refreshJob.adapterJob(),"catalog-refresh",refreshJob.slug(),refreshJob.input());
                catalogs.publish(refreshJob,result,adapter.refreshManifest(result,"importManifest",refreshJob.id()),
                        adapter.refreshManifest(result,"selectionManifest",refreshJob.id()));
            } catch(Exception e) {
                catalogs.fail(refreshJob);LOG.warn("catalog refresh failed kind={}",e.getClass().getSimpleName());
            }
            return;
        }
        var discoveryJob=discoveries.claim();
        if(discoveryJob!=null) {
            try {
                var input=new TopicPreparationService.Job(discoveryJob.userId(),discoveryJob.token(),discoveryJob.query(),"",null,null);
                var result=adapter.run(input,"discover",null,java.util.Map.of("discoveryId",discoveryJob.id().toString()));
                discoveries.complete(discoveryJob,result);
            } catch(Exception e) {
                discoveries.fail(discoveryJob);
                LOG.warn("book discovery failed kind={}",e.getClass().getSimpleName());
            }
            return;
        }
        var job=jobs.claim();
        if (job==null) { if (!prepareContent() && !prepareQuestions()) prepareReview(); return; }
        try {
            if(job.discoveryJson()!=null) {
                var scope=json.readTree(job.discoveryJson());
                job=jobs.classifyDiscovery(job,scope.path("parentCode").asString(),scope.path("parentName").asString());
                String slug=scope.path("slug").asString();
                if(!jobs.resolved(job,slug))return;
                var result=adapter.run(job,"collect",slug,java.util.Map.of("discovery",json.readValue(job.discoveryJson(),java.util.Map.class)));
                if(!"COLLECTED".equals(result.path("status").asString()) || !slug.equals(result.path("slug").asString()))throw new IllegalArgumentException("invalid discovery collection result");
                jobs.publish(job,slug,adapter.manifest(result,"importManifest",job),adapter.manifest(result,"selectionManifest",job),result.path("providers"));
                return;
            }
            var resolution=resolver.resolve(job.name(),job.selectedSlug());
            if (resolution.match()==null) {
                jobs.needsInput(job,resolution.message()); return;
            }
            var match=resolution.match();
            if(job.parentCode()!=null && !job.parentCode().equals(match.parentCode())) {
                jobs.needsInput(job,"분야 이름에 맞는 분류로 다시 요청해 주세요.");return;
            }
            if (job.parentCode()==null) job=jobs.classify(job,match.parentCode());
            String slug=match.slug();
            if (!jobs.resolved(job,slug)) return;
            var result=adapter.run(job,"collect",slug);
            if (!"COLLECTED".equals(result.path("status").asString()) || !slug.equals(result.path("slug").asString()))
                throw new IllegalArgumentException("invalid collection result");
            jobs.publish(job,slug,adapter.manifest(result,"importManifest",job),adapter.manifest(result,"selectionManifest",job),result.path("providers"));
            LOG.info("topic preparation completed request={}",job.id());
        } catch (Exception e) {
            jobs.fail(job);
            LOG.warn("topic preparation failed request={} kind={}",job.id(),e.getClass().getSimpleName());
        }
    }
    private boolean prepareContent() {
        var job=content.claim(); if (job==null) return false;
        try {
            var result=adapter.run(job.adapterJob(),"concepts",job.slug(),java.util.Map.of("sourceSnapshotId",job.snapshotId()));
            if ("PREPARING".equals(result.path("status").asString())
                    && job.slug().equals(result.path("slug").asString())
                    && job.snapshotId().equals(result.path("sourceSnapshotId").asString())) {
                content.defer(job); return true;
            }
            if (!java.util.List.of("CONCEPTS_READY","NEEDS_EVIDENCE").contains(result.path("status").asString())
                    || !job.slug().equals(result.path("slug").asString())
                    || !job.snapshotId().equals(result.path("sourceSnapshotId").asString()))
                throw new IllegalArgumentException("invalid content result");
            content.publish(job,adapter.manifest(result,"reportPath",job.adapterJob()),result.path("reportHash").asString());
            LOG.info("topic content preparation completed topic={}",job.topicId());
        } catch (Exception e) {
            content.fail(job);
            LOG.warn("topic content preparation failed topic={} kind={}",job.topicId(),e.getClass().getSimpleName());
        }
        return true;
    }
    private boolean prepareQuestions() {
        var job=questions.claim();if (job==null) return false;
        try {
            var result=adapter.run(job.adapterJob(),"questions",job.slug(),java.util.Map.of("sourceSnapshotId",job.snapshotId(),
                    "contentReportHash",job.contentHash(),"contentReportPath",job.contentPath()));
            if (!java.util.List.of("GENERATING","CANDIDATES_READY").contains(result.path("status").asString())
                    || !job.slug().equals(result.path("slug").asString()) || !job.snapshotId().equals(result.path("sourceSnapshotId").asString()))
                throw new IllegalArgumentException("invalid question result");
            questions.publish(job,adapter.manifest(result,"reportPath",job.adapterJob()),result.path("reportHash").asString());
            LOG.info("topic question batch completed topic={}",job.topicId());
        } catch (Exception e) {
            questions.fail(job);
            LOG.warn("topic question preparation failed topic={} kind={}",job.topicId(),e.getClass().getSimpleName());
        }
        return true;
    }
    private void prepareReview() {
        var job=activation.claim();if(job==null)return;
        var q=job.question();
        try {
            var result=adapter.run(q.adapterJob(),"review",q.slug(),java.util.Map.of("sourceSnapshotId",q.snapshotId(),
                "contentReportHash",q.contentHash(),"contentReportPath",q.contentPath(),
                "generationReportPath",job.generationPath(),"generationReportHash",job.generationHash(),"allowRevision",job.allowRevision()));
            activation.publish(job,adapter.manifest(result,"reportPath",q.adapterJob()),result.path("reportHash").asString());
            LOG.info("topic question review completed topic={} status={}",q.topicId(),result.path("status").asString());
        } catch(Exception e) {
            activation.fail(job);LOG.warn("topic question review failed topic={} kind={}",q.topicId(),e.getClass().getSimpleName());
        }
    }
}
