package com.nutrilog;
import com.nutrilog.session.SessionDirectory;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
class SessionDirectoryTest {
    @Test void separatesOwnersAndConversations() {
        var directory=new SessionDirectory();
        var a=directory.create("alice","早餐"); var b=directory.create("alice","晚餐");
        assertNotEquals(a.sessionId(),b.sessionId());
        assertEquals(2,directory.list("alice").size()); assertTrue(directory.list("bob").isEmpty());
        assertEquals(a,directory.get("alice",a.sessionId()));
    }
    @Test void rejectsReadAndDeleteBeforeReturningMetadata() {
        var directory=new SessionDirectory(); var a=directory.create("alice","private title");
        var denied=assertThrows(ResponseStatusException.class,()->directory.get("bob",a.sessionId()));
        assertEquals(403,denied.getStatusCode().value());
        assertThrows(ResponseStatusException.class,()->directory.delete("bob",a.sessionId()));
        assertEquals(a,directory.get("alice",a.sessionId()));
        assertEquals(denied.getReason(),assertThrows(ResponseStatusException.class,()->directory.get("bob",UUID.randomUUID())).getReason());
        directory.delete("alice",a.sessionId()); assertTrue(directory.list("alice").isEmpty());
    }
    @Test void rejectsInvalidCreation() {
        var directory=new SessionDirectory();
        assertThrows(ResponseStatusException.class,()->directory.create(null,"title"));
        assertThrows(ResponseStatusException.class,()->directory.create("alice"," "));
        assertThrows(ResponseStatusException.class,()->directory.create("alice","a".repeat(101)));
    }
}
