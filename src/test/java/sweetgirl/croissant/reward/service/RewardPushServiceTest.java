package sweetgirl.croissant.reward.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.enums.NotificationType;
import sweetgirl.croissant.alarm.event.PushRequestedEvent;
import sweetgirl.croissant.alarm.repository.NotificationRepository;
import sweetgirl.croissant.alarm.service.NotificationRecorder;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.reward.dto.response.RewardSummaryResponse.Letter;
import sweetgirl.croissant.reward.repository.RewardPushTargetRepository;
import sweetgirl.croissant.reward.repository.RewardPushTargetRepository.AttendanceReminderTarget;

@ExtendWith(MockitoExtension.class)
class RewardPushServiceTest {

	/** 월요일 오전 10 시. 그 주는 10/12 00:00 에 시작한다. */
	private static final LocalDateTime MONDAY_TEN = LocalDateTime.of(2026, 10, 12, 10, 0);
	private static final LocalDate TODAY = MONDAY_TEN.toLocalDate();

	@Mock
	private RewardPushTargetRepository targetRepository;
	@Mock
	private NotificationRepository notificationRepository;
	@Mock
	private NotificationRecorder notificationRecorder;
	@Mock
	private MemberRepository memberRepository;
	@Mock
	private BbangdaengLetterService bbangdaengLetterService;
	@Mock
	private ApplicationEventPublisher eventPublisher;
	@Mock
	private RewardSettings rewardSettings;

	@InjectMocks
	private RewardPushService rewardPushService;

	private Member member(Long id) {
		Member member = mock(Member.class);
		given(member.getId()).willReturn(id);
		return member;
	}

	private Notification givenRecorded(Long notificationId) {
		Notification recorded = mock(Notification.class);
		given(recorded.getId()).willReturn(notificationId);
		given(notificationRecorder.recordForMembers(any(Notification.class), anyList(), any())).willReturn(recorded);
		return recorded;
	}

	@Nested
	@DisplayName("편지 도착")
	class LetterArrivals {

		private void givenFirstRunOfTheWeek() {
			given(targetRepository.tryAdvisoryXactLock(RewardPushService.LETTER_LOCK_KEY)).willReturn(true);
			given(notificationRepository.existsByTypeAndCreatedAtGreaterThanEqual(
				NotificationType.REWARD_LETTER, LocalDateTime.of(2026, 10, 12, 0, 0))).willReturn(false);
		}

		@Test
		@DisplayName("최근 30 일 안에 들어온 회원 가운데 이번 주 편지를 안 연 회원에게만 알린다")
		void notifiesWaitingMembers() {
			givenFirstRunOfTheWeek();
			given(targetRepository.findMemberIdsActiveSince(MONDAY_TEN.minusDays(30))).willReturn(List.of(1L, 2L));
			given(bbangdaengLetterService.summaryOf(1L, TODAY)).willReturn(new Letter(1, 31L));
			given(bbangdaengLetterService.summaryOf(2L, TODAY)).willReturn(new Letter(0, null));
			Member waiting = member(1L);
			given(memberRepository.findAllById(List.of(1L))).willReturn(List.of(waiting));
			givenRecorded(500L);

			assertThat(rewardPushService.sendLetterArrivals(MONDAY_TEN)).isEqualTo(1);

			// 편지는 들어와야 만들어지므로, 알리기 전에 이번 주 편지를 먼저 배달한다
			then(bbangdaengLetterService).should().deliverIfDue(1L, TODAY);
			then(bbangdaengLetterService).should().deliverIfDue(2L, TODAY);
			then(eventPublisher).should().publishEvent(PushRequestedEvent.to(500L, 1L));
		}

		@Test
		@DisplayName("광고가 아닌 서비스 알림으로, 홈의 편지를 여는 링크와 함께 만든다")
		void notificationShape() {
			givenFirstRunOfTheWeek();
			given(targetRepository.findMemberIdsActiveSince(any())).willReturn(List.of(1L));
			given(bbangdaengLetterService.summaryOf(1L, TODAY)).willReturn(new Letter(1, 31L));
			Member waiting = member(1L);
			given(memberRepository.findAllById(List.of(1L))).willReturn(List.of(waiting));
			givenRecorded(500L);

			rewardPushService.sendLetterArrivals(MONDAY_TEN);

			ArgumentCaptor<Notification> notification = ArgumentCaptor.forClass(Notification.class);
			then(notificationRecorder).should().recordForMembers(notification.capture(), anyList(), eq(MONDAY_TEN));
			assertThat(notification.getValue().getType()).isEqualTo(NotificationType.REWARD_LETTER);
			assertThat(notification.getValue().getType().requiresMarketingConsent()).isFalse();
			assertThat(notification.getValue().getTitle()).isEqualTo("빵댕이가 편지를 보냈어요");
			assertThat(notification.getValue().getDeepLink()).isEqualTo("/bbanglog?open=letter");
		}

