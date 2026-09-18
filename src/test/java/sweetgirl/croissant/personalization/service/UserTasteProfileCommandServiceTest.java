package sweetgirl.croissant.personalization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.personalization.code.PersonalizationErrorCode;
import sweetgirl.croissant.personalization.config.PersonalizationProperties;
import sweetgirl.croissant.personalization.dto.response.TasteProfileRefreshResult;
import sweetgirl.croissant.personalization.entity.TasteAxis;
import sweetgirl.croissant.personalization.entity.UserTasteProfile;
import sweetgirl.croissant.personalization.enums.InteractionType;
import sweetgirl.croissant.personalization.enums.TasteProfileRefreshOutcome;
import sweetgirl.croissant.personalization.enums.TasteTypeSource;
import sweetgirl.croissant.personalization.enums.TasteUserType;
import sweetgirl.croissant.personalization.exception.PersonalizationException;
import sweetgirl.croissant.personalization.repository.TasteSignal;
import sweetgirl.croissant.personalization.repository.UserTasteProfileRepository;
import sweetgirl.croissant.personalization.repository.UserTasteSignalJdbcRepository;

/** 계산기는 진짜를 쓴다(값이 바뀌었는지 판정까지가 이 서비스의 몫이라 목으로 두면 검증이 비어 버린다). */
@ExtendWith(MockitoExtension.class)
class UserTasteProfileCommandServiceTest {

	private static final LocalDateTime YESTERDAY = LocalDateTime.now().minusDays(1);
	private static final TasteAxis ALL_PLUS = axis(1);
	private static final TasteAxis ALL_MINUS = axis(-1);
	private static final List<Map<String, String>> ALL_B = List.of(
		Map.of("axis", "taste", "choice", "B"), Map.of("axis", "texture", "choice", "B"),
		Map.of("axis", "filling", "choice", "B"), Map.of("axis", "vibe", "choice", "B"));

	@Mock
	private UserTasteSignalJdbcRepository signalRepository;
	@Mock
	private UserTasteProfileRepository profileRepository;
	@Mock
	private MemberRepository memberRepository;
	@Mock
	private PlatformTransactionManager transactionManager;

	private UserTasteProfileCommandService profileCommandService;

	private static TasteAxis axis(double value) {
		BigDecimal scaled = BigDecimal.valueOf(value).setScale(3);
		return TasteAxis.of(scaled, scaled, scaled, scaled);
	}

	private static TasteSignal saved(long storeId, TasteAxis storeAxis) {
		return new TasteSignal(InteractionType.SAVE, storeId, null, null, YESTERDAY, storeAxis, Map.of(), null);
	}

	private static TasteSignal viewed(long storeId) {
		return new TasteSignal(InteractionType.STORE_VIEW, storeId, null, null, YESTERDAY, null, Map.of(), null);
	}

	@BeforeEach
	void setUp() {
		PersonalizationProperties properties = new PersonalizationProperties();
		profileCommandService = new UserTasteProfileCommandService(signalRepository, profileRepository,
			memberRepository, new TasteProfileCalculator(properties), properties, transactionManager);
	}

	@Test
	@DisplayName("다른 재계산이 락을 쥐고 있으면 409 — 회원 목록도 읽지 않는다")
	void recomputeAll_whenLocked_throwsConflict() {
		given(signalRepository.tryLock(UserTasteProfileCommandService.PROFILE_REFRESH_LOCK_KEY)).willReturn(false);

		assertThatThrownBy(() -> profileCommandService.recomputeAll())
			.isInstanceOfSatisfying(PersonalizationException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(PersonalizationErrorCode.TASTE_PROFILE_REFRESH_ALREADY_RUNNING));
		then(signalRepository).should(never()).findMemberIdsToRecompute(any());
	}

