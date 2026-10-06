package com.cau.capstone8.backend.topic;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TopicPreparationAdapterTest {
    @Test void transportsAnUnclassifiedNameThroughTheWorkerProtocol(@TempDir Path directory) throws Exception {
        // Echo the real stdin bytes through a subprocess; no provider call or mock serialization.
        var script=directory.resolve("echo-worker.sh");Files.writeString(script,"#!/bin/sh\ncat\n");
        var adapter=new TopicPreparationAdapter("/bin/sh",script.toString(),directory.resolve("workspace").toString());
        var job=new TopicPreparationService.Job(1,UUID.randomUUID(),"컴퓨터 통신망","",null,null);
        var input=adapter.run(job,"resolve",null);
        assertThat(input.path("name").asString()).isEqualTo("컴퓨터 통신망");
        assertThat(input.path("parentCode").isNull()).isTrue();
        assertThat(input.path("parentName").isNull()).isTrue();
        assertThat(input.path("scope").asString()).isEmpty();
        try(var files=Files.list(directory.resolve("workspace"))) {
            assertThat(files.toList()).isEmpty();
        }
    }
}
