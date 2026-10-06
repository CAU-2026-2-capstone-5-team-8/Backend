package com.cau.capstone8.backend.topic;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeGraphPublicationTest {
    @Test void restartPreservesOriginalGraphHashButRejectsChangedContent(@TempDir Path directory) throws Exception {
        Path path=directory.resolve("runtime-graph.json");
        byte[] original="{\"topic\":\"synthetic\",\"nodes\":[\"a\",\"b\"]}".getBytes(StandardCharsets.UTF_8);
        byte[] reordered="{\"nodes\":[\"a\",\"b\"],\"topic\":\"synthetic\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(TopicDiagnosticActivationService.saveImmutableGraph(path,original)).isEqualTo(original);
        assertThat(TopicDiagnosticActivationService.saveImmutableGraph(path,reordered)).isEqualTo(original);
        assertThat(Files.readAllBytes(path)).isEqualTo(original);
        assertThatThrownBy(()->TopicDiagnosticActivationService.saveImmutableGraph(path,"{\"topic\":\"other\"}".getBytes(StandardCharsets.UTF_8)))
            .hasMessageContaining("runtime graph already differs");
        assertThat(Files.readAllBytes(path)).isEqualTo(original);
    }
}
