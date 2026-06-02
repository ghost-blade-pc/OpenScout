package com.openscout.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openscout.agent.runtime.AgentRuntimeMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationReportWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldWriteJsonAndMarkdownReportsWithRedaction() throws Exception {
        EvaluationMetrics metrics = new EvaluationMetrics(
                1.0, 1.0, true, 0, false, 0, 0, 0,
                false, 12, 0, 0, 0, List.of());
        EvaluationSampleResult sample = new EvaluationSampleResult(
                "case-1",
                "question",
                AgentRuntimeMode.MOCK,
                List.of("mock"),
                false,
                false,
                true,
                "trace-1",
                "token=secret-value",
                "answer",
                List.of(),
                12,
                metrics,
                Map.of("repo_search_mock", 1L),
                List.of("password=secret-value")
        );
        EvaluationReport report = EvaluationReport.from(
                new EvaluationEnvironment(true, "mock", false, false, true, true, true, false, false),
                List.of(sample)
        );
        EvaluationReportWriter writer = new EvaluationReportWriter(
                new ObjectMapper().findAndRegisterModules(),
                tempDir
        );

        EvaluationReportWriter.EvaluationReportFiles files = writer.write(report);

        assertThat(files.json()).exists();
        assertThat(files.markdown()).exists();
        String json = Files.readString(files.json());
        String markdown = Files.readString(files.markdown());
        assertThat(json).contains("token=<redacted>", "\"mode\" : \"MOCK\"").doesNotContain("secret-value");
        assertThat(markdown).contains("password=<redacted>", "| case-1 | MOCK | false | PASSED | trace-1 | 12 |")
                .doesNotContain("secret-value");
    }
}
