package sweetgirl.croissant.member.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import sweetgirl.croissant.member.entity.Member;

/** 내 프로필(GET /users/me). {@code nicknameChangeableAt} 은 14일 안에 또 바꿀 수 없을 때만 그 날짜, 바꿀 수 있으면 null. */
public record MemberProfileResponse(Long memberId, String nickname, LocalDate nicknameChangeableAt) {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	public static MemberProfileResponse from(Member member) {
		return new MemberProfileResponse(member.getId(), member.getNickname(),
			member.nicknameChangeableAt(LocalDateTime.now(KST), Member.NICKNAME_CHANGE_INTERVAL_DAYS));
	}
}