	@Test
	@DisplayName("전체 재계산: 추천이 읽는 값이 바뀐 회원만 version+1, 유형·카운트만 바뀌면 version 유지, 실패한 회원은 건너뛰고 센다")
	void recomputeAll_countsOutcomes_andIsolatesFailures() {
		given(signalRepository.tryLock(anyLong())).willReturn(true);
		given(signalRepository.findMemberIdsToRecompute(any())).willReturn(List.of(1L, 2L, 3L, 4L));
		UserTasteProfile recomputed = UserTasteProfile.createEmpty(1L, YESTERDAY);
		UserTasteProfile statsOnly = UserTasteProfile.createEmpty(2L, YESTERDAY);
		UserTasteProfile unchanged = UserTasteProfile.createEmpty(3L, YESTERDAY);
		given(profileRepository.findByIdForUpdate(1L)).willReturn(Optional.of(recomputed));
		given(profileRepository.findByIdForUpdate(2L)).willReturn(Optional.of(statsOnly));
		given(profileRepository.findByIdForUpdate(3L)).willReturn(Optional.of(unchanged));
		given(profileRepository.findByIdForUpdate(4L)).willThrow(new IllegalStateException("잠금 실패"));
		given(signalRepository.findSignals(eq(1L), any())).willReturn(List.of(saved(10L, ALL_PLUS)));
		given(signalRepository.findSignals(eq(2L), any())).willReturn(List.of(viewed(11L))); // 축 없음 · 유형만 BROWSER
		given(signalRepository.findSignals(eq(3L), any())).willReturn(List.of());

		TasteProfileRefreshResult result = profileCommandService.recomputeAll();

		assertThat(result).isEqualTo(new TasteProfileRefreshResult(4, 1, 1, 1, 1));
		assertThat(recomputed.getVersion()).isEqualTo(2);
		assertThat(recomputed.getTypeSource()).isEqualTo(TasteTypeSource.SIGNAL);
		assertThat(recomputed.getAxis()).isEqualTo(ALL_PLUS);
		assertThat(statsOnly.getVersion()).isEqualTo(1);
		assertThat(statsOnly.getUserType()).isEqualTo(TasteUserType.BROWSER);
		assertThat(unchanged.getVersion()).isEqualTo(1);
		then(profileRepository).should().insertEmptyIfAbsent(eq(1L), any());
	}

	@Test
	@DisplayName("한 회원 재계산: 빈 행을 만들고(있으면 무시) 잠근 뒤 신호로 채운다")
	void recompute_singleMember_createsRowAndFills() {
		UserTasteProfile profile = UserTasteProfile.createEmpty(7L, YESTERDAY);
		given(memberRepository.existsById(7L)).willReturn(true);
		given(profileRepository.findByIdForUpdate(7L)).willReturn(Optional.of(profile));
		given(signalRepository.findSignals(eq(7L), any())).willReturn(List.of(saved(10L, ALL_MINUS)));

		TasteProfileRefreshOutcome outcome = profileCommandService.recompute(7L);

		assertThat(outcome).isEqualTo(TasteProfileRefreshOutcome.RECOMPUTED);
		assertThat(profile.getAxis()).isEqualTo(ALL_MINUS);
		assertThat(profile.getTypeCode()).isEqualTo("SFRO");
		assertThat(profile.getStrongStoreCount()).isEqualTo(1);
		then(profileRepository).should().insertEmptyIfAbsent(eq(7L), any());
	}

