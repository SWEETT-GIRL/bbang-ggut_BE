package sweetgirl.croissant.profile.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.config.cache.CacheConfig;

/**
 * 추천 후보 id — 하루 캐시, 키는 보는 사람. 같은 클래스 안에서 부르면 프록시를 안 타 캐시가 안 먹어(10/22) 따로 뺐다.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberSuggestionCandidateReader {

	static final int RECENT_DAYS = 30;
	static final int CANDIDATE_SIZE = 30;

	private final BreadRecordRepository breadRecordRepository;

	@Cacheable(cacheNames = CacheConfig.MEMBER_SUGGESTIONS, key = "#viewerId")
	public List<Long> candidateIds(Long viewerId) {
		return breadRecordRepository.findRecentPublicRecorderIds(
			viewerId, LocalDateTime.now().minusDays(RECENT_DAYS), CANDIDATE_SIZE);
	}
}
