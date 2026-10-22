package sweetgirl.croissant.profile.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.follow.dto.response.FollowMemberResponse;
import sweetgirl.croissant.follow.repository.MemberFollowRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.util.NicknameNormalizer;

/** 빵순이 찾기 — 닉네임 앞글자 일치. 2자부터, 20명까지. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberSearchQueryService {

	static final int MIN_QUERY_LENGTH = 2;
	static final int LIMIT = 20;

	private final MemberRepository memberRepository;
	private final MemberFollowRepository memberFollowRepository;

	/** 보는 사람과 차단 관계 · 탈퇴 · 게스트 · 봇 · 프로필 제한 중인 사람은 빠진다. 2자 미만이면 빈 목록. */
	public List<FollowMemberResponse> search(Long viewerId, String query) {
		String prefix = NicknameNormalizer.normalize(query);
		if (prefix.length() < MIN_QUERY_LENGTH) {
			return List.of();
		}
		List<Member> found = memberRepository.searchSocialByNicknamePrefix(prefix, viewerId, LocalDateTime.now(),
			PageRequest.of(0, LIMIT));
		return withRelations(viewerId, found);
	}

	/** 줄마다 followedByMe · followsMe 를 id 묶음 두 번으로 채운다. 좋아요 누른 사람 목록도 쓴다. */
	public List<FollowMemberResponse> withRelations(Long viewerId, List<Member> people) {
		List<Long> ids = people.stream().map(Member::getId).toList();
		Set<Long> followedByMe = viewerId == null || ids.isEmpty()
			? Set.of() : Set.copyOf(memberFollowRepository.findFolloweeIdsIn(viewerId, ids));
		Set<Long> followsMe = viewerId == null || ids.isEmpty()
			? Set.of() : Set.copyOf(memberFollowRepository.findFollowerIdsIn(viewerId, ids));
		return people.stream()
			.map(person -> FollowMemberResponse.of(person,
				followedByMe.contains(person.getId()), followsMe.contains(person.getId())))
			.toList();
	}
}
