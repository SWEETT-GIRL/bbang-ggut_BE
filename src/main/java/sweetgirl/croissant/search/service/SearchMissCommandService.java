package sweetgirl.croissant.search.service;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.search.entity.SearchMiss;
import sweetgirl.croissant.search.repository.SearchMissRepository;
import sweetgirl.croissant.store.service.search.SearchOutcome;

/**
 * 결과를 못 낸 검색어를 적재한다.
 *
 * <p>관리자가 이 목록을 보고 없는 가게를 추가한다. 지표는 "얼마나 자주 실패했나"까지만 알려주므로
 * "무슨 검색어였나"는 여기에 쌓아야 한다.
 */
@Service
@RequiredArgsConstructor
public class SearchMissCommandService {

	// 검색어를 붙여 쓰든 띄어 쓰든 같은 행으로 묶기 위한 정규화 규칙.
	private static final Pattern NORMALIZE_PATTERN = Pattern.compile("[^a-zA-Z0-9가-힣]");

	private final SearchMissRepository searchMissRepository;

	/**
	 * 실패한 검색어를 기록한다. 이미 있으면 발생 횟수만 올린다.
	 *
	 * <p>UPDATE를 먼저 시도하고 0건일 때만 INSERT한다. 조회 후 분기하면 같은 검색어가 동시에
	 * 들어올 때 경합에서 깨지기 때문이다. 그래도 INSERT가 겹치면 unique 제약에 걸리는데,
	 * 그 예외 처리는 호출자(리스너)가 재시도로 맡는다 — 여기서 잡으면 트랜잭션이 이미
	 * rollback-only로 표시돼 같은 트랜잭션 안에서는 복구할 수 없다.
	 */
	@Transactional
	public void record(String keyword, SearchOutcome outcome, LocalDateTime occurredAt) {
		if (outcome == SearchOutcome.HIT) {
			return;
		}
		String normalizedKeyword = truncate(normalize(keyword));
		// 특수문자·공백만 입력한 경우. 처리할 것이 없다.
		if (normalizedKeyword.isBlank()) {
			return;
		}
		String truncatedKeyword = truncate(keyword);

		if (searchMissRepository.incrementOccurrence(normalizedKeyword, truncatedKeyword, outcome, occurredAt) > 0) {
			return;
		}
		searchMissRepository.save(
			SearchMiss.firstOccurrence(normalizedKeyword, truncatedKeyword, outcome, occurredAt));
	}

	private String normalize(String keyword) {
		if (keyword == null) {
			return "";
		}
		return NORMALIZE_PATTERN.matcher(keyword)
			.replaceAll("")
			.toLowerCase(Locale.ROOT);
	}

	// 봇이 긴 랜덤 문자열을 넣는 경우를 잘라낸다. 컬럼 길이를 넘기면 저장 자체가 실패한다.
	private String truncate(String value) {
		String trimmed = value == null ? "" : value.trim();
		return trimmed.length() <= SearchMiss.MAX_KEYWORD_LENGTH
			? trimmed
			: trimmed.substring(0, SearchMiss.MAX_KEYWORD_LENGTH);
	}
}
