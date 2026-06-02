package com.openscout.evaluation;

import java.util.List;

public record EvaluationCaseSet(List<EvaluationCase> cases) {

    public EvaluationCaseSet {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }
}
