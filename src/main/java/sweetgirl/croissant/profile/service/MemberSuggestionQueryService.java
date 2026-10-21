package sweetgirl.croissant.profile.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.config.cache.CacheConfig;
import sweetgirl.croissant.follow.dto.response.FollowMemberResponse;
import sweetgirl.croissant.follow.repository.MemberFollowRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberBlockRepository;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.service.MemberReader;

/**
 * "이런 빵순이 어때요" 3명 — 최근 7일 공개 빵로그가 있는 사람, 나와 같은 빵집을 기록한 사람 우선.
 *
 * <p>후보 id 목록은 보는 사람마다 하루 캐시(인스턴스 메모리)한다. 이미 팔로우 중 · 차단 관계는 읽을 때 걸러서
 * 팔로우하자마자 추천에서 빠진다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberSuggestionQueryService {

	static final int RESULT_SIZE = 3;
	static final int CANDIDATE_SIZE = 30;
	static final int RECENT_DAYS = 7;

	private final BreadRecordRepository breadRecordRepository;
	private final MemberRepository memberRepository;
	private final MemberFollowRepository memberFollowRepository;
	private final MemberBlockRepository memberBlockRepository;
	private final MemberReader memberReader;
	private final MemberSearchQueryService memberSearchQueryService;

	public List<FollowMemberResponse> suggest(Long viewerId) {
		memberReader.requireLoggedIn(viewerId);
		List<Long> candidateIds = candidateIds(viewerId);
		if (candidateIds.isEmpty()) {
			return List.of();
		}
		Set<Long> alreadyFollowing = Set.copyOf(memberFollowRepository.findFolloweeIdsIn(viewerId, candidateIds));
		List<Long> picked = candidateIds.stream()
			.filter(candidateId -> !alreadyFollowing.contains(candidateId))
			.filter(candidateId -> !memberBlockRepository.existsBetween(viewerId, candidateId))
			.limit(RESULT_SIZE)
			.toList();
		List<Member> people = memberRepository.findSocialByIdIn(picked);
		// 쿼리 결과 순서는 보장되지 않는다 — 후보 순서(같은 빵집 우선)로 다시 맞춘다
		List<Member> ordered = picked.stream()
			.flatMap(id -> people.stream().filter(person -> person.getId().equals(id)))
			.toList();
		return memberSearchQueryService.withRelations(viewerId, ordered);
	}

	/** 하루 캐시. 키는 보는 사람. */
	@Cacheable(cacheNames = CacheConfig.MEMBER_SUGGESTIONS, key = "#viewerId")
	public List<Long> candidateIds(Long viewerId) {
		return breadRecordRepository.findRecentPublicRecorderIds(
			viewerId, LocalDateTime.now().minusDays(RECENT_DAYS), CANDIDATE_SIZE);
	}
}
