package com.cau.capstone8.backend.topic;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TopicPreparationWorkerTest {
    final TopicPreparationService jobs=mock(TopicPreparationService.class);
    final TopicPreparationAdapter adapter=mock(TopicPreparationAdapter.class);
    final TopicContentPreparationService content=mock(TopicContentPreparationService.class);
    final TopicQuestionPreparationService questions=mock(TopicQuestionPreparationService.class);
    final TopicDiagnosticActivationService activation=mock(TopicDiagnosticActivationService.class);
    final TopicPreparationWorker worker=new TopicPreparationWorker(jobs,adapter,content,questions,
            mock(TopicNameResolver.class),mock(TopicDiscoveryService.class),mock(TopicCatalogRefreshService.class),activation);
    final TopicPreparationService.Job input=new TopicPreparationService.Job(-1,UUID.randomUUID(),"Synthetic","","CS","Computer science");
    final TopicQuestionPreparationService.Job question=new TopicQuestionPreparationService.Job(1,"snapshot","hash","content.json",9,
            UUID.randomUUID(),"synthetic",input);

    @Test void readyReviewRunsBeforeMoreGenerationOrTranslation() throws Exception {
        var review=new TopicDiagnosticActivationService.Job(question,"generation.json","hash",false);
        when(activation.claim()).thenReturn(review);
        when(adapter.run(eq(input),eq("review"),eq("synthetic"),anyMap()))
                .thenReturn(new JsonMapper().readTree("{\"status\":\"REVIEW_PENDING\",\"reportHash\":\"hash\"}"));
        when(adapter.manifest(any(),eq("reportPath"),eq(input))).thenReturn(Path.of("review.json"));
        worker.tick();
        verify(activation).publish(eq(review),eq(Path.of("review.json")),eq("hash"));
        verify(questions,never()).claim();verify(content,never()).claim();
    }

    @Test void readyQuestionsRunWithoutWaitingForUnrelatedTranslations() throws Exception {
        when(questions.claim()).thenReturn(question);
        when(adapter.run(eq(input),eq("questions"),eq("synthetic"),anyMap()))
                .thenReturn(new JsonMapper().readTree("{\"status\":\"GENERATING\",\"slug\":\"synthetic\",\"sourceSnapshotId\":\"snapshot\",\"reportHash\":\"hash\"}"));
        when(adapter.manifest(any(),eq("reportPath"),eq(input))).thenReturn(Path.of("generation.json"));
        worker.tick();
        verify(questions).publish(eq(question),eq(Path.of("generation.json")),eq("hash"));
        verify(content,never()).claim();
    }

    @Test void translationContinuesWhenNoLaterStageIsReady() throws Exception {
        var job=new TopicContentPreparationService.Job(1,"snapshot",UUID.randomUUID(),"synthetic",input);
        when(content.claim()).thenReturn(job);
        when(adapter.run(eq(input),eq("concepts"),eq("synthetic"),anyMap()))
                .thenReturn(new JsonMapper().readTree("{\"status\":\"PREPARING\",\"slug\":\"synthetic\",\"sourceSnapshotId\":\"snapshot\"}"));
        worker.tick();
        verify(content).defer(job);
    }
}
