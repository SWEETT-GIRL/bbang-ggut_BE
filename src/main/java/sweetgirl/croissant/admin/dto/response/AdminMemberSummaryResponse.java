package sweetgirl.croissant.admin.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import sweetgirl.croissant.member.entity.Member;

/**
 * 어드민이 편지 받을 사람을 고를 때 보는 한 줄. 닉네임과 가입일이면 누구인지 짚기에 족하다.
 */
public record AdminMemberSummaryResponse(
		Long memberId,
		String nickname,
		LocalDateTime joinedAt) {

	public static AdminMemberSummaryResponse from(Member member) {
		return new AdminMemberSummaryResponse(member.getId(), member.getNickname(), member.getCreatedAt());
	}

	public record ListResponse(List<AdminMemberSummaryResponse> members, boolean hasNext) {
	}
}
