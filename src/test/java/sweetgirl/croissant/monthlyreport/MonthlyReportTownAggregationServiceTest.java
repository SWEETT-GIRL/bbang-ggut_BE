package sweetgirl.croissant.monthlyreport;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse.Town;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportSourceSnapshot;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportSourceSnapshot.RecordState;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportTownAggregationService;

class MonthlyReportTownAggregationServiceTest {
	private final MonthlyReportTownAggregationService service = new MonthlyReportTownAggregationService();

	@Test
	void deduplicatesFullRegionAndKeepsSameTownNamesInDifferentDistricts() {
		assertThat(service.aggregate(source(" 서울특별시 _ 강남구 _ 신사동 ", "서울_강남구_신사동",
			"서울_은평구_신사동", "세종특별자치시_도담동", "서울_종로구_종로1가")))
			.containsExactly(new Town("신사동", "서울 강남구"), new Town("신사동", "서울 은평구"),
				new Town("종로1가", "서울 종로구"), new Town("도담동", "세종"));
	}

	@Test
	void excludesMissingOrIncompleteRegionWithoutLookingUpCurrentStore() {
		assertThat(service.aggregate(source(null, "", " ", "서울", "서울_강남구", "서울__성수동", "성수동")))
			.isEmpty();
	}

	private MonthlyReportSourceSnapshot source(String... keys) {
		LocalDateTime time = LocalDateTime.of(2025, 12, 1, 0, 0);
		List<RecordState> records = IntStream.range(0, keys.length)
			.mapToObj(i -> new RecordState((long)i + 1, 11L, keys[i], time, time, null, null, List.of())).toList();
		return new MonthlyReportSourceSnapshot(time, records, List.of(), Map.of(), Set.of(), Set.of(), 0);
	}
}
