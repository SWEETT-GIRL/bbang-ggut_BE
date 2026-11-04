package sweetgirl.croissant.member.dto.request;

import jakarta.validation.constraints.NotNull;

/** 계정 공개 범위 — true 면 비공개(팔로워만 내 공개 빵로그를 본다). */
public record PrivacyUpdateRequest(@NotNull Boolean isPrivate) {
}