		@Test
		@DisplayName("이번 주에 이미 보냈으면 다시 보내지 않는다")
		void oncePerWeek() {
			given(targetRepository.tryAdvisoryXactLock(RewardPushService.LETTER_LOCK_KEY)).willReturn(true);
			given(notificationRepository.existsByTypeAndCreatedAtGreaterThanEqual(
				NotificationType.REWARD_LETTER, LocalDateTime.of(2026, 10, 12, 0, 0))).willReturn(true);

			assertThat(rewardPushService.sendLetterArrivals(MONDAY_TEN)).isEqualTo(-1);
			then(notificationRecorder).shouldHaveNoInteractions();
			then(eventPublisher).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("다른 인스턴스가 보내는 중이면 물러선다")
		void anotherInstanceRunning() {
			given(targetRepository.tryAdvisoryXactLock(RewardPushService.LETTER_LOCK_KEY)).willReturn(false);

			assertThat(rewardPushService.sendLetterArrivals(MONDAY_TEN)).isEqualTo(-1);
			then(targetRepository).should(never()).findMemberIdsActiveSince(any());
		}

		@Test
		@DisplayName("받을 사람이 없으면 알림도 만들지 않는다")
		void nobodyWaiting() {
			givenFirstRunOfTheWeek();
			given(targetRepository.findMemberIdsActiveSince(any())).willReturn(List.of(2L));
			given(bbangdaengLetterService.summaryOf(2L, TODAY)).willReturn(new Letter(0, null));

			assertThat(rewardPushService.sendLetterArrivals(MONDAY_TEN)).isZero();
			then(notificationRecorder).shouldHaveNoInteractions();
		}
	}

	@Nested
	@DisplayName("출석 리마인드")
	class AttendanceReminders {

		private static final LocalDateTime EVENING = LocalDateTime.of(2026, 10, 14, 20, 0);
		private static final LocalDate EVENING_DAY = EVENING.toLocalDate();

		private AttendanceReminderTarget target(Long memberId, int yesterdayBoardDay) {
			AttendanceReminderTarget target = mock(AttendanceReminderTarget.class);
			given(target.getMemberId()).willReturn(memberId);
			given(target.getBoardDay()).willReturn(yesterdayBoardDay);
			return target;
		}

		private void givenFirstRunOfTheDay() {
			given(targetRepository.tryAdvisoryXactLock(RewardPushService.ATTENDANCE_LOCK_KEY)).willReturn(true);
			given(notificationRepository.existsByTypeAndCreatedAtGreaterThanEqual(
				NotificationType.REWARD_ATTENDANCE, EVENING_DAY.atStartOfDay())).willReturn(false);
			given(rewardSettings.attendance()).willReturn(List.of(5, 5, 10, 5, 10, 10, 30));
		}

		@Test
		@DisplayName("어제 출석하고 오늘 안 한 회원에게, 오늘 찍을 칸과 보상을 알린다 — 칸이 같은 회원은 한 알림으로 묶는다")
		void remindsByBoardDay() {
			givenFirstRunOfTheDay();
			List<AttendanceReminderTarget> targets = List.of(target(1L, 2), target(2L, 2), target(3L, 7));
			given(targetRepository.findAttendanceReminderTargets(EVENING_DAY.minusDays(1), EVENING_DAY))
				.willReturn(targets);
			Member first = member(1L);
			Member second = member(2L);
			Member third = member(3L);
			given(memberRepository.findAllById(List.of(3L))).willReturn(List.of(third));
			given(memberRepository.findAllById(List.of(1L, 2L))).willReturn(List.of(first, second));
			givenRecorded(600L);

			assertThat(rewardPushService.sendAttendanceReminders(EVENING)).isEqualTo(3);

			ArgumentCaptor<Notification> notifications = ArgumentCaptor.forClass(Notification.class);
			then(notificationRecorder).should(org.mockito.Mockito.times(2))
				.recordForMembers(notifications.capture(), anyList(), eq(EVENING));
			// 어제 7 일째를 채운 회원은 오늘이 1 일째, 어제 2 일째였던 회원은 오늘이 3 일째다
			assertThat(notifications.getAllValues()).extracting(Notification::getBody)
				.containsExactly("1일째 +5", "3일째 +10");
			assertThat(notifications.getAllValues()).allSatisfy(notification -> {
				assertThat(notification.getType()).isEqualTo(NotificationType.REWARD_ATTENDANCE);
				assertThat(notification.getTitle()).isEqualTo("오늘 출석 도장이 아직이에요");
				assertThat(notification.getDeepLink()).isEqualTo("/bbanglog?open=attendance");
			});
		}

		@Test
		@DisplayName("발송이 밀려 밤 9 시를 넘겼으면 그날은 버린다")
		void skipsAtNight() {
			assertThat(rewardPushService.sendAttendanceReminders(EVENING.withHour(21))).isEqualTo(-1);
			assertThat(rewardPushService.sendAttendanceReminders(EVENING.withHour(7))).isEqualTo(-1);

			then(targetRepository).shouldHaveNoInteractions();
			then(notificationRecorder).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("오늘 이미 보냈으면 다시 보내지 않는다")
		void oncePerDay() {
			given(targetRepository.tryAdvisoryXactLock(RewardPushService.ATTENDANCE_LOCK_KEY)).willReturn(true);
			given(notificationRepository.existsByTypeAndCreatedAtGreaterThanEqual(
				NotificationType.REWARD_ATTENDANCE, EVENING_DAY.atStartOfDay())).willReturn(true);

			assertThat(rewardPushService.sendAttendanceReminders(EVENING)).isEqualTo(-1);
			then(notificationRecorder).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("부를 사람이 없으면 알림을 만들지 않는다")
		void nobodyToRemind() {
			givenFirstRunOfTheDay();
			given(targetRepository.findAttendanceReminderTargets(any(), any())).willReturn(List.of());

			assertThat(rewardPushService.sendAttendanceReminders(EVENING)).isZero();
			then(notificationRecorder).shouldHaveNoInteractions();
		}
	}
}
