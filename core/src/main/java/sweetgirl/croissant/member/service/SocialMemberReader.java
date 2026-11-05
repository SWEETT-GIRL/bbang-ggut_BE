package sweetgirl.croissant.member.service;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;

/**
 * 팔로우 · 프로필 · 검색 · 추천 · 멘션의 <b>상대</b>가 될 수 있는 사람인가.
 *
 * <p>일반 회원(USER)이고 탈퇴하지 않았어야 한다. 게스트와 블로그 봇(BLOG_BOT)은 사람들 눈에 띄지 않는다 —
 * 프로필도 없고 팔로우도 안 된다. 강퇴(BANNED)는 soft delete 를 함께 하므로 탈퇴 조건이 거른다.
 * 상대를 찾지 못한 모든 경우를 한 가지(없음)로 답한다 — 게스트인지 탈퇴했는지는 남에게 알려 줄 일이 아니다.
 */
@Component
@RequiredArgsConstructor
public class SocialMemberReader {

	private final MemberRepository memberRepository;

	public boolean isSocial(Member member) {
		return member != null && !member.isDeleted() && member.isUser();
	}

	/** 없으면 비어 있다. 404 로 바꾸는 건 부르는 쪽이 자기 에러 코드로 한다. */
	public java.util.Optional<Member> findSocial(Long memberId) {
		if (memberId == null) {
			return java.util.Optional.empty();
		}
		return memberRepository.findByIdAndDeletedAtIsNull(memberId).filter(this::isSocial);
	}
}
