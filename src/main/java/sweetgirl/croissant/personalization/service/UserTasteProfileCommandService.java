package sweetgirl.croissant.personalization.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.repository.MemberRepository;
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
	/** 적재와 같은 시계 — occurred_at(DB 벽시계, KST)과 창·유예를 같은 기준으로 잰다 */
	public static final ZoneId ZONE_ID = UserInteractionIngestCommandService.ZONE_ID;

	private final UserTasteSignalJdbcRepository signalRepository;
	private final UserTasteProfileRepository profileRepository;
	private final MemberRepository memberRepository;
	private final TasteProfileCalculator calculator;
	private final PersonalizationProperties personalizationProperties;
	private final TransactionTemplate perMemberTransaction;

	public UserTasteProfileCommandService(UserTasteSignalJdbcRepository signalRepository,
			UserTasteProfileRepository profileRepository, MemberRepository memberRepository,
			TasteProfileCalculator calculator, PersonalizationProperties personalizationProperties,
			PlatformTransactionManager transactionManager) {
		this.signalRepository = signalRepository;
		this.profileRepository = profileRepository;
		this.memberRepository = memberRepository;
		this.calculator = calculator;
		this.personalizationProperties = personalizationProperties;
		this.perMemberTransaction = new TransactionTemplate(transactionManager);
		this.perMemberTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * 창 안 신호가 있는 회원 ∪ 프로필 있는 회원 전부(탈퇴 회원 제외). 회원별 트랜잭션이라 한 명이 실패해도 나머지는 커밋된다.
	 *
	 * @throws PersonalizationException 다른 재계산이 실행 중이면 409
	 */
	@Transactional
	public TasteProfileRefreshResult recomputeAll() {
		if (!signalRepository.tryLock(PROFILE_REFRESH_LOCK_KEY)) {
			throw new PersonalizationException(PersonalizationErrorCode.TASTE_PROFILE_REFRESH_ALREADY_RUNNING);
		}
		LocalDateTime now = LocalDateTime.now(ZONE_ID);
		long startedAt = System.currentTimeMillis();
		List<Long> memberIds = signalRepository.findMemberIdsToRecompute(signalWindowStart(now));
		int recomputed = 0;
		int statsOnly = 0;
		int unchanged = 0;
		int failed = 0;
		for (Long memberId : memberIds) {
			try {
				TasteProfileRefreshOutcome outcome = perMemberTransaction.execute(
					status -> recomputeUnderRowLock(memberId, now));
				switch (outcome) {
					case RECOMPUTED -> recomputed++;
					case STATS_ONLY -> statsOnly++;
					default -> unchanged++;
				}
			} catch (RuntimeException e) {
				failed++;
				if (failed == 1) {
					// 같은 원인으로 수백 명이 실패하면 스택이 수백 번 찍힌다 — 첫 건만 스택, 나머지는 한 줄
					log.warn("[개인화] 프로필 재계산 실패 — 건너뜀, 다음 배치에서 재시도 memberId={}", memberId, e);
				} else {
					log.warn("[개인화] 프로필 재계산 실패 — 건너뜀 memberId={} cause={}", memberId, e.toString());
				}
			}
		}
		log.info("[개인화] 프로필 재계산 완료 members={} recomputed={} statsOnly={} unchanged={} failed={} elapsedMs={}",
			memberIds.size(), recomputed, statsOnly, unchanged, failed, System.currentTimeMillis() - startedAt);
		return new TasteProfileRefreshResult(memberIds.size(), recomputed, statsOnly, unchanged, failed);
	}

	/**
	 * 한 회원만 — 관리자 수동 실행. 행 잠금만으로 배치와 줄을 선다.
	 *
	 * @throws MemberException 없는 회원이면 404 — FK 위반 500 이 아니라
	 */
	@Transactional
	public TasteProfileRefreshOutcome recompute(Long memberId) {
		if (!memberRepository.existsById(memberId)) {
			throw new MemberException(MemberErrorCode.MEMBER_NOT_FOUND);
		}
		return recomputeUnderRowLock(memberId, LocalDateTime.now(ZONE_ID));
	}

	/** 행이 없으면 만들고 FOR UPDATE 로 잠가 돌려준다. 호출자의 트랜잭션이 없으면 즉시 실패한다(MANDATORY). */
	@Transactional(propagation = Propagation.MANDATORY)
	public UserTasteProfile lockOrCreate(Long memberId, LocalDateTime now) {
		profileRepository.insertEmptyIfAbsent(memberId, now);
		return profileRepository.findByIdForUpdate(memberId)
			.orElseThrow(() -> new PersonalizationException(PersonalizationErrorCode.TASTE_PROFILE_ROW_MISSING));
	}

	/**
	 * 이미 잠근 프로필에 신호를 합산한다 — 검사 제출 직후. 신호가 없으면 검사 축 그대로라 바뀐 게 없고, version 도 오르지 않는다.
	 * 합산 실패는 호출자가 검사 축만 저장하는 것으로 이어가므로 트랜잭션을 rollback-only 로 만들지 않는다(noRollbackFor).
	 */
	@Transactional(propagation = Propagation.MANDATORY, noRollbackFor = RuntimeException.class)
	public TasteProfileRefreshOutcome mergeSignals(UserTasteProfile profile, TasteAxis quizAxis,
			LocalDateTime quizAnsweredAt, LocalDateTime now) {
		return writeIfChanged(profile, calculateSnapshot(profile.getMemberId(), quizAxis, quizAnsweredAt, now), now);
	}

	private TasteProfileRefreshOutcome recomputeUnderRowLock(Long memberId, LocalDateTime now) {
		UserTasteProfile profile = lockOrCreate(memberId, now);
		return writeIfChanged(profile,
			calculateSnapshot(memberId, storedQuizAxisOf(profile), profile.getQuizAnsweredAt(), now), now);
	}

	/** 검사 축은 따로 저장되지 않는다(프로필 축은 합산 결과). 저장된 검사 응답에서 다시 만든다. 검사를 안 했으면 null. */
	private static TasteAxis storedQuizAxisOf(UserTasteProfile profile) {
		return profile.getQuizAnswers() == null ? null : QuizAxisRule.fromStoredAnswers(profile.getQuizAnswers());
	}

	private TasteProfileSnapshot calculateSnapshot(Long memberId, TasteAxis quizAxis, LocalDateTime quizAnsweredAt,
			LocalDateTime now) {
		List<TasteSignal> signals = signalRepository.findSignals(memberId, signalWindowStart(now));
		int visits = signalRepository.countVisits(memberId,
			now.minusDays(personalizationProperties.getProfile().getUserTypeWindowDays()));
		return calculator.calculate(signals, quizAxis, quizAnsweredAt, visits, now);
	}

	/** 추천이 읽는 값이 바뀌면 recompute(version+1), 유형·카운트만이면 refreshStats(version 유지), 같으면 쓰지 않는다 */
	private TasteProfileRefreshOutcome writeIfChanged(UserTasteProfile profile, TasteProfileSnapshot snapshot,
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
