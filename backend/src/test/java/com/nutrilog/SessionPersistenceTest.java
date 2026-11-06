package com.nutrilog;

import com.nutrilog.session.SessionDirectory;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class SessionPersistenceTest {
    @TempDir Path data;

    @Test void restoresCompletedTurnsAndOwnershipAndPersistsDeletion() throws Exception {
        String url = "jdbc:h2:file:" + data.resolve("sessions");
        UUID id;
        var state = new SessionDirectory.State(List.of(168935L), 168935L, new BigDecimal("200.25"), "USER_CONFIRMED");
        try (var directory = new SessionDirectory(url)) {
            id = directory.create("alice", "早餐").sessionId();
            directory.executeTurn("alice", id, "熟米饭", old -> new SessionDirectory.CompletedTurn("已查询", state));
            assertThrows(IllegalStateException.class, () -> directory.executeTurn("alice", id, "失败轮次", old -> {
                throw new IllegalStateException("模拟失败");
            }));
        }
        try (var restarted = new SessionDirectory(url)) {
            assertEquals("早餐", restarted.get("alice", id).title());
            var snapshot = restarted.snapshot("alice", id);
            assertEquals(List.of(new SessionDirectory.Turn("熟米饭", "已查询")), snapshot.turns());
            assertEquals(state, snapshot.state());
            assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> restarted.snapshot("bob", id)).getStatusCode().value());
            restarted.delete("alice", id);
        }
        try (var restartedAgain = new SessionDirectory(url)) {
            assertTrue(restartedAgain.list("alice").isEmpty());
        }
    }

    @Test void failedDatabaseWriteDoesNotPublishTheTurn() throws Exception {
        String url = "jdbc:h2:file:" + data.resolve("closed");
        var directory = new SessionDirectory(url);
        UUID id = directory.create("alice", "早餐").sessionId();
        directory.close();
        var before = directory.snapshot("alice", id);
        assertEquals(503, assertThrows(ResponseStatusException.class, () ->
            directory.executeTurn("alice", id, "米饭", old ->
                new SessionDirectory.CompletedTurn("请确认", old.state()))).getStatusCode().value());
        assertEquals(before, directory.snapshot("alice", id));
    }
}
