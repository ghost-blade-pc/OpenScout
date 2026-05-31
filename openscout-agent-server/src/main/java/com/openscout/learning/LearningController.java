package com.openscout.learning;

import com.openscout.persistence.learning.LearningPlanPersistenceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/learning")
public class LearningController {

    private final LearningPlanPersistenceService persistenceService;

    public LearningController(LearningPlanPersistenceService persistenceService) {
        this.persistenceService = persistenceService;
    }

    @GetMapping("/goals/{goalId}")
    public ResponseEntity<LearningPlanResponse> getGoal(@PathVariable Long goalId) {
        return persistenceService.findPlan(goalId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PatchMapping("/tasks/{taskId}/status")
    public ResponseEntity<LearningTaskResponse> updateTaskStatus(
            @PathVariable Long taskId,
            @RequestBody UpdateTaskStatusRequest request) {
        LearningTaskStatus status = LearningTaskStatus.parse(request.status());
        return persistenceService.updateTaskStatus(taskId, status)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
