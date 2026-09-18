package sweetgirl.croissant.personalization.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.personalization.code.PersonalizationErrorCode;
import sweetgirl.croissant.personalization.config.PersonalizationProperties;
import sweetgirl.croissant.personalization.dto.response.TasteProfileRefreshResult;
import sweetgirl.croissant.personalization.entity.TasteAxis;
import sweetgirl.croissant.personalization.entity.UserTasteProfile;
import sweetgirl.croissant.personalization.enums.TasteProfileRefreshOutcome;
import sweetgirl.croissant.personalization.exception.PersonalizationException;
import sweetgirl.croissant.personalization.repository.TasteSignal;
import sweetgirl.croissant.personalization.repository.UserTasteProfileRepository;
import sweetgirl.croissant.personalization.repository.UserTasteSignalJdbcRepository;

/**
 * 취향 프로필 재계산 (#663 ②). 신호 적재(04:00) 뒤 전체를, 검사 제출 때 그 회원을 다시 계산한다.
 *
 * <p>version 은 추천이 읽는 값(축·타입·태그·종류)이 바뀐 회원만 올린다 — 매일 전원의 version 이 오르면 저장된 추천의 재사용 키가
 * 매일 깨진다. 유형·카운트만 바뀌면 version 을 두고 값만 갱신하고, 아무것도 안 바뀌면 쓰지 않는다.
 *
 * <p>동시성(#732 W3): 전체 재계산은 바깥 트랜잭션이 advisory xact lock 을 쥐고 회원마다 REQUIRES_NEW 로 짧게 쓴다 — 한 회원의 실패가
 * 나머지를 되돌리지 않는다. 회원 행은 {@code INSERT … ON CONFLICT DO NOTHING} 뒤 {@code SELECT … FOR UPDATE} 로 잡아 검사 제출과
 * 줄을 선다.
 */
@Slf4j
@Service
public class UserTasteProfileCommandService {

	/** 인기도(20260818_1) · 적재(20260918_1)와 다른 키 */
	static final long PROFILE_REFRESH_LOCK_KEY = 20260918_2L;

	private final UserTasteSignalJdbcRepository signalRepository;
	private final UserTasteProfileRepository profileRepository;
	private final TasteProfileCalculator calculator;
	private final PersonalizationProperties personalizationProperties;
	private final TransactionTemplate perMemberTransaction;

