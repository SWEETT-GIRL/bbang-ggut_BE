package sweetgirl.croissant.personalization.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.personalization.code.PersonalizationErrorCode;
import sweetgirl.croissant.personalization.config.PersonalizationProperties;
import sweetgirl.croissant.personalization.dto.response.InteractionIngestResult;
import sweetgirl.croissant.personalization.enums.InteractionType;
import sweetgirl.croissant.personalization.exception.PersonalizationException;
import sweetgirl.croissant.personalization.repository.UserInteractionIngestJdbcRepository;

/**
 * 사용자 × 가게 신호 적재 (#663 1단계). 매일 04:00 전날분을, 관리자는 아무 날짜 범위나 옮겨 적는다. 스케줄러와 수동 실행이 같은
 * 경로를 타고 advisory lock 으로 겹침을 막는다(인기도 갱신과 같은 방식). 프로필 재계산은 이 뒤 단계(2단계)다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class UserInteractionIngestCommandService {

	// pg advisory lock 키 — 이 적재 작업 전용 임의 상수. 인기도 갱신(20260818_1)과 겹치지 않는다.
	static final long INGEST_LOCK_KEY = 20260918_1L;

	private final UserInteractionIngestJdbcRepository ingestRepository;
	private final PersonalizationProperties personalizationProperties;

	/** 스케줄러가 부른다 — 어제 00:00 부터 오늘 00:00 전까지(JVM 시간대 = DB 벽시계, Asia/Seoul). */
	public InteractionIngestResult ingestYesterday() {
		LocalDate today = LocalDate.now();
		return ingest(today.minusDays(1), today);
	}

	/**
	 * @param fromDate 포함
	 * @param toDate 제외 — 같은 범위를 다시 돌려도 유니크 제약으로 중복이 없다(재실행 안전)
	 * @throws PersonalizationException 범위가 거꾸로면 400, 다른 적재가 실행 중이면 409
	 */
	public InteractionIngestResult ingest(LocalDate fromDate, LocalDate toDate) {
		if (!fromDate.isBefore(toDate)) {
			throw new PersonalizationException(PersonalizationErrorCode.INGEST_RANGE_INVALID);
		}
		acquireLock();
		LocalDateTime from = fromDate.atStartOfDay();
		LocalDateTime to = toDate.atStartOfDay();
		long startedAt = System.currentTimeMillis();
		Map<InteractionType, Integer> byType = new EnumMap<>(InteractionType.class);
		byType.put(InteractionType.SAVE, ingestRepository.insertSaves(from, to));
		byType.put(InteractionType.PLAN, ingestRepository.insertPlans(from, to));
		byType.put(InteractionType.RECORD_STORE, ingestRepository.insertRecordStores(from, to));
		byType.put(InteractionType.RECORD_BREAD, ingestRepository.insertRecordBreads(from, to));
		byType.put(InteractionType.STORE_VIEW, ingestRepository.insertStoreViews(from, to,
			personalizationProperties.getIngestConversionWindowMinutes()));
		byType.put(InteractionType.COURSE_REMOVE, ingestRepository.insertCourseRemoves(from, to));
		byType.put(InteractionType.RECO_DISMISSED, ingestRepository.insertRecoDismissed(from, to));
		byType.put(InteractionType.RECO_UNCHECK, ingestRepository.insertRecoUnchecks(from, to));
		int inserted = byType.values().stream().mapToInt(Integer::intValue).sum();
		log.info("[개인화] 신호 적재 완료 from={} to={} inserted={} byType={} elapsedMs={}", fromDate, toDate, inserted,
			byType, System.currentTimeMillis() - startedAt);
		return new InteractionIngestResult(fromDate, toDate, inserted, byType);
	}

	/**
	 * 담기 백필 — 이벤트(담는 요청의 회원)가 붙기 전에 담긴 가게를 코스 주인 기준으로 한 번 옮긴다. until 은 이벤트 배포 시각.
	 *
	 * @return 새로 들어간 행 수
	 */
	public int backfillCourseAddsByOwner(LocalDate untilDate) {
		acquireLock();
		int inserted = ingestRepository.backfillCourseAddsByOwner(untilDate.atStartOfDay());
		log.info("[개인화] 담기 백필(코스 주인 기준) until={} inserted={}", untilDate, inserted);
		return inserted;
	}

	private void acquireLock() {
		if (!ingestRepository.tryLock(INGEST_LOCK_KEY)) {
			throw new PersonalizationException(PersonalizationErrorCode.INTERACTION_INGEST_ALREADY_RUNNING);
		}
	}
}