	@Test
	@DisplayName("없는 회원을 재계산하면 404 — FK 위반 500 이 아니라")
	void recompute_unknownMember_throwsNotFound() {
		given(memberRepository.existsById(404L)).willReturn(false);

		assertThatThrownBy(() -> profileCommandService.recompute(404L))
			.isInstanceOfSatisfying(MemberException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND));
		then(profileRepository).should(never()).insertEmptyIfAbsent(anyLong(), any());
	}

	@Test
	@DisplayName("빈 행을 넣은 직후에도 잠글 행이 없으면 500 — 약속이 깨진 것")
	void lockOrCreate_rowMissing_throws() {
		given(profileRepository.findByIdForUpdate(7L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> profileCommandService.lockOrCreate(7L, YESTERDAY))
			.isInstanceOfSatisfying(PersonalizationException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(PersonalizationErrorCode.TASTE_PROFILE_ROW_MISSING));
	}

	@Test
	@DisplayName("검사 직후 합산: 신호가 없으면 검사 축 그대로라 UNCHANGED — version 이 두 번 오르지 않는다")
	void mergeSignals_withoutSignals_keepsQuizAxis() {
		UserTasteProfile profile = UserTasteProfile.createEmpty(7L, YESTERDAY);
		profile.applyQuiz(ALL_B, ALL_PLUS, "PCLN", YESTERDAY);
		given(signalRepository.findSignals(eq(7L), any())).willReturn(List.of());

		TasteProfileRefreshOutcome outcome = profileCommandService.mergeSignals(profile, ALL_PLUS, YESTERDAY,
			YESTERDAY);

		assertThat(outcome).isEqualTo(TasteProfileRefreshOutcome.UNCHANGED);
		assertThat(profile.getVersion()).isEqualTo(2);
		assertThat(profile.getTypeSource()).isEqualTo(TasteTypeSource.QUIZ);
	}

	@Test
	@DisplayName("검사 직후 합산: 신호가 있으면 검사 ×3 과 합쳐 축이 바뀌고 version 이 한 번 더 오른다")
	void mergeSignals_withSignals_recomputesMergedAxis() {
		UserTasteProfile profile = UserTasteProfile.createEmpty(7L, YESTERDAY);
		profile.applyQuiz(ALL_B, ALL_PLUS, "PCLN", YESTERDAY);
		given(signalRepository.findSignals(eq(7L), any())).willReturn(List.of(saved(10L, ALL_MINUS)));

		TasteProfileRefreshOutcome outcome = profileCommandService.mergeSignals(profile, ALL_PLUS, YESTERDAY,
			YESTERDAY);

		assertThat(outcome).isEqualTo(TasteProfileRefreshOutcome.RECOMPUTED);
		assertThat(profile.getVersion()).isEqualTo(3);
		assertThat(profile.getAxis()).isEqualTo(axis(0.5)); // (3 − 1) ÷ 4
		assertThat(profile.getTypeSource()).isEqualTo(TasteTypeSource.SIGNAL);
		assertThat(profile.getQuizAnsweredAt()).isEqualTo(YESTERDAY);
	}

	@Test
	@DisplayName("배치는 저장된 검사 응답에서 검사 축을 되살려 합산한다 — 검사와 신호가 같은 날이면 같은 감쇠라 정확히 (3 − 1) ÷ 4")
	void recompute_usesStoredQuizAnswers() {
		UserTasteProfile quizOnly = UserTasteProfile.createEmpty(7L, YESTERDAY);
		quizOnly.applyQuiz(ALL_B, ALL_PLUS, "PCLN", YESTERDAY);
		given(memberRepository.existsById(7L)).willReturn(true);
		given(profileRepository.findByIdForUpdate(7L)).willReturn(Optional.of(quizOnly));
		given(signalRepository.findSignals(eq(7L), any())).willReturn(List.of());
		assertThat(profileCommandService.recompute(7L)).isEqualTo(TasteProfileRefreshOutcome.UNCHANGED);

		given(signalRepository.findSignals(eq(7L), any())).willReturn(List.of(saved(10L, ALL_MINUS)));
		assertThat(profileCommandService.recompute(7L)).isEqualTo(TasteProfileRefreshOutcome.RECOMPUTED);
		// 검사(어제)와 신호(어제)가 같은 감쇠를 타므로 (3d − d) ÷ (3d + d) = 0.5 — 날이 지나도 같은 값
		assertThat(quizOnly.getAxis()).isEqualTo(axis(0.5));
		assertThat(quizOnly.getTypeSource()).isEqualTo(TasteTypeSource.SIGNAL);
	}
}
