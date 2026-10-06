package com.cau.capstone8.backend.assessment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@EnableScheduling
@ConditionalOnProperty(name="question-translation.enabled",havingValue="true")
public class QuestionTranslationWorker {
    private static final Logger LOG=LoggerFactory.getLogger(QuestionTranslationWorker.class);
    private final QuestionTranslationService translations;
    private final String python;
    private final Path script,workspace;
    private final JsonMapper json=JsonMapper.builder().build();
    public QuestionTranslationWorker(QuestionTranslationService translations,
        @Value("${question-translation.python:python3}") String python,
        @Value("${question-translation.script:scripts/question-translation.py}") String script,
        @Value("${topic-preparation.workspace:.local/topic-preparation}") String workspace) {
        this.translations=translations;this.python=python;this.script=Path.of(script).toAbsolutePath();this.workspace=Path.of(workspace).toAbsolutePath();
    }
    @Scheduled(fixedDelayString="${question-translation.poll-ms:5000}",initialDelay=5000)
    public void tick() {
        var job=translations.claim();if(job==null)return;
        Process process=null;Path output=null;
        try {
            Files.createDirectories(workspace);output=Files.createTempFile(workspace,"translation-",".json");
            process=new ProcessBuilder(python,script.toString(),workspace.toString()).redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try(var stdin=process.getOutputStream()){stdin.write(json.writeValueAsBytes(job.input()));}
            if(!process.waitFor(110,TimeUnit.SECONDS) || Files.size(output)>1024*1024)
                throw new IllegalStateException("translation adapter failed");
            if(process.exitValue()!=0) {
                var failure=json.readTree(Files.readAllBytes(output));
                String kind=failure.path("kind").asString();
                if(kind.matches("[A-Za-z]{1,80}"))
                    LOG.warn("translation adapter rejected question={} kind={} providerStatus={}",job.questionId(),kind,failure.path("providerStatus").asInt(0));
                throw new IllegalStateException("translation adapter failed");
            }
            translations.publish(job,json.readTree(Files.readAllBytes(output)));
        } catch(Exception e) {
            translations.fail(job);LOG.warn("question translation failed question={} kind={}",job.questionId(),e.getClass().getSimpleName());
        } finally {
            if(process!=null && process.isAlive())process.destroyForcibly();
            if(output!=null)try{Files.deleteIfExists(output);}catch(Exception ignored){}
        }
    }
}
