package sweetgirl.croissant.follow.service;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.follow.code.FollowErrorCode;
import sweetgirl.croissant.follow.dto.response.FollowMemberResponse;
import sweetgirl.croissant.follow.entity.MemberFollow;
import sweetgirl.croissant.follow.exception.FollowException;
import sweetgirl.croissant.follow.repository.MemberFollowRepository;
import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberBlockRepository;
import sweetgirl.croissant.member.service.SocialMemberReader;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FollowQueryService {

	static final int MAX_PAGE_SIZE = 50;

	private final MemberFollowRepository memberFollowRepository;
	private final MemberBlockRepository memberBlockRepository;
	private final SocialMemberReader socialMemberReader;

	/** {@code memberId} 의 팔로워. 보는 사람({@code viewerId})은 게스트여도 되고, 비로그인이면 null 이다. */
	public SliceResponse<FollowMemberResponse> getFollowers(Long viewerId, Long memberId, int page, int size) {
		validateVisible(viewerId, memberId);
		Slice<MemberFollow> rows = memberFollowRepository.findFollowers(memberId, viewerId, pageable(page, size));
		return toResponse(viewerId, rows, MemberFollow::getFollower);
	}

	/** {@code memberId} 가 팔로우하는 사람. */
	public SliceResponse<FollowMemberResponse> getFollowings(Long viewerId, Long memberId, int page, int size) {
		validateVisible(viewerId, memberId);
		Slice<MemberFollow> rows = memberFollowRepository.findFollowings(memberId, viewerId, pageable(page, size));
		return toResponse(viewerId, rows, MemberFollow::getFollowee);
	}

	/** 상대가 없거나 게스트 · 봇 · 탈퇴면 404, 보는 사람과 차단 관계면 403 — 프로필과 같은 규칙이다. */
	private void validateVisible(Long viewerId, Long memberId) {
		socialMemberReader.findSocial(memberId)
			.orElseThrow(() -> new FollowException(FollowErrorCode.FOLLOW_TARGET_NOT_FOUND));
		if (viewerId != null && !viewerId.equals(memberId) && memberBlockRepository.existsBetween(viewerId, memberId)) {
			throw new FollowException(FollowErrorCode.FOLLOW_BLOCKED);
		}
	}

	private SliceResponse<FollowMemberResponse> toResponse(Long viewerId, Slice<MemberFollow> rows,
			Function<MemberFollow, Member> personOf) {
		List<Member> people = rows.getContent().stream().map(personOf).toList();
		List<Long> ids = people.stream().map(Member::getId).toList();
		Set<Long> followedByMe = viewerId == null || ids.isEmpty()
			? Set.of() : Set.copyOf(memberFollowRepository.findFolloweeIdsIn(viewerId, ids));
		Set<Long> followsMe = viewerId == null || ids.isEmpty()
			? Set.of() : Set.copyOf(memberFollowRepository.findFollowerIdsIn(viewerId, ids));
		List<FollowMemberResponse> content = people.stream()
			.map(person -> FollowMemberResponse.of(person,
				followedByMe.contains(person.getId()), followsMe.contains(person.getId())))
			.toList();
		return new SliceResponse<>(content, rows.hasNext());
	}

	private static Pageable pageable(int page, int size) {
		return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
	}
}
