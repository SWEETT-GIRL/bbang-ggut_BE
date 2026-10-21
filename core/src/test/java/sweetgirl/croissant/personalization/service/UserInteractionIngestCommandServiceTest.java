package sweetgirl.croissant.personalization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.personalization.code.PersonalizationErrorCode;
import sweetgirl.croissant.personalization.config.PersonalizationProperties;
import sweetgirl.croissant.personalization.dto.response.InteractionIngestResult;
import sweetgirl.croissant.personalization.enums.InteractionType;
import sweetgirl.croissant.personalization.exception.PersonalizationException;
import sweetgirl.croissant.personalization.repository.UserInteractionIngestJdbcRepository;

@ExtendWith(MockitoExtension.class)
class UserInteractionIngestCommandServiceTest {

	@Mock
	private UserInteractionIngestJdbcRepository ingestRepository;

	private UserInteractionIngestCommandService ingestCommandService;

	@BeforeEach
	void setUp() {
		ingestCommandService = new UserInteractionIngestCommandService(ingestRepository,
			new PersonalizationProperties());
	}

	@Test
	void skipsCompletedDayAfterAcquiringSharedIngestLock() {
		LocalDate day = LocalDate.of(2025, 12, 31);
		given(ingestRepository.tryLock(anyLong())).willReturn(true);
		given(ingestRepository.isDayComplete(day)).willReturn(true);
		assertThat(ingestCommandService.ingestMissingDay(day)).isFalse();
		var order = inOrder(ingestRepository);
		order.verify(ingestRepository).tryLock(UserInteractionIngestCommandService.INGEST_LOCK_KEY);
		order.verify(ingestRepository).isDayComplete(day);
		then(ingestRepository).should(never()).insertStoreViews(any(), any(), anyInt());
	}

	@Test
	void repairsOnlyMissingClosedDayThroughCommonIngest() {
		LocalDate day = LocalDate.of(2025, 12, 31);
		given(ingestRepository.tryLock(anyLong())).willReturn(true);
		assertThat(ingestCommandService.ingestMissingDay(day)).isTrue();
		then(ingestRepository).should().insertStoreViews(day.atStartOfDay(), day.plusDays(1).atStartOfDay(), 30);
		then(ingestRepository).should().recordCompletedDays(day.atStartOfDay(), day.plusDays(1).atStartOfDay());
	}

	@Test
	@DisplayName("범위 [from, to) 를 자정 기준으로 넘기고, 여덟 원천의 새 행 수를 종류별로 합쳐 돌려준다")
	void ingest_sumsEverySource() {
		LocalDate from = LocalDate.of(2026, 9, 17);
		LocalDate to = LocalDate.of(2026, 9, 18);
		LocalDateTime fromAt = from.atStartOfDay();
		LocalDateTime toAt = to.atStartOfDay();
		given(ingestRepository.tryLock(anyLong())).willReturn(true);
		given(ingestRepository.insertSaves(fromAt, toAt)).willReturn(5);
		given(ingestRepository.insertPlans(fromAt, toAt)).willReturn(1);
		given(ingestRepository.insertRecordStores(fromAt, toAt)).willReturn(2);
		given(ingestRepository.insertRecordBreads(fromAt, toAt)).willReturn(3);
		given(ingestRepository.insertStoreViews(fromAt, toAt, 30)).willReturn(40); // 기본 창 30분
		given(ingestRepository.insertCourseRemoves(fromAt, toAt)).willReturn(0);
		given(ingestRepository.insertRecoDismissed(fromAt, toAt)).willReturn(8);
		given(ingestRepository.insertRecoUnchecks(fromAt, toAt)).willReturn(1);

		InteractionIngestResult result = ingestCommandService.ingest(from, to);

		assertThat(result.from()).isEqualTo(from);
		assertThat(result.to()).isEqualTo(to);
		assertThat(result.inserted()).isEqualTo(60);
		assertThat(result.byType())
			.containsEntry(InteractionType.SAVE, 5)
			.containsEntry(InteractionType.STORE_VIEW, 40)
			.containsEntry(InteractionType.RECO_DISMISSED, 8)
			.doesNotContainKeys(InteractionType.COURSE_ADD, InteractionType.UNSAVE); // 둘은 이벤트 · 백필 몫
		then(ingestRepository).should().tryLock(UserInteractionIngestCommandService.INGEST_LOCK_KEY);
	}

	@Test
	void recordsCompletionAfterAllSourcesAndExcludesOpenDays() {
		given(ingestRepository.tryLock(anyLong())).willReturn(true);
		LocalDate today = LocalDate.now(UserInteractionIngestCommandService.ZONE_ID);
		ingestCommandService.ingest(today.minusDays(2), today.plusDays(2));
		var order = inOrder(ingestRepository);
		order.verify(ingestRepository).insertRecoUnchecks(today.minusDays(2).atStartOfDay(),
			today.plusDays(2).atStartOfDay());
		order.verify(ingestRepository).recordCompletedDays(today.minusDays(2).atStartOfDay(), today.atStartOfDay());
	}

