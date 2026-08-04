package sweetgirl.croissant.breadlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.breadlog.dto.projection.BreadRecordRegionProjection;
import sweetgirl.croissant.breadlog.dto.projection.BreadRecordStoreVisitProjection;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordSummaryResponse;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;
import sweetgirl.croissant.member.service.MemberReader;

@ExtendWith(MockitoExtension.class)
class BreadRecordSummaryServiceTest {

	@Mock
	MemberReader memberReader;
	@Mock
	BreadRecordRepository breadRecordRepository;
	@Mock
	BreadRecordTagRepository tagRepository;

	@InjectMocks
	BreadRecordSummaryService summaryService;

	@Test
	@DisplayName("가게 방문·빵 종류·지역을 기간 기준으로 집계한다")
	void getSummary_aggregatesRecords() {
		LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
		LocalDateTime to = LocalDateTime.of(2027, 1, 1, 0, 0);
		List<BreadRecordStoreVisitProjection> visits = List.of(
			storeVisit(1L, "밀도 성수", 3L),
			storeVisit(null, "동네 빵집", 2L),
			storeVisit(2L, "어니언", 1L));
		given(breadRecordRepository.findStoreVisits(7L, from, to)).willReturn(visits);
		given(breadRecordRepository.findVisitedRegions(7L, from, to)).willReturn(List.of(
			region("서울특별시", "서울_성동구_성수동"),
			region(null, "대전_유성구_궁동")));
		given(tagRepository.countBreads(7L, from, to)).willReturn(6L);
		given(tagRepository.findBreadCategories(7L, from, to)).willReturn(List.of("소금빵", "식빵"));

		BreadRecordSummaryResponse result = summaryService.getSummary(7L, from, to);

		assertThat(result.totalBreads()).isEqualTo(6L);
		assertThat(result.totalStores()).isEqualTo(3L);
		assertThat(result.sidos()).containsExactly("서울", "대전");
		assertThat(result.breadCategories()).containsExactly("소금빵", "식빵");
		assertThat(result.topStores()).extracting(BreadRecordSummaryResponse.TopStore::storeId)
			.containsExactly(1L, 2L);
		assertThat(result.revisitRate()).isEqualTo(67);
	}

	private BreadRecordStoreVisitProjection storeVisit(Long storeId, String name, Long visits) {
		return new BreadRecordStoreVisitProjection() {
			@Override
			public Long getStoreId() {
				return storeId;
			}

			@Override
			public String getName() {
				return name;
			}

			@Override
			public Long getVisits() {
				return visits;
			}
		};
	}

	private BreadRecordRegionProjection region(String region1st, String regionKey) {
		return new BreadRecordRegionProjection() {
			@Override
			public String getRegion1st() {
				return region1st;
			}

			@Override
			public String getRegionKey() {
				return regionKey;
			}
		};
	}
}
