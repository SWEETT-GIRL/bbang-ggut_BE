package sweetgirl.croissant.breadlog.service;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.dto.response.RankingRegionResponse;
import sweetgirl.croissant.breadlog.entity.MemberRankingRegion;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.breadlog.repository.MemberRankingRegionRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.store.RegionFormatter;

/**
 * 빵로그 랭킹 "내 지역" — 회원이 직접 고른 시·도. 30일에 한 번 바꿀 수 있다(규칙은 {@link MemberRankingRegion}).
 *
 * <p>게스트는 저장할 곳이 없어 로그인해야 정할 수 있다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class RankingRegionService {

	static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

	private final MemberReader memberReader;
	private final MemberRankingRegionRepository regionRepository;

	@Transactional(readOnly = true)
	public RankingRegionResponse getRegion(Long memberId) {
		LocalDateTime now = LocalDateTime.now(ZONE);
		return regionRepository.findById(memberId)
			.map(region -> new RankingRegionResponse(region.getSido(), region.nextChangeableAt(now)))
			.orElseGet(() -> new RankingRegionResponse(null, null));
	}

	public RankingRegionResponse updateRegion(Long memberId, String sido) {
		memberReader.requireLoggedIn(memberId);
		if (!RegionFormatter.isShortSido(sido)) {
			throw new BreadLogException(BreadLogErrorCode.INVALID_RANKING_REGION);
		}
		LocalDateTime now = LocalDateTime.now(ZONE);
		MemberRankingRegion region = regionRepository.findById(memberId).orElse(null);
		if (region == null) {
			region = regionRepository.save(MemberRankingRegion.first(memberId, sido, now));
		} else if (!region.getSido().equals(sido)) {
			// 같은 곳을 다시 고른 건 바꾼 게 아니다 — 쿨다운도 횟수도 건드리지 않는다
			if (region.nextChangeableAt(now) != null) {
				throw new BreadLogException(BreadLogErrorCode.RANKING_REGION_LOCKED);
			}
			region.change(sido, now);
		}
		return new RankingRegionResponse(region.getSido(), region.nextChangeableAt(now));
	}

	/** 내 지역 판을 부를 때 — 로그인했고 지역을 정했어야 한다. */
	@Transactional(readOnly = true)
	public String requireRegion(Long memberId) {
		memberReader.requireLoggedIn(memberId);
		return regionRepository.findById(memberId)
			.map(MemberRankingRegion::getSido)
			.orElseThrow(() -> new BreadLogException(BreadLogErrorCode.RANKING_REGION_NOT_SET));
	}
}
