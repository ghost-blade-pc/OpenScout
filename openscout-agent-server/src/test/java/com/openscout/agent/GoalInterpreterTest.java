package com.openscout.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openscout.config.OpenScoutProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GoalInterpreterTest {

    private GoalInterpreter goalInterpreter;

    @BeforeEach
    void setUp() {
        // ChatClient is null → LLM disabled, always uses fallback
        goalInterpreter = new GoalInterpreter(new OpenScoutProperties(), new ObjectMapper());
    }

    @Test
    void shouldFallbackWhenLlmDisabled() {
        GoalInterpretation result = goalInterpreter.interpret("我想学 Spring AI");
        assertEquals("我想学 Spring AI", result.keyword());
        assertEquals("", result.language());
    }

    @Test
    void shouldExtractJsonFromMarkdownBlock() {
        String json = GoalInterpreter.extractJson("```json\n{\"keyword\":\"spring\",\"language\":\"java\",\"domain\":\"ai\"}\n```");
        assertTrue(json.contains("\"keyword\""));
        assertFalse(json.contains("```"));
    }

    @Test
    void shouldExtractBareJson() {
        String json = GoalInterpreter.extractJson("{\"keyword\":\"spring\"}");
        assertEquals("{\"keyword\":\"spring\"}", json);
    }

    @Test
    void shouldReturnEmptyJsonOnNull() {
        String json = GoalInterpreter.extractJson(null);
        assertEquals("{}", json);
    }
}
