package com.nutrilog.session;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 第一阶段仅保存会话元数据，内存目录在重启后清空，没有消息正文。 */
@Service
public class SessionDirectory {
    private record Entry(String owner, View view) {}
    public record View(UUID sessionId, String title, Instant createdAt) {}
    private final ConcurrentHashMap<UUID,Entry> entries = new ConcurrentHashMap<>();
    public View create(String owner, String title) {
        if (owner == null || owner.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (title == null || title.isBlank() || title.length() > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"标题须为1至100个字符");
        var view = new View(UUID.randomUUID(),title.strip(),Instant.now());
        entries.put(view.sessionId(),new Entry(owner,view)); return view;
    }
    private Entry requireOwned(String owner, UUID id) {
        var entry = entries.get(id);
        if (entry == null || !entry.owner().equals(owner))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"会话不存在或无权访问");
        return entry;
    }
    public View get(String owner, UUID id) { return requireOwned(owner,id).view(); }
    public List<View> list(String owner) {
        return entries.values().stream().filter(entry -> entry.owner().equals(owner)).map(Entry::view)
            .sorted(Comparator.comparing(View::createdAt).thenComparing(View::sessionId)).toList();
    }
    public void delete(String owner, UUID id) {
        var entry = requireOwned(owner,id);
        if (!entries.remove(id,entry)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"会话不存在或无权访问");
    }
}
