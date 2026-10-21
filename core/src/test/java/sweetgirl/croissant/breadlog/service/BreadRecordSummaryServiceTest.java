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
import sweetgirl.croissant.breadlog.entity.BreadRecordTag;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.MenuCategory;
import sweetgirl.croissant.menu.enums.MenuKind;

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
		given(tagRepository.findTagsWithMenu(7L, from, to)).willReturn(List.of(
			tag("소금빵"), tag("식빵"), tag("소금빵"), tag("기타"), tag("빵")));

		BreadRecordSummaryResponse result = summaryService.getSummary(7L, from, to);

		assertThat(result.totalBreads()).isEqualTo(6L);
		assertThat(result.totalStores()).isEqualTo(3L);
		assertThat(result.sidos()).containsExactly("서울", "대전");
		assertThat(result.breadCategories()).containsExactly("소금빵", "식빵");
		assertThat(result.topStores()).extracting(BreadRecordSummaryResponse.TopStore::storeId)
			.containsExactly(1L, 2L);
		assertThat(result.revisitRate()).isEqualTo(67);
	}

	@Test
	@DisplayName("지역 정보가 없는 기록은 시도 집계에서 제외한다")
	void getSummary_ignoresRecordsWithoutRegion() {
		LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
		LocalDateTime to = LocalDateTime.of(2027, 1, 1, 0, 0);
		given(breadRecordRepository.findStoreVisits(7L, from, to)).willReturn(List.of());
		given(breadRecordRepository.findVisitedRegions(7L, from, to)).willReturn(List.of(
			region(null, null),
			region(null, "대전_유성구_궁동")));
		given(tagRepository.findTagsWithMenu(7L, from, to)).willReturn(List.of());

		BreadRecordSummaryResponse result = summaryService.getSummary(7L, from, to);

		assertThat(result.sidos()).containsExactly("대전");
	}

	@Test
	@DisplayName("룰이 '빵'·'기타'로 물러선 태그는 가리키는 메뉴의 분류로 집계한다")
	void getSummary_readsCategoryFromMenu() {
		LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
		LocalDateTime to = LocalDateTime.of(2027, 1, 1, 0, 0);
		given(breadRecordRepository.findStoreVisits(7L, from, to)).willReturn(List.of());
		given(breadRecordRepository.findVisitedRegions(7L, from, to)).willReturn(List.of());
		given(tagRepository.findTagsWithMenu(7L, from, to)).willReturn(List.of(
			taggedMenu("소금버터빵", "빵", MenuKind.소금빵),
			taggedMenu("잠봉뵈르", "기타", MenuKind.샌드위치),
			// 메뉴도 '빵'에 머물면 여전히 모르는 것 — 집계에서 뺀다
			taggedMenu("작은메아리", "기타", MenuKind.빵)));

		BreadRecordSummaryResponse result = summaryService.getSummary(7L, from, to);

		assertThat(result.breadCategories()).containsExactly("샌드위치", "소금빵");
	}

	@Test
	@DisplayName("룰이 구체 분류를 냈으면 메뉴 분류가 달라도 룰 값을 쓴다")
	void getSummary_keepsRuleCategoryOverMenu() {
		LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
		LocalDateTime to = LocalDateTime.of(2027, 1, 1, 0, 0);
		given(breadRecordRepository.findStoreVisits(7L, from, to)).willReturn(List.of());
		given(breadRecordRepository.findVisitedRegions(7L, from, to)).willReturn(List.of());
		given(tagRepository.findTagsWithMenu(7L, from, to)).willReturn(List.of(
			taggedMenu("밤식빵", "식빵", MenuKind.밤빵)));

		BreadRecordSummaryResponse result = summaryService.getSummary(7L, from, to);

		assertThat(result.breadCategories()).containsExactly("식빵");
	}

	private static BreadRecordTag tag(String breadCategory) {
		return BreadRecordTag.builder()
			.name(breadCategory)
			.breadCategory(breadCategory)
			.positionX(0.5)
			.positionY(0.5)
			.build();
	}

	private static BreadRecordTag taggedMenu(String name, String breadCategory, MenuKind menuKind) {
		return BreadRecordTag.builder()
			.name(name)
			.breadCategory(breadCategory)
			.positionX(0.5)
			.positionY(0.5)
			.menu(Menu.builder()
				.name(name)
				.menuCategory(MenuCategory.builder().name(menuKind).build())
				.build())
			.build();
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
