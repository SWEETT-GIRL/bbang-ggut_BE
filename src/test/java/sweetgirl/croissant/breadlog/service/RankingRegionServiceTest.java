package sweetgirl.croissant.breadlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.dto.response.RankingRegionResponse;
import sweetgirl.croissant.breadlog.entity.MemberRankingRegion;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.breadlog.repository.MemberRankingRegionRepository;
import sweetgirl.croissant.member.service.MemberReader;

@ExtendWith(MockitoExtension.class)
class RankingRegionServiceTest {

	private static final Long MEMBER_ID = 7L;

	@Mock
	private MemberReader memberReader;

	@Mock
	private MemberRankingRegionRepository regionRepository;

	@InjectMocks
	private RankingRegionService service;

	@Test
	@DisplayName("처음 정하면 저장하고 하루 동안은 바로 바꿀 수 있다고 알려준다")
	void firstPick() {
		given(regionRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		given(regionRepository.save(any(MemberRankingRegion.class)))
			.willAnswer(invocation -> invocation.getArgument(0));

		RankingRegionResponse response = service.updateRegion(MEMBER_ID, "서울");

		assertThat(response.sido()).isEqualTo("서울");
		assertThat(response.nextChangeableAt()).isNull();
	}

	@Test
	@DisplayName("없는 시·도는 막는다")
	void rejectsUnknownSido() {
		assertThatThrownBy(() -> service.updateRegion(MEMBER_ID, "서울특별시"))
			.isInstanceOf(BreadLogException.class)
			.extracting("errorCode")
			.isEqualTo(BreadLogErrorCode.INVALID_RANKING_REGION);
		verify(regionRepository, never()).save(any());
	}

	@Test
	@DisplayName("쿨다운 중에 다른 지역으로 바꾸면 409 로 막는다")
	void lockedDuringCooldown() {
		MemberRankingRegion region = MemberRankingRegion.first(MEMBER_ID, "서울", LocalDateTime.now().minusDays(3));
		given(regionRepository.findById(MEMBER_ID)).willReturn(Optional.of(region));

		assertThatThrownBy(() -> service.updateRegion(MEMBER_ID, "부산"))
			.isInstanceOf(BreadLogException.class)
			.extracting("errorCode")
			.isEqualTo(BreadLogErrorCode.RANKING_REGION_LOCKED);
		assertThat(region.getSido()).isEqualTo("서울");
	}

	@Test
	@DisplayName("같은 지역을 다시 고르면 바꾼 것으로 세지 않는다")
	void sameSidoIsNoop() {
		MemberRankingRegion region = MemberRankingRegion.first(MEMBER_ID, "서울", LocalDateTime.now().minusDays(3));
		given(regionRepository.findById(MEMBER_ID)).willReturn(Optional.of(region));

		service.updateRegion(MEMBER_ID, "서울");

		assertThat(region.getChangeCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("내 지역 판은 지역을 정해야 볼 수 있다")
	void requireRegionWhenNotSet() {
		given(regionRepository.findById(MEMBER_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.requireRegion(MEMBER_ID))
			.isInstanceOf(BreadLogException.class)
			.extracting("errorCode")
			.isEqualTo(BreadLogErrorCode.RANKING_REGION_NOT_SET);
	}
}
