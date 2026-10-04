package com.cau.capstone8.backend.account;

import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;

public final class AccountModels {
    private AccountModels() {}
    public enum Avatar { BOOK, LEAF, MOON, SUN }
    public record Register(@NotBlank @Email @Size(max=254) String email,
            @NotNull @Size(min=12,max=128) String password,
            @NotBlank @Size(max=120) String displayName) {}
    public record Login(@NotBlank @Email @Size(max=254) String email,
            @NotNull @Size(min=1,max=128) String password) {}
    public record Session(long userId, String accessToken, String tokenType, OffsetDateTime expiresAt) {}
    public record Update(@NotBlank @Size(max=120) String displayName,
            @NotNull @Size(max=500) String bio, @NotNull Avatar avatarKey,
            @NotNull @Size(max=20) List<@NotNull @Positive Long> interestTopicIds) {}
    public record Interest(long id, String code, String name) {}
    public record Profile(long userId, String email, String displayName, String bio,
            Avatar avatarKey, List<Interest> interests, OffsetDateTime createdAt) {}
}
