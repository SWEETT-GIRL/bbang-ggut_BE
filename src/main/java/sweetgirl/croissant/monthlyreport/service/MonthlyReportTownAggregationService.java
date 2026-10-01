package sweetgirl.croissant.monthlyreport.service;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse.Town;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportSourceSnapshot;
import sweetgirl.croissant.store.RegionFormatter;

/** 월말 로그에 저장된 지역만 사용한다. 현재 가게 주소로 결측을 추정하지 않는다. */
@Service
public class MonthlyReportTownAggregationService {
	public static final String VERSION = "record-region-history-v1";

	public List<Town> aggregate(MonthlyReportSourceSnapshot source) {
		return source.records().stream().map(record -> parse(record.regionKey()))
			.filter(Objects::nonNull).distinct()
			.sorted(Comparator.comparing(Town::region).thenComparing(Town::name)).toList();
	}

	private Town parse(String regionKey) {
		if (regionKey == null || regionKey.isBlank()) {
			return null;
		}
		List<String> parts = Arrays.stream(regionKey.split("_", -1)).map(String::strip).toList();
		if (parts.size() < 2 || parts.stream().anyMatch(String::isBlank)) {
			return null;
		}
		String name = parts.get(parts.size() - 1);
		// 시도·시군구까지만 있는 값은 동네로 표시하지 않는다. 세종_도담동 같은 두 조각도 허용한다.
		if (!name.matches(".+[읍면동리가]$")) {
			return null;
		}
		String region = RegionFormatter.shortSido(parts.get(0));
		if (parts.size() > 2) {
			region += " " + String.join(" ", parts.subList(1, parts.size() - 1));
		}
		return new Town(name, region);
	}
}