	public UserTasteProfileCommandService(UserTasteSignalJdbcRepository signalRepository,
			UserTasteProfileRepository profileRepository, TasteProfileCalculator calculator,
			PersonalizationProperties personalizationProperties, PlatformTransactionManager transactionManager) {
		this.signalRepository = signalRepository;
		this.profileRepository = profileRepository;
		this.calculator = calculator;
		this.personalizationProperties = personalizationProperties;
		this.perMemberTransaction = new TransactionTemplate(transactionManager);
		this.perMemberTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * 창 안 신호가 있는 회원 ∪ 프로필 있는 회원 전부. 회원별 트랜잭션이라 한 명이 실패해도 나머지는 커밋된다.
	 *
	 * @throws PersonalizationException 다른 재계산이 실행 중이면 409
	 */
	@Transactional
	public TasteProfileRefreshResult recomputeAll() {
		if (!signalRepository.tryLock(PROFILE_REFRESH_LOCK_KEY)) {
			throw new PersonalizationException(PersonalizationErrorCode.TASTE_PROFILE_REFRESH_ALREADY_RUNNING);
		}
		LocalDateTime now = LocalDateTime.now();
		long startedAt = System.currentTimeMillis();
		List<Long> memberIds = signalRepository.findMemberIdsToRecompute(signalWindowStart(now));
		int recomputed = 0;
		int statsOnly = 0;
		int unchanged = 0;
		int failed = 0;
		for (Long memberId : memberIds) {
			try {
				TasteProfileRefreshOutcome outcome = perMemberTransaction.execute(
					status -> recomputeLocked(memberId, now));
				switch (outcome) {
					case RECOMPUTED -> recomputed++;
					case STATS_ONLY -> statsOnly++;
					default -> unchanged++;
				}
			} catch (RuntimeException e) {
				failed++;
				log.warn("[개인화] 프로필 재계산 실패 — 건너뜀, 다음 배치에서 재시도 memberId={}", memberId, e);
			}
		}
		log.info("[개인화] 프로필 재계산 완료 members={} recomputed={} statsOnly={} unchanged={} failed={} elapsedMs={}",
			memberIds.size(), recomputed, statsOnly, unchanged, failed, System.currentTimeMillis() - startedAt);
		return new TasteProfileRefreshResult(memberIds.size(), recomputed, statsOnly, unchanged, failed);
	}

	/** 한 회원만 — 관리자 수동 실행. 행 잠금만으로 배치와 줄을 선다. */
	@Transactional
	public TasteProfileRefreshOutcome recompute(Long memberId) {
		return recomputeLocked(memberId, LocalDateTime.now());
	}

	/** 행이 없으면 만들고 FOR UPDATE 로 잠가 돌려준다. 호출자의 트랜잭션 안에서 쓴다(검사 제출). */
	public UserTasteProfile lockOrCreate(Long memberId, LocalDateTime now) {
		profileRepository.insertEmptyIfAbsent(memberId, now);
		return profileRepository.findByIdForUpdate(memberId)
			.orElseThrow(() -> new PersonalizationException(PersonalizationErrorCode.TASTE_PROFILE_ROW_MISSING));
	}

	/**
	 * 이미 잠근 프로필에 신호를 합산한다 — 검사 제출 직후. 검사 축은 프로필에 방금 쓰인 값을 쓴다. 신호가 없으면 검사 축 그대로라
	 * 바뀐 게 없고, version 도 다시 오르지 않는다.
	 */
	public TasteProfileRefreshOutcome mergeSignals(UserTasteProfile profile, TasteAxis quizAxis, LocalDateTime now) {
		return apply(profile, snapshotOf(profile.getMemberId(), quizAxis, now), now);
	}

	private TasteProfileRefreshOutcome recomputeLocked(Long memberId, LocalDateTime now) {
		UserTasteProfile profile = lockOrCreate(memberId, now);
		return apply(profile, snapshotOf(memberId, quizAxisOf(profile), now), now);
	}

	/**
	 * 검사 축은 따로 저장되지 않는다(프로필 축은 합산 결과). 검사 응답에서 다시 만든다 — TasteQuizCommandService 와 같은 규칙(A −1 ·
	 * B +1 의 축별 평균). 검사를 안 했으면 null.
	 */
	private static TasteAxis quizAxisOf(UserTasteProfile profile) {
		return profile.getQuizAnswers() == null ? null : TasteQuizCommandService.axisOfStored(profile.getQuizAnswers());
	}

	private TasteProfileSnapshot snapshotOf(Long memberId, TasteAxis quizAxis, LocalDateTime now) {
		List<TasteSignal> signals = signalRepository.findSignals(memberId, signalWindowStart(now));
		int visits = signalRepository.countVisits(memberId,
			now.minusDays(personalizationProperties.getProfile().getUserTypeWindowDays()));
		return calculator.calculate(signals, quizAxis, visits, now);
	}

	private TasteProfileRefreshOutcome apply(UserTasteProfile profile, TasteProfileSnapshot snapshot,
			LocalDateTime now) {
		if (!snapshot.sameTasteAs(profile)) {
			profile.recompute(snapshot.axis(), snapshot.typeCode(), snapshot.typeSource(), snapshot.tags(),
				snapshot.topKinds(), snapshot.userType(), snapshot.strongStoreCount(), snapshot.breadSignalCount(),
				now);
			return TasteProfileRefreshOutcome.RECOMPUTED;
		}
		if (!snapshot.sameStatsAs(profile)) {
			profile.refreshStats(snapshot.userType(), snapshot.strongStoreCount(), snapshot.breadSignalCount(), now);
			return TasteProfileRefreshOutcome.STATS_ONLY;
		}
		return TasteProfileRefreshOutcome.UNCHANGED;
	}

	private LocalDateTime signalWindowStart(LocalDateTime now) {
		return now.minusDays(personalizationProperties.getProfile().getSignalWindowDays());
	}
}
