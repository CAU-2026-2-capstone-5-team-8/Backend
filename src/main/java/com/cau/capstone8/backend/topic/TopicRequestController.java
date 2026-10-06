package com.cau.capstone8.backend.topic;

import com.cau.capstone8.backend.account.CurrentAccount;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/topic-requests")
public class TopicRequestController {
    private final TopicCatalogRefreshService catalogs;
    private final TopicRequestService requests;
    private final TopicContentPreparationService content;
    private final TopicQuestionPreparationService questions;
    private final TopicNameResolver resolver;
    private final TopicDiscoveryService discoveries;
    public TopicRequestController(TopicRequestService requests,TopicContentPreparationService content,TopicQuestionPreparationService questions,TopicNameResolver resolver,TopicDiscoveryService discoveries,TopicCatalogRefreshService catalogs) { this.catalogs=catalogs; this.requests = requests; this.content=content;this.questions=questions;this.resolver=resolver;this.discoveries=discoveries; }

    @GetMapping("/catalog/{topicId}")
    public TopicCatalogRefreshService.State catalog(@PathVariable long topicId) {
        CurrentAccount.id();return catalogs.state(topicId);
    }
    @PostMapping("/catalog/{topicId}/refresh")
    public ResponseEntity<TopicCatalogRefreshService.State> refreshCatalog(@PathVariable long topicId,
            @RequestBody(required=false) TopicCatalogRefreshService.Input input) {
        return ResponseEntity.accepted().body(catalogs.start(CurrentAccount.id(),topicId,input));
    }
    public record Lookup(String name) {}
    @PostMapping("/resolve")
    public TopicNameResolver.Resolution resolve(@RequestBody Lookup input) {
        CurrentAccount.id();return resolver.inspect(input.name());
    }

    @PostMapping("/discover")
    public ResponseEntity<TopicDiscoveryService.Discovery> discover(@RequestBody Lookup input) {
        var result=discoveries.start(CurrentAccount.id(),input.name());
        return ResponseEntity.status(java.util.Set.of("QUEUED","SEARCHING").contains(result.status())?202:200).body(result);
    }
    @GetMapping("/discover/{id}")
    public TopicDiscoveryService.Discovery discovery(@PathVariable java.util.UUID id) {
        return discoveries.detail(CurrentAccount.id(),id);
    }
    @PostMapping
    public ResponseEntity<TopicRequestService.Submission> submit(@RequestBody TopicRequestService.Input input) {
        var result = requests.submit(CurrentAccount.id(), input);
        return ResponseEntity.status(result.request() != null && !result.replayed() ? 201 : 200).body(result);
    }
    @GetMapping
    public List<TopicRequestService.Request> list() { return requests.list(CurrentAccount.id()); }
    @GetMapping("/{id}")
    public TopicRequestService.Request detail(@PathVariable long id) { return requests.detail(CurrentAccount.id(), id); }
    @PostMapping("/{id}/retry")
    public TopicRequestService.Request retry(@PathVariable long id, @RequestBody(required=false) TopicRequestService.Retry input) {
        return requests.retry(CurrentAccount.id(), id, input);
    }
    @PostMapping("/{id}/content/retry")
    public TopicRequestService.Request retryContent(@PathVariable long id) {
        content.retry(CurrentAccount.id(),id);
        return requests.detail(CurrentAccount.id(),id);
    }
    @PostMapping("/{id}/questions/retry")
    public TopicRequestService.Request retryQuestions(@PathVariable long id) {
        questions.retry(CurrentAccount.id(),id);return requests.detail(CurrentAccount.id(),id);
    }
}
