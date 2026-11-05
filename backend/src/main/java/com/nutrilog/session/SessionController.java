package com.nutrilog.session;
import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {
    private final SessionDirectory directory;
    public SessionController(SessionDirectory directory) { this.directory=directory; }
    public record CreateRequest(String title) {}
    private String owner(Principal principal) {
        if(principal==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return principal.getName();
    }
    @PostMapping public SessionDirectory.View create(@RequestBody CreateRequest request, Principal principal) {
        String user=owner(principal);
        if(request==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return directory.create(user,request.title());
    }
    @GetMapping public List<SessionDirectory.View> list(Principal principal) { return directory.list(owner(principal)); }
    @GetMapping("/{id}") public SessionDirectory.View get(@PathVariable UUID id, Principal principal) {
        return directory.get(owner(principal),id);
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, Principal principal) { directory.delete(owner(principal),id); }
}