	@Test
	void failedSourceDoesNotRecordCompletion() {
		given(ingestRepository.tryLock(anyLong())).willReturn(true);
		doThrow(new IllegalStateException("test failure")).when(ingestRepository).insertRecoUnchecks(any(), any());
		assertThatThrownBy(() -> ingestCommandService.ingest(LocalDate.of(2025, 12, 1), LocalDate.of(2026, 1, 1)))
			.isInstanceOf(IllegalStateException.class);
		then(ingestRepository).should(never()).recordCompletedDays(any(), any());
	}

	@Test
	void openDaysAloneCannotBeMarkedComplete() {
		given(ingestRepository.tryLock(anyLong())).willReturn(true);
		LocalDate today = LocalDate.now(UserInteractionIngestCommandService.ZONE_ID);
		ingestCommandService.ingest(today, today.plusDays(1));
		then(ingestRepository).should(never()).recordCompletedDays(any(), any());
	}

	@Test
	@DisplayName("어제분 = 어제 00:00 부터 오늘 00:00 전까지")
	void ingestYesterday_coversYesterdayOnly() {
		given(ingestRepository.tryLock(anyLong())).willReturn(true);
		LocalDate today = LocalDate.now(ZoneId.of(UserInteractionIngestCommandService.ZONE)); // 스케줄 cron 과 같은 시계

		InteractionIngestResult result = ingestCommandService.ingestYesterday();

		assertThat(result.from()).isEqualTo(today.minusDays(1));
		assertThat(result.to()).isEqualTo(today);
		then(ingestRepository).should().insertSaves(today.minusDays(1).atStartOfDay(), today.atStartOfDay());
	}

	@Test
	@DisplayName("범위가 거꾸로거나 비어 있으면 400 이고 아무것도 넣지 않는다")
	void ingest_rejectsEmptyRange() {
		LocalDate day = LocalDate.of(2026, 9, 17);

		assertThatThrownBy(() -> ingestCommandService.ingest(day, day))
			.isInstanceOfSatisfying(PersonalizationException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(PersonalizationErrorCode.INGEST_RANGE_INVALID));
		then(ingestRepository).should(never()).tryLock(anyLong());
		then(ingestRepository).should(never()).insertSaves(any(), any());
	}

	@Test
	@DisplayName("범위가 설정 상한(62일)을 넘으면 400 — 락을 쥔 채 몇 년치를 한 트랜잭션으로 돌지 않는다")
	void ingest_rejectsTooLongRange() {
		assertThatThrownBy(() -> ingestCommandService.ingest(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 1)))
			.isInstanceOfSatisfying(PersonalizationException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(PersonalizationErrorCode.INGEST_RANGE_TOO_LONG));
		then(ingestRepository).should(never()).tryLock(anyLong());
	}

	@Test
	@DisplayName("담기 백필은 이벤트 배포일(설정) 뒤로는 거부 — 담은 회원과 코스 주인 두 사람에게 귀속되지 않게")
	void backfillCourseAdds_rejectsUntilAfterEventDeploy() {
		assertThatThrownBy(() -> ingestCommandService.backfillCourseAddsByOwner(LocalDate.of(2026, 10, 1)))
			.isInstanceOfSatisfying(PersonalizationException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(PersonalizationErrorCode.BACKFILL_UNTIL_TOO_LATE));
		then(ingestRepository).should(never()).tryLock(anyLong());
	}

	@Test
	@DisplayName("다른 적재가 락을 쥐고 있으면 409 — 스케줄러와 수동 실행이 겹치지 않는다")
	void ingest_whenLocked_isConflict() {
		given(ingestRepository.tryLock(anyLong())).willReturn(false);

		assertThatThrownBy(() -> ingestCommandService.ingest(LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 18)))
			.isInstanceOfSatisfying(PersonalizationException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(PersonalizationErrorCode.INTERACTION_INGEST_ALREADY_RUNNING));
		then(ingestRepository).should(never()).insertSaves(any(), any());
		then(ingestRepository).should(never()).insertStoreViews(any(), any(), anyInt());
	}

	@Test
	@DisplayName("담기 백필은 until 자정 전 행을 코스 주인 기준으로 한 번 넣는다 — 같은 락을 쓴다")
	void backfillCourseAdds_usesLockAndUntil() {
		given(ingestRepository.tryLock(anyLong())).willReturn(true);
		LocalDate until = LocalDate.of(2026, 9, 19);
		given(ingestRepository.backfillCourseAddsByOwner(until.atStartOfDay())).willReturn(1_910);

		assertThat(ingestCommandService.backfillCourseAddsByOwner(until)).isEqualTo(1_910);
	}
}
