package sweetgirl.croissant.course.dto.request;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/** 앱 안 일행 초대 — 팔로잉 · 팔로워에서 고른 회원 id. 정원이 {@code CourseMember.MEMBER_LIMIT} 이라 한 번에 7명까지. */
public record CourseMemberInviteRequest(
		@NotEmpty @Size(max = 7) List<Long> memberIds) {
}
