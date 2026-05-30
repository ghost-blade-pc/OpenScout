package com.openscout.persistence.trace;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openscout.trace.AgentTrace;
import com.openscout.trace.TraceToolCall;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class TracePersistenceService {

    private final AgentTraceMapper mapper;
    private final ObjectMapper objectMapper;

    public TracePersistenceService(AgentTraceMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public void insertTrace(AgentTrace trace) {
        AgentTraceEntity entity = toEntity(trace);
        mapper.insert(entity);
        trace.setId(entity.getId());
    }

    public void updateTrace(AgentTrace trace) {
        AgentTraceEntity entity = findEntityByTraceId(trace.getTraceId()).orElse(null);
        if (entity == null) {
            return;
        }
        entity.setToolCallsJson(serializeToolCalls(trace.getToolCalls()));
        entity.setScoreSummary(wrapScoreSummary(trace.getScoreSummary()));
        entity.setFinalAnswer(trace.getFinalAnswer());
        entity.setLatencyMs(trace.getLatencyMs());
        entity.setStatus(trace.getStatus());
        entity.setErrorMessage(trace.getErrorMessage());
        mapper.updateById(entity);
    }

    public Optional<AgentTrace> findByTraceId(String traceId) {
        return findEntityByTraceId(traceId).map(this::toDomain);
    }

    private Optional<AgentTraceEntity> findEntityByTraceId(String traceId) {
        QueryWrapper<AgentTraceEntity> qw = new QueryWrapper<>();
        qw.eq("trace_id", traceId);
        return Optional.ofNullable(mapper.selectOne(qw));
    }

    private AgentTraceEntity toEntity(AgentTrace trace) {
        AgentTraceEntity entity = new AgentTraceEntity();
        entity.setTraceId(trace.getTraceId());
        entity.setUserQuestion(trace.getUserQuestion());
        entity.setToolCallsJson(serializeToolCalls(trace.getToolCalls()));
        entity.setScoreSummary(wrapScoreSummary(trace.getScoreSummary()));
        entity.setFinalAnswer(trace.getFinalAnswer());
        entity.setLatencyMs(trace.getLatencyMs());
        entity.setStatus(trace.getStatus());
        entity.setErrorMessage(trace.getErrorMessage());
        return entity;
    }

    private AgentTrace toDomain(AgentTraceEntity entity) {
        AgentTrace trace = new AgentTrace(
                entity.getTraceId(),
                entity.getUserQuestion(),
                entity.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant()
        );
        trace.setId(entity.getId());
        List<TraceToolCall> toolCalls = parseToolCalls(entity.getToolCallsJson());
        trace.getToolCalls().addAll(toolCalls);
        trace.setScoreSummary(extractScoreSummary(entity.getScoreSummary()));
        trace.setFinalAnswer(entity.getFinalAnswer());
        trace.setLatencyMs(entity.getLatencyMs() != null ? entity.getLatencyMs() : 0);
        trace.setStatus(entity.getStatus());
        trace.setErrorMessage(entity.getErrorMessage());
        return trace;
    }

    private String serializeToolCalls(List<TraceToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(toolCalls);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    @SuppressWarnings("unchecked")
    private List<TraceToolCall> parseToolCalls(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(json, List.class);
            return raw.stream().map(item -> new TraceToolCall(
                    (String) item.getOrDefault("toolName", ""),
                    (String) item.getOrDefault("inputSummary", ""),
                    (String) item.getOrDefault("outputSummary", ""),
                    item.get("latencyMs") instanceof Number n ? n.longValue() : 0,
                    (String) item.getOrDefault("status", "SUCCESS"),
                    (String) item.getOrDefault("errorMessage", null),
                    null
            )).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private String wrapScoreSummary(String summary) {
        if (summary == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(Map.of("summary", summary));
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private String extractScoreSummary(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            Object summary = map.get("summary");
            return summary != null ? summary.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
