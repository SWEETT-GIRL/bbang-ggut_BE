package sweetgirl.croissant.breadlog.service;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.dto.projection.BreadRecordRegionProjection;
import sweetgirl.croissant.breadlog.dto.projection.BreadRecordStoreVisitProjection;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordSummaryResponse;
import sweetgirl.croissant.breadlog.entity.BreadRecordTag;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.store.RegionFormatter;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BreadRecordSummaryService {

	private static final LocalDateTime MIN_DATE = LocalDateTime.of(1, 1, 1, 0, 0);
	private static final LocalDateTime MAX_DATE = LocalDateTime.of(9999, 12, 31, 23, 59, 59);
	private static final List<String> SIDO_ORDER = List.of(
		"서울", "경기", "인천", "강원", "충북", "충남", "세종", "대전", "경북",
		"경남", "대구", "울산", "부산", "전북", "전남", "광주", "제주");
	private static final Set<String> VALID_SIDOS = Set.copyOf(SIDO_ORDER);

	private final MemberReader memberReader;
	private final BreadRecordRepository breadRecordRepository;
	private final BreadRecordTagRepository tagRepository;

	public BreadRecordSummaryResponse getSummary(Long memberId, LocalDateTime from, LocalDateTime to) {
		memberReader.getMember(memberId);
		validateDateRange(from, to);
		LocalDateTime rangeStart = from != null ? from : MIN_DATE;
		LocalDateTime rangeEnd = to != null ? to : MAX_DATE;
		List<BreadRecordStoreVisitProjection> storeVisits = breadRecordRepository.findStoreVisits(
			memberId, rangeStart, rangeEnd);
		List<String> sidos = resolveSidos(breadRecordRepository.findVisitedRegions(
			memberId, rangeStart, rangeEnd));
		List<BreadRecordSummaryResponse.TopStore> topStores = storeVisits.stream()
			.filter(visit -> visit.getStoreId() != null)
			.limit(5)
			.map(visit -> new BreadRecordSummaryResponse.TopStore(
				visit.getStoreId(), visit.getName(), visit.getVisits()))
			.toList();

		return new BreadRecordSummaryResponse(
			tagRepository.countBreads(memberId, rangeStart, rangeEnd),
			storeVisits.size(),
			sidos,
			resolveBreadCategories(memberId, rangeStart, rangeEnd),
			topStores,
			calculateRevisitRate(storeVisits));
	}

	/**
	 * 먹은 빵 종류 목록. 분류를 못 정한 태그('빵'·'기타')는 뺀다 — 종류를 셌다고 말할 수 없는 것들이다.
	 * 이전에는 '기타'만 걸러 '빵'이 종류인 양 섞여 나갔다.
	 */
	private List<String> resolveBreadCategories(Long memberId, LocalDateTime from, LocalDateTime to) {
		return tagRepository.findTagsWithMenu(memberId, from, to).stream()
			.filter(tag -> !tag.uncategorized())
			.map(BreadRecordTag::resolvedCategory)
			.distinct()
			.sorted()
			.toList();
	}

	private List<String> resolveSidos(List<BreadRecordRegionProjection> regions) {
		Set<String> visited = new LinkedHashSet<>();
		for (BreadRecordRegionProjection region : regions) {
			String sido = RegionFormatter.shortSido(region.getRegion1st());
			if (sido == null && region.getRegionKey() != null) {
				sido = region.getRegionKey().split("_", 2)[0];
			}
			if (sido != null && VALID_SIDOS.contains(sido)) {
				visited.add(sido);
			}
		}
		return SIDO_ORDER.stream().filter(visited::contains).toList();
	}

	private int calculateRevisitRate(List<BreadRecordStoreVisitProjection> storeVisits) {
		if (storeVisits.isEmpty()) {
			return 0;
		}
		long revisitedStores = storeVisits.stream().filter(visit -> visit.getVisits() > 1).count();
		return (int)Math.round(revisitedStores * 100.0 / storeVisits.size());
	}

	private void validateDateRange(LocalDateTime from, LocalDateTime to) {
		if (from != null && to != null && !from.isBefore(to)) {
			throw new BreadLogException(BreadLogErrorCode.INVALID_DATE_RANGE);
		}
	}
}
