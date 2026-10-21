package sweetgirl.croissant.profile.service;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;

/**
 * 칭호 "OO동 빵순이" — 공개 빵로그가 가장 많은 동네, 그 동네 기록이 {@value #MIN_PUBLIC_RECORDS}개부터.
 * 비공개 · 숨김은 세지 않고, 동 조각이 없는 region_key(시도_구만 있는 가게)는 집계에서 뺀다.
 */
@Component
@RequiredArgsConstructor
public class MemberTitleReader {

	static final int MIN_PUBLIC_RECORDS = 3;
	static final String TITLE_SUFFIX = " 빵순이";

	private final BreadRecordRepository breadRecordRepository;

	public Optional<String> titleOf(Long memberId) {
		return breadRecordRepository.findTopPublicTown(memberId, MIN_PUBLIC_RECORDS)
			.map(projection -> townOf(projection.getRegionKey()))
			.filter(Optional::isPresent)
			.map(Optional::get)
			.map(town -> town + TITLE_SUFFIX);
	}

	/** "서울_성동구_성수동" → "성수동". 마지막 조각이 읍 · 면 · 동 · 리 · 가로 끝나야 동네다(월간 리포트와 같은 규칙). */
	static Optional<String> townOf(String regionKey) {
		if (regionKey == null || regionKey.isBlank()) {
			return Optional.empty();
		}
		List<String> parts = Arrays.stream(regionKey.split("_", -1)).map(String::strip).toList();
		if (parts.size() < 2 || parts.stream().anyMatch(String::isBlank)) {
			return Optional.empty();
		}
		String name = parts.get(parts.size() - 1);
		return name.matches(".+[읍면동리가]$") ? Optional.of(name) : Optional.empty();
	}
}
