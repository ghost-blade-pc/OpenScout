package com.openscout.evaluation;

import com.openscout.agent.AgentAskRequest;
import com.openscout.agent.AgentAskResponse;
import com.openscout.agent.AgentCallException;
import com.openscout.agent.AgentService;
import com.openscout.agent.runtime.AgentRuntimeMode;
import com.openscout.config.OpenScoutProperties;
import com.openscout.trace.AgentTrace;
import com.openscout.trace.TraceService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AgentEvaluationRunner {

    private final AgentService agentService;
    private final TraceService traceService;
    private final OpenScoutProperties properties;
    private final EvaluationMetricCalculator metricCalculator;

    public AgentEvaluationRunner(AgentService agentService,
                                 TraceService traceService,
                                 OpenScoutProperties properties,
                                 EvaluationMetricCalculator metricCalculator) {
        this.agentService = agentService;
        this.traceService = traceService;
        this.properties = properties;
        this.metricCalculator = metricCalculator;
    }

    public EvaluationReport run(List<EvaluationCase> cases) {
        return run(cases, false);
    }

    public EvaluationReport run(List<EvaluationCase> cases, boolean includeOptional) {
        List<EvaluationSampleResult> samples = new ArrayList<>();
        for (EvaluationCase evaluationCase : cases) {
            if (evaluationCase.optional() && !includeOptional) {
                samples.add(EvaluationSampleResult.skipped(evaluationCase));
                continue;
            }
            samples.add(runCase(evaluationCase));
        }
        return EvaluationReport.from(EvaluationEnvironment.from(properties), samples);
    }

    private EvaluationSampleResult runCase(EvaluationCase evaluationCase) {
        boolean previousMockAgent = properties.isMockAgent();
        properties.setMockAgent(evaluationCase.mode() == AgentRuntimeMode.MOCK);
        try {
            AgentAskResponse response = agentService.ask(new AgentAskRequest(evaluationCase.question(), null, null));
            AgentTrace trace = traceService.find(response.traceId()).orElse(null);
            EvaluationMetrics metrics = metricCalculator.calculate(evaluationCase, response, trace, null);
            Map<String, Long> eventCounts = metricCalculator.eventCounts(trace);
            return EvaluationSampleResult.from(evaluationCase, response, metrics, eventCounts, null);
        } catch (AgentCallException ex) {
            AgentTrace trace = traceService.find(ex.getTraceId()).orElse(null);
            EvaluationMetrics metrics = metricCalculator.calculate(evaluationCase, null, trace, ex.getMessage());
            Map<String, Long> eventCounts = metricCalculator.eventCounts(trace);
            return EvaluationSampleResult.from(evaluationCase, null, metrics, eventCounts, ex.getMessage());
        } finally {
            properties.setMockAgent(previousMockAgent);
        }
    }
}
