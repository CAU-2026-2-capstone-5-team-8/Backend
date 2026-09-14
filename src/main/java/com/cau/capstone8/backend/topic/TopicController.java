package com.cau.capstone8.backend.topic;

import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/topics")
public class TopicController {
    private final TopicRepository topics;
    public TopicController(TopicRepository topics) { this.topics = topics; }

    @GetMapping
    public List<TopicResponse> list() {
        return topics.findAllByOrderByIdAsc().stream()
                .map(t -> new TopicResponse(t.getId(), t.getCode(), t.getName(), t.getParentId())).toList();
    }
    public record TopicResponse(long id, String code, String name, Long parentId) {}
}
