package sweetgirl.croissant.breadlog.service;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.dto.response.RankingRegionResponse;
import sweetgirl.croissant.breadlog.entity.MemberRankingRegion;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.breadlog.repository.MemberRankingRegionRepository;
import sweetgirl.croissant.external.kakao.client.KakaoGeoClient;
import sweetgirl.croissant.external.kakao.dto.KakaoCoordToRegionResponse;
import sweetgirl.croissant.external.kakao.exception.KakaoErrorCode;
import sweetgirl.croissant.external.kakao.exception.KakaoLocalApiException;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.store.RegionFormatter;

/**
 * 빵로그 랭킹 "내 지역" — 지금 있는 곳의 좌표로 시·군·구를 인증한다(규칙은 {@link MemberRankingRegion}).
 *
 * <p>좌표 → 지역은 카카오 행정동(H)으로 푼다. "수원시 영통구"처럼 두 단어로 오는 시·군·구는 첫 단어("수원시")만 쓴다 —
 * 가게 주소에서 만든 {@code bread_record.region_key} 둘째 칸이 그 표기라, 내 지역 판 필터가 그대로 맞는다.
 *
 * <p>게스트는 저장할 곳이 없어 로그인해야 인증할 수 있다. 좌표는 저장도 로그도 하지 않는다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class RankingRegionService {

	static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
	private static final String ADMINISTRATIVE_REGION = "H";

	private final MemberReader memberReader;
	private final MemberRankingRegionRepository regionRepository;
	private final KakaoGeoClient kakaoGeoClient;

	@Transactional(readOnly = true)
	public RankingRegionResponse getRegion(Long memberId) {
		return regionRepository.findById(memberId)
			.map(RankingRegionResponse::from)
			.orElseGet(RankingRegionResponse::empty);
	}

	public RankingRegionResponse verifyRegion(Long memberId, double latitude, double longitude) {
		memberReader.requireLoggedIn(memberId);
		KakaoCoordToRegionResponse.Document document = locate(latitude, longitude);
		String sido = RegionFormatter.shortSido(document.region1depthName());
		String sigungu = firstWord(document.region2depthName());
		if (!RegionFormatter.isShortSido(sido) || sigungu.isEmpty()) {
			throw new BreadLogException(BreadLogErrorCode.INVALID_RANKING_REGION);
		}
		LocalDateTime now = LocalDateTime.now(ZONE);
		MemberRankingRegion region = regionRepository.findById(memberId)
			.map(existing -> {
				existing.reverify(sido, sigungu, now);
				return existing;
			})
			.orElseGet(() -> regionRepository.save(MemberRankingRegion.verified(memberId, sido, sigungu, now)));
		return RankingRegionResponse.from(region);
	}

	/** 내 지역 판을 부를 때 — 로그인했고 시·군·구까지 인증했어야 한다. */
	@Transactional(readOnly = true)
	public RankingRegionResponse requireRegion(Long memberId) {
		memberReader.requireLoggedIn(memberId);
		return regionRepository.findById(memberId)
			.filter(MemberRankingRegion::isVerified)
			.map(RankingRegionResponse::from)
			.orElseThrow(() -> new BreadLogException(BreadLogErrorCode.RANKING_REGION_NOT_SET));
	}

	private KakaoCoordToRegionResponse.Document locate(double latitude, double longitude) {
		KakaoCoordToRegionResponse response;
		try {
			response = kakaoGeoClient.coordToRegion(longitude, latitude);
		} catch (RestClientException exception) {
			throw new KakaoLocalApiException(KakaoErrorCode.COORD_TO_REGION_ERROR, exception);
		}
		if (response == null || response.documents() == null) {
			throw new BreadLogException(BreadLogErrorCode.INVALID_RANKING_REGION);
		}
		// 행정동(H)을 먼저, 없으면 법정동(B) — 시·도와 시·군·구는 둘이 같다
		return response.documents().stream()
			.filter(document -> ADMINISTRATIVE_REGION.equals(document.regionType()))
			.findFirst()
			.or(() -> response.documents().stream().findFirst())
			.orElseThrow(() -> new BreadLogException(BreadLogErrorCode.INVALID_RANKING_REGION));
	}

	/** "수원시 영통구" → "수원시". 비어 있으면 빈 문자열. */
	private static String firstWord(String name) {
		if (name == null || name.isBlank()) {
			return "";
		}
		return name.strip().split("\\s+")[0];
	}
}
