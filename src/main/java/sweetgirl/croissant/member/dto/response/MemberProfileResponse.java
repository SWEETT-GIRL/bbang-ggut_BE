package sweetgirl.croissant.member.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import sweetgirl.croissant.member.entity.Member;

/**
 * 내 프로필(GET /users/me). {@code nicknameChangeableAt} 은 14일 안에 또 바꿀 수 없을 때만 그 날짜.
 * {@code suspended} 는 정지(SUSPENDED) 중인가, {@code suspendedUntil} 은 풀리는 때(무기한이면 null) — 마이 위 정지 배너용.
 */
public record MemberProfileResponse(
		Long memberId,
		String nickname,
		LocalDate nicknameChangeableAt,
		boolean suspended,
		LocalDateTime suspendedUntil) {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	public static MemberProfileResponse from(Member member) {
		return new MemberProfileResponse(member.getId(), member.getNickname(),
			member.nicknameChangeableAt(LocalDateTime.now(KST), Member.NICKNAME_CHANGE_INTERVAL_DAYS), false, null);
	}

	public MemberProfileResponse withSuspension(boolean isSuspended, LocalDateTime until) {
		return new MemberProfileResponse(memberId, nickname, nicknameChangeableAt, isSuspended, until);
	}
}
