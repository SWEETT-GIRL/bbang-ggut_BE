package sweetgirl.croissant.profile.service;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.follow.dto.response.FollowMemberResponse;
import sweetgirl.croissant.follow.repository.MemberFollowRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberBlockRepository;
import sweetgirl.croissant.member.repository.MemberRepository;

/**
 * "이 빵집 다녀간 사람" — 그 가게에 공개 빵로그를 남긴 사람, 최근 기록순. 기록 완료 화면의 팔로우 제안이다.
 *
 * <p>공개 기록은 주 393 건 쌓이는데 팔로우 · 댓글은 0 에 가깝다(11/3). 같은 빵집을 다녀온 사람이 가장 자연스러운 첫 팔로우다.
 * 본인 · 이미 팔로우 중 · 차단 관계는 뺀다. 게스트도 본다(관계 플래그는 전부 false, 팔로우를 누르면 앱이 로그인 게이트).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreRecorderQueryService {

	static final int MAX_LIMIT = 20;
	/** 팔로우 중 · 차단을 거르고도 limit 를 채우도록 후보는 넉넉히 읽는다. */
	static final int CANDIDATE_MULTIPLIER = 2;

	private final BreadRecordRepository breadRecordRepository;
	private final MemberRepository memberRepository;
	private final MemberFollowRepository memberFollowRepository;
	private final MemberBlockRepository memberBlockRepository;
	private final MemberSearchQueryService memberSearchQueryService;

	public List<FollowMemberResponse> recorders(Long viewerId, Long storeId, int limit) {
		int size = Math.max(1, Math.min(limit, MAX_LIMIT));
		List<Long> candidateIds = breadRecordRepository.findPublicRecorderIdsByStore(
			storeId, viewerId == null ? 0L : viewerId, size * CANDIDATE_MULTIPLIER);
		if (candidateIds.isEmpty()) {
			return List.of();
		}
		Set<Long> alreadyFollowing = viewerId == null
			? Set.of() : Set.copyOf(memberFollowRepository.findFolloweeIdsIn(viewerId, candidateIds));
		List<Long> picked = candidateIds.stream()
			.filter(candidateId -> !alreadyFollowing.contains(candidateId))
			.filter(candidateId -> viewerId == null || !memberBlockRepository.existsBetween(viewerId, candidateId))
			.limit(size)
			.toList();
		if (picked.isEmpty()) {
			return List.of();
		}
		List<Member> people = memberRepository.findSocialByIdIn(picked);
		// 쿼리 결과 순서는 보장되지 않는다 — 최근 기록순으로 다시 맞춘다
		List<Member> ordered = picked.stream()
			.flatMap(id -> people.stream().filter(person -> person.getId().equals(id)))
			.toList();
		return memberSearchQueryService.withRelations(viewerId, ordered);
	}
}
