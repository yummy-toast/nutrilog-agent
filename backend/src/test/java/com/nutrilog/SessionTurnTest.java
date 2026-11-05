package com.nutrilog;

import com.nutrilog.session.SessionDirectory;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class SessionTurnTest {
    private final SessionDirectory directory = new SessionDirectory();
    private final java.util.UUID id = directory.create("alice", "早餐").sessionId();
    private final SessionDirectory.State pending = new SessionDirectory.State(List.of(1L, 2L), null, null, null);

    @Test void failedTurnLeavesSnapshotIntactAndReleasesGate() {
        directory.executeTurn("alice", id, "米饭", old -> new SessionDirectory.CompletedTurn("生米还是熟米饭？", pending));
        var before = directory.snapshot("alice", id);
        assertEquals(1, before.turns().size());
        assertEquals(pending, before.state());
        assertThrows(IllegalStateException.class, () -> directory.executeTurn("alice", id, "熟米饭", old -> {
            throw new IllegalStateException("模拟模型失败");
        }));
        assertEquals(before, directory.snapshot("alice", id));
        directory.executeTurn("alice", id, "熟米饭", old -> {
            assertEquals(before, old);
            return new SessionDirectory.CompletedTurn("多少克？", new SessionDirectory.State(List.of(), 2L, null, null));
        });
        assertEquals(2, directory.snapshot("alice", id).turns().size());
        assertEquals(2L, directory.snapshot("alice", id).state().selectedFoodId());
    }

    @Test void concurrentTurnAndDeleteCannotRaceTheCommit() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = executor.submit(() -> directory.executeTurn("alice", id, "米饭", old -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("等待超时");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return new SessionDirectory.CompletedTurn("请确认", pending);
            }));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertTrue(directory.snapshot("alice", id).turns().isEmpty());
                assertEquals(409, assertThrows(ResponseStatusException.class,
                    () -> directory.executeTurn("alice", id, "另一个请求", old -> fail("不能进入执行器"))).getStatusCode().value());
                assertEquals(409, assertThrows(ResponseStatusException.class, () -> directory.delete("alice", id)).getStatusCode().value());
                assertEquals(403, assertThrows(ResponseStatusException.class,
                    () -> directory.executeTurn("bob", id, "越权", old -> fail("不能读取快照"))).getStatusCode().value());
                assertEquals(403, assertThrows(ResponseStatusException.class, () -> directory.snapshot("bob", id)).getStatusCode().value());
                var other = directory.create("alice", "晚餐").sessionId();
                directory.executeTurn("alice", other, "鸡蛋", old -> new SessionDirectory.CompletedTurn("多少克？", old.state()));
                assertEquals(1, directory.snapshot("alice", other).turns().size());
            } finally {
                release.countDown();
            }
            running.get(3, TimeUnit.SECONDS);
            assertEquals(1, directory.snapshot("alice", id).turns().size());
        }
    }
}
