package com.cau.capstone8.backend.assessment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cau.capstone8.backend.common.error.ResourceNotFoundException;
import com.cau.capstone8.backend.integration.ml.*;
import com.cau.capstone8.backend.topic.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AssessmentDiagnosticsServiceTest {
    final AssessmentSessionRepository sessions = mock(AssessmentSessionRepository.class);
    final AssessmentQuestionRepository questions = mock(AssessmentQuestionRepository.class);
    final AssessmentAnswerRepository answers = mock(AssessmentAnswerRepository.class);
    final TopicRepository topics = mock(TopicRepository.class);
    final MlGateway gateway = mock(MlGateway.class);
    final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);

    AssessmentDiagnosticsService service() {
        when(tx.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        return new AssessmentDiagnosticsService(sessions, questions, answers, topics, gateway, tx);
    }

    @Test void rejectsMissingOrUnfinishedSessionsBeforeMlCall() {
        var service = service();
        when(sessions.findById(42L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(42)).isInstanceOf(ResourceNotFoundException.class);
        when(sessions.findById(42L)).thenReturn(Optional.of(new AssessmentSession(1L, 2L)));
        assertThatThrownBy(() -> service.get(42)).isInstanceOf(AssessmentStateConflictException.class);
        verifyNoInteractions(gateway, questions, answers);
    }

    @Test void usesIssuedSnapshotsAndKeepsCompletionOnMlFailure() {
        var session = mock(AssessmentSession.class);
        when(session.getId()).thenReturn(42L);
        when(session.getUserId()).thenReturn(1L);
        when(session.getTopicId()).thenReturn(2L);
        when(session.getStatus()).thenReturn(AssessmentStatus.COMPLETED);
        when(sessions.findById(42L)).thenReturn(Optional.of(session));
        var topic = mock(Topic.class);
        when(topic.getCode()).thenReturn("operating-systems");
        when(topics.findById(2L)).thenReturn(Optional.of(topic));
        List<AssessmentQuestion> issued = new ArrayList<>();
        List<AssessmentAnswer> storedAnswers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            var q = mock(AssessmentQuestion.class);
            when(q.getId()).thenReturn((long) i + 1);
            when(q.getMeasurementAreaSnapshot()).thenReturn(MeasurementArea.values()[i]);
            when(q.getDifficultySnapshot()).thenReturn(i + 1);
            when(q.getConceptIdSnapshot()).thenReturn("process");
            when(q.getAnswerModeSnapshot()).thenReturn(i == 0 ? AnswerMode.SELF_REPORT : AnswerMode.MULTIPLE_CHOICE);
            when(q.getGeneratedQuestionIdSnapshot()).thenReturn(i == 0 ? null : "generated-" + i);
            issued.add(q);
            storedAnswers.add(i == 0 ? AssessmentAnswer.selfReport(1L, true)
                    : AssessmentAnswer.multipleChoice((long) i + 1, 0, false));
        }
        when(questions.findBySessionIdOrderByOrderIndex(42L)).thenReturn(issued);
        when(answers.findByAssessmentQuestionIdIn(List.of(1L, 2L, 3L))).thenReturn(storedAnswers);
        var evidence = mock(MlReaderDiagnostics.class);
        when(gateway.readerDiagnostics(any()))
                .thenThrow(new MlGatewayException("ML_UNAVAILABLE", "offline")).thenReturn(evidence);
        assertThatThrownBy(() -> service().get(42)).isInstanceOf(MlGatewayException.class);
        var captor = org.mockito.ArgumentCaptor.forClass(MlProfileRequest.class);
        verify(gateway).readerDiagnostics(captor.capture());
        assertThat(captor.getValue().answers().get(1).questionId()).isEqualTo("generated-1");
        assertThat(captor.getValue().answers().get(1).correct()).isFalse();
        verify(tx).commit(any());
        verify(session, never()).setStatus(any());
        verify(session, never()).failProcessing(any(), any());
        verify(sessions, never()).save(any());
        var recovered = service().get(42);
        assertThat(recovered.diagnostics()).isSameAs(evidence);
        assertThat(recovered.responseSources()).containsExactly(
                new AssessmentDiagnosticsService.ResponseSource("1", 1, "SELF_REPORT"),
                new AssessmentDiagnosticsService.ResponseSource("generated-1", 2, "MULTIPLE_CHOICE"),
                new AssessmentDiagnosticsService.ResponseSource("generated-2", 3, "MULTIPLE_CHOICE"));
    }
}
