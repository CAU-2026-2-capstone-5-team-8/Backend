package com.cau.capstone8.backend.topic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Only trusted server configuration determines commands and filesystem roots. */
@Component
public class TopicPreparationAdapter {
    private final String python;
    private final Path script, workspace;
    private final JsonMapper json=JsonMapper.builder().build();
    public TopicPreparationAdapter(@Value("${topic-preparation.python:python3}") String python,
            @Value("${topic-preparation.script:scripts/topic-preparation.py}") String script,
            @Value("${topic-preparation.workspace:.local/topic-preparation}") String workspace) {
        this.python=python; this.script=Path.of(script).toAbsolutePath().normalize();
        this.workspace=Path.of(workspace).toAbsolutePath().normalize();
    }
    public JsonNode run(TopicPreparationService.Job job,String action,String slug) throws Exception {
        return run(job,action,slug,Map.of());
    }
    public JsonNode run(TopicPreparationService.Job job,String action,String slug,Map<String,Object> extra) throws Exception {
        Files.createDirectories(workspace);
        Path output=Files.createTempFile(workspace,"worker-",".json");
        var input=new LinkedHashMap<String,Object>(Map.of("requestId",job.id(),"action",action,
                "name",job.name(),"scope",job.scope()));
        input.put("parentCode",job.parentCode());input.put("parentName",job.parentName());
        if (slug!=null) input.put("slug",slug);
        input.putAll(extra);
        Process process=null;
        try {
            process=new ProcessBuilder(python,script.toString(),workspace.toString())
                    .redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try (var stdin=process.getOutputStream()) { stdin.write(json.writeValueAsBytes(input)); }
            if (!process.waitFor(180,TimeUnit.SECONDS)) throw new IllegalStateException("preparation step timed out");
            if (process.exitValue()!=0 || Files.size(output)>1024*1024) throw new IllegalStateException("preparation adapter failed");
            return json.readTree(Files.readAllBytes(output));
        } finally {
            if (process!=null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(output);
        }
    }
    public Path refreshManifest(JsonNode result,String field,java.util.UUID id) throws Exception {
        Path file=Path.of(result.path(field).asString()).toRealPath();
        Path expected=workspace.resolve("catalog-refresh").resolve(id.toString()).toRealPath();
        if(!file.startsWith(expected) || !Files.isRegularFile(file))throw new IllegalArgumentException("handoff outside refresh workspace");
        return file;
    }
    public Path manifest(JsonNode result,String field,TopicPreparationService.Job job) throws Exception {
        Path file=Path.of(result.path(field).asString()).toRealPath();
        Path expected=workspace.resolve("request-"+job.id()).toRealPath();
        if (!file.startsWith(expected) || !Files.isRegularFile(file)) throw new IllegalArgumentException("handoff outside job workspace");
        return file;
    }
}
