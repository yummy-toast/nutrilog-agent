package com.nutrilog.chat;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ExecutionBudgetTest {
    @Test void limitsAreIndependentAndCheckedBeforeExecution() {
        var budget = new ExecutionBudget();
        for (int i=0;i<8;i++) budget.beforeTool();
        assertEquals("TOOL_LIMIT_EXCEEDED", assertThrows(ChatFailure.class, budget::beforeTool).code());
        assertDoesNotThrow(budget::beforeModel);
        assertDoesNotThrow(new ExecutionBudget()::beforeTool);
    }
    @Test void capsModelAttempts() {
        var budget = new ExecutionBudget();
        for (int i=0;i<6;i++) budget.beforeModel();
        assertEquals("MODEL_LIMIT_EXCEEDED", assertThrows(ChatFailure.class, budget::beforeModel).code());
    }
    @Test void shrinksTimeoutAndExpiresUsingMonotonicClock() {
        var now = new AtomicLong();
        var budget = new ExecutionBudget(now::get, Duration.ofSeconds(60));
        assertEquals(Duration.ofSeconds(30), budget.beforeModel());
        now.set(Duration.ofSeconds(55).toNanos());
        assertEquals(Duration.ofSeconds(5), budget.beforeModel());
        now.set(Duration.ofSeconds(60).toNanos());
        assertEquals("EXECUTION_TIMEOUT", assertThrows(ChatFailure.class, budget::beforeTool).code());
    }
}
