package sweetgirl.croissant.follow.service;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.follow.repository.MemberFollowRepository;
import sweetgirl.croissant.member.entity.Member;

/**
 * 비공개 계정의 공개 빵로그를 볼 수 있는 사람인가 — 본인이거나 (수락된) 팔로워.
 *
 * <p>공개 계정이면 누구나 본다. 프로필 자체(닉네임 · 캐릭터 · 수)는 비공개여도 보인다 — 여기서 가르는 건 빵로그뿐이다.
 * 빵로그 상세 · 댓글 · 좋아요 · 프로필의 빵로그 목록 · 다녀간 빵집이 같은 규칙을 쓴다.
 */
@Component
@RequiredArgsConstructor
public class FollowVisibilityReader {

	private final MemberFollowRepository memberFollowRepository;

	public boolean canViewRecords(Long viewerId, Member owner) {
		if (!owner.isPrivate()) {
			return true;
		}
		if (viewerId == null) {
			return false;
		}
		if (viewerId.equals(owner.getId())) {
			return true;
		}
		return memberFollowRepository.existsByIdFollowerIdAndIdFolloweeId(viewerId, owner.getId());
	}
}
