package com.openscout.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

public class EvaluationReportWriter {

    private static final Pattern SECRET_PATTERN = Pattern.compile("(?i)(token|api[-_]?key|authorization|password|secret)\\s*[:=]\\s*[^\\s,;]+");

    private final ObjectMapper objectMapper;
    private final Path outputDir;

    public EvaluationReportWriter(ObjectMapper objectMapper, Path outputDir) {
        this.objectMapper = objectMapper;
        this.outputDir = outputDir;
    }

    public EvaluationReportFiles write(EvaluationReport report) {
        try {
            Files.createDirectories(outputDir);
            Path json = outputDir.resolve("agent-evaluation-report.json");
            Path markdown = outputDir.resolve("agent-evaluation-report.md");
            String jsonContent = objectMapper.copy()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(report);
            Files.writeString(json, redact(jsonContent));
            Files.writeString(markdown, redact(markdown(report)));
            return new EvaluationReportFiles(json, markdown);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write evaluation report to " + outputDir, ex);
        }
    }

    private String markdown(EvaluationReport report) {
        StringBuilder sb = new StringBuilder();
        EvaluationSummary summary = report.summary();
        sb.append("# OpenScout Agent Evaluation Report\n\n");
        sb.append("- generatedAt: `").append(report.generatedAt()).append("`\n");
        sb.append("- allRequiredPassed: `").append(summary.allRequiredPassed()).append("`\n");
        sb.append("- totalCases: `").append(summary.totalCases()).append("`\n");
        sb.append("- passedCases: `").append(summary.passedCases()).append("`\n");
        sb.append("- failedCases: `").append(summary.failedCases()).append("`\n");
        sb.append("- skippedCases: `").append(summary.skippedCases()).append("`\n\n");
        sb.append("## Metrics\n\n");
        sb.append("| Metric | Value |\n");
        sb.append("|---|---:|\n");
        sb.append("| averageRecommendationRelevance | ").append(format(summary.averageRecommendationRelevance())).append(" |\n");
        sb.append("| averageEvidenceCoverage | ").append(format(summary.averageEvidenceCoverage())).append(" |\n");
        sb.append("| maxLatencyMs | ").append(summary.maxLatencyMs()).append(" |\n");
        sb.append("| p50LatencyMs | ").append(summary.p50LatencyMs()).append(" |\n");
        sb.append("| p95LatencyMs | ").append(summary.p95LatencyMs()).append(" |\n");
        sb.append("| memoryHitCases | ").append(summary.memoryHitCases()).append(" |\n");
        sb.append("| fallbackCases | ").append(summary.fallbackCases()).append(" |\n");
        sb.append("| verifierIssueCases | ").append(summary.verifierIssueCases()).append(" |\n");
        sb.append("| githubSearchCalls | ").append(summary.githubSearchCalls()).append(" |\n");
        sb.append("| githubReadmeFetchCalls | ").append(summary.githubReadmeFetchCalls()).append(" |\n");
        sb.append("| githubCallSavings | ").append(summary.githubCallSavings()).append(" |\n\n");
        sb.append("## Samples\n\n");
        sb.append("| Case | Mode | Optional | Status | Trace | Latency | Failures |\n");
        sb.append("|---|---|---:|---|---|---:|---|\n");
        for (EvaluationSampleResult sample : report.samples()) {
            String status = sample.skipped() ? "SKIPPED" : sample.passed() ? "PASSED" : "FAILED";
            sb.append("| ").append(sample.caseId())
                    .append(" | ").append(sample.mode())
                    .append(" | ").append(sample.optional())
                    .append(" | ").append(status)
                    .append(" | ").append(sample.traceId() == null ? "" : sample.traceId())
                    .append(" | ").append(sample.latencyMs())
                    .append(" | ").append(String.join("; ", sample.failureReasons()))
                    .append(" |\n");
        }
        sb.append("\n## Boundary\n\n");
        sb.append("默认评测是 mock-first 本地评测，不代表生产 SLA、线上准确率或大规模 benchmark。");
        sb.append("真实 GitHub 和 LLM enabled 评测需要单独标注环境。\n");
        return sb.toString();
    }

    private String redact(String value) {
        return SECRET_PATTERN.matcher(value).replaceAll("$1=<redacted>");
    }

    private String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    public record EvaluationReportFiles(Path json, Path markdown) {
    }
}
