package com.cau.capstone8.backend.recommendation;

import com.cau.capstone8.backend.common.error.ResourceNotFoundException;
import com.cau.capstone8.backend.user.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeedbackService {
    private final FeedbackRepository feedback;
    private final RecommendationItemRepository items;
    private final RecommendationRunRepository runs;
    private final AppUserRepository users;

    public FeedbackService(
            FeedbackRepository feedback,
            RecommendationItemRepository items,
            RecommendationRunRepository runs,
            AppUserRepository users) {
        this.feedback = feedback;
        this.items = items;
        this.runs = runs;
        this.users = users;
    }

    @Transactional
    public Saved save(long itemId, FeedbackRequest request) {
        users.findByIdForUpdate(request.userId())
                .orElseThrow(() -> new ResourceNotFoundException("사용자를 찾을 수 없습니다."));
        RecommendationItem item = items.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("추천 항목을 찾을 수 없습니다."));
        RecommendationRun run = runs.findById(item.getRunId())
                .orElseThrow(() -> new IllegalStateException("추천 항목의 요청을 찾을 수 없습니다."));
        if (!run.getUserId().equals(request.userId())) {
            throw new RecommendationConflictException("다른 사용자의 추천에는 피드백을 남길 수 없습니다.");
        }
        if (run.getStatus() != RecommendationStatus.SUCCEEDED) {
            throw new RecommendationConflictException("완료된 추천에만 피드백을 남길 수 있습니다.");
        }
        Feedback existing = feedback.findByUserIdAndRecommendationItemId(request.userId(), itemId)
                .orElse(null);
        if (existing != null) {
            existing.replace(request.helpful(), request.comment());
            return new Saved(FeedbackResponse.from(existing), false);
        }
        Feedback saved = feedback.save(new Feedback(
                itemId, request.userId(), request.helpful(), request.comment()));
        return new Saved(FeedbackResponse.from(saved), true);
    }

    public record Saved(FeedbackResponse response, boolean newlyCreated) {
    }
}
