package sweetgirl.croissant.breadlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.dto.response.RankingRegionResponse;
import sweetgirl.croissant.breadlog.entity.MemberRankingRegion;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.breadlog.repository.MemberRankingRegionRepository;
import sweetgirl.croissant.external.kakao.client.KakaoGeoClient;
import sweetgirl.croissant.external.kakao.dto.KakaoCoordToRegionResponse;
import sweetgirl.croissant.external.kakao.exception.KakaoErrorCode;
import sweetgirl.croissant.external.kakao.exception.KakaoLocalApiException;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.service.MemberReader;

@ExtendWith(MockitoExtension.class)
class RankingRegionServiceTest {

	private static final Long MEMBER_ID = 7L;
	private static final double LATITUDE = 37.5744;
	private static final double LONGITUDE = 127.0396;
	private static final LocalDateTime BEFORE = LocalDateTime.of(2026, 9, 28, 10, 0);

	@Mock
	private MemberReader memberReader;

	@Mock
	private MemberRankingRegionRepository regionRepository;

	@Mock
	private KakaoGeoClient kakaoGeoClient;

	@InjectMocks
	private RankingRegionService service;

	@Test
	@DisplayName("처음 인증하면 행정동의 시·도·시·군·구를 저장한다")
	void firstVerify() {
		given(kakaoGeoClient.coordToRegion(LONGITUDE, LATITUDE)).willReturn(kakao("서울특별시", "동대문구"));
		given(regionRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		given(regionRepository.save(any(MemberRankingRegion.class)))
			.willAnswer(invocation -> invocation.getArgument(0));

		RankingRegionResponse response = service.verifyRegion(MEMBER_ID, LATITUDE, LONGITUDE);

		assertThat(response.sido()).isEqualTo("서울");
		assertThat(response.sigungu()).isEqualTo("동대문구");
		assertThat(response.verifiedAt()).isNotNull();
	}

	@Test
	@DisplayName("법정동(B)이 먼저 와도 행정동(H)을 고른다")
	void prefersAdministrativeDocument() {
		given(kakaoGeoClient.coordToRegion(LONGITUDE, LATITUDE)).willReturn(new KakaoCoordToRegionResponse(List.of(
			document("B", "서울특별시", "중구"),
			document("H", "서울특별시", "종로구"))));
		given(regionRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		given(regionRepository.save(any(MemberRankingRegion.class)))
			.willAnswer(invocation -> invocation.getArgument(0));

		RankingRegionResponse response = service.verifyRegion(MEMBER_ID, LATITUDE, LONGITUDE);

		assertThat(response.sigungu()).isEqualTo("종로구");
	}

	@Test
	@DisplayName("\"수원시 영통구\"처럼 두 단어면 첫 단어만 저장한다 — region_key 둘째 칸과 같은 표기")
	void keepsFirstWordOfSigungu() {
		given(kakaoGeoClient.coordToRegion(LONGITUDE, LATITUDE)).willReturn(kakao("경기도", "수원시 영통구"));
		given(regionRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		given(regionRepository.save(any(MemberRankingRegion.class)))
			.willAnswer(invocation -> invocation.getArgument(0));

		RankingRegionResponse response = service.verifyRegion(MEMBER_ID, LATITUDE, LONGITUDE);

		assertThat(response.sido()).isEqualTo("경기");
		assertThat(response.sigungu()).isEqualTo("수원시");
	}

	@Test
	@DisplayName("다른 곳에서 다시 인증하면 잠금 없이 바로 바뀐다")
	void reverifyElsewhere() {
		MemberRankingRegion region = MemberRankingRegion.verified(MEMBER_ID, "서울", "동대문구", BEFORE);
		given(regionRepository.findById(MEMBER_ID)).willReturn(Optional.of(region));
		given(kakaoGeoClient.coordToRegion(LONGITUDE, LATITUDE)).willReturn(kakao("부산광역시", "해운대구"));

		RankingRegionResponse response = service.verifyRegion(MEMBER_ID, LATITUDE, LONGITUDE);

		assertThat(response.sido()).isEqualTo("부산");
		assertThat(response.sigungu()).isEqualTo("해운대구");
		assertThat(region.getVerifiedAt()).isAfter(BEFORE);
		verify(regionRepository, never()).save(any());
	}

	@Test
	@DisplayName("같은 곳을 다시 인증하면 인증 시각만 새로 찍힌다")
	void reverifySamePlace() {
		MemberRankingRegion region = MemberRankingRegion.verified(MEMBER_ID, "서울", "동대문구", BEFORE);
		given(regionRepository.findById(MEMBER_ID)).willReturn(Optional.of(region));
		given(kakaoGeoClient.coordToRegion(LONGITUDE, LATITUDE)).willReturn(kakao("서울특별시", "동대문구"));

		service.verifyRegion(MEMBER_ID, LATITUDE, LONGITUDE);

		assertThat(region.getSigungu()).isEqualTo("동대문구");
		assertThat(region.getVerifiedAt()).isAfter(BEFORE);
	}

	@Test
	@DisplayName("카카오가 지역을 못 찾으면 400 RANKING_INVALID_REGION")
	void rejectsWhenNoRegion() {
		given(kakaoGeoClient.coordToRegion(LONGITUDE, LATITUDE))
			.willReturn(new KakaoCoordToRegionResponse(List.of()));

		assertThatThrownBy(() -> service.verifyRegion(MEMBER_ID, LATITUDE, LONGITUDE))
			.isInstanceOf(BreadLogException.class)
			.extracting("errorCode")
			.isEqualTo(BreadLogErrorCode.INVALID_RANKING_REGION);
		verify(regionRepository, never()).save(any());
	}

	@Test
	@DisplayName("지역 이름이 비어 오면 400 — 바다처럼 행정구역이 없는 곳")
	void rejectsBlankRegion() {
		given(kakaoGeoClient.coordToRegion(LONGITUDE, LATITUDE)).willReturn(kakao("", ""));

		assertThatThrownBy(() -> service.verifyRegion(MEMBER_ID, LATITUDE, LONGITUDE))
			.isInstanceOf(BreadLogException.class)
			.extracting("errorCode")
			.isEqualTo(BreadLogErrorCode.INVALID_RANKING_REGION);
	}

	@Test
	@DisplayName("카카오 호출이 실패하면 502 KAKAO502_2")
	void kakaoFailure() {
		given(kakaoGeoClient.coordToRegion(LONGITUDE, LATITUDE)).willThrow(new RestClientException("timeout"));

		assertThatThrownBy(() -> service.verifyRegion(MEMBER_ID, LATITUDE, LONGITUDE))
			.isInstanceOf(KakaoLocalApiException.class)
			.extracting("errorCode")
			.isEqualTo(KakaoErrorCode.COORD_TO_REGION_ERROR);
	}

	@Test
	@DisplayName("게스트는 인증할 수 없다 — 카카오도 부르지 않는다")
	void guestCannotVerify() {
		willThrow(new MemberException(MemberErrorCode.GUEST_LOGIN_REQUIRED))
			.given(memberReader).requireLoggedIn(MEMBER_ID);

		assertThatThrownBy(() -> service.verifyRegion(MEMBER_ID, LATITUDE, LONGITUDE))
			.isInstanceOf(MemberException.class);
		verify(kakaoGeoClient, never()).coordToRegion(anyDouble(), anyDouble());
	}

	@Test
	@DisplayName("조회 — 아직 인증 안 했으면 전부 null")
	void getRegionEmpty() {
		given(regionRepository.findById(MEMBER_ID)).willReturn(Optional.empty());

		RankingRegionResponse response = service.getRegion(MEMBER_ID);

		assertThat(response.sido()).isNull();
		assertThat(response.sigungu()).isNull();
		assertThat(response.verifiedAt()).isNull();
	}

	@Test
	@DisplayName("내 지역 판은 인증했어야 볼 수 있다")
	void requireRegionWhenNotSet() {
		given(regionRepository.findById(MEMBER_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.requireRegion(MEMBER_ID))
			.isInstanceOf(BreadLogException.class)
			.extracting("errorCode")
			.isEqualTo(BreadLogErrorCode.RANKING_REGION_NOT_SET);
	}

	@Test
	@DisplayName("시·도만 고르던 때의 행은 시·군·구가 없어 다시 인증해야 한다")
	void requireRegionRejectsLegacyRow() {
		given(regionRepository.findById(MEMBER_ID))
			.willReturn(Optional.of(MemberRankingRegion.verified(MEMBER_ID, "서울", null, BEFORE)));

		assertThatThrownBy(() -> service.requireRegion(MEMBER_ID))
			.isInstanceOf(BreadLogException.class)
			.extracting("errorCode")
			.isEqualTo(BreadLogErrorCode.RANKING_REGION_NOT_SET);
	}

	@Test
	@DisplayName("인증했으면 내 지역 판의 시·도·시·군·구를 준다")
	void requireRegionVerified() {
		given(regionRepository.findById(MEMBER_ID))
			.willReturn(Optional.of(MemberRankingRegion.verified(MEMBER_ID, "경기", "수원시", BEFORE)));

		RankingRegionResponse response = service.requireRegion(MEMBER_ID);

		assertThat(response.sido()).isEqualTo("경기");
		assertThat(response.sigungu()).isEqualTo("수원시");
	}

	private static KakaoCoordToRegionResponse kakao(String sido, String sigungu) {
		return new KakaoCoordToRegionResponse(List.of(document("H", sido, sigungu)));
	}

	private static KakaoCoordToRegionResponse.Document document(String type, String sido, String sigungu) {
		return new KakaoCoordToRegionResponse.Document(type, sido, sigungu, "장안동");
	}
}
