package sweetgirl.croissant.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.entity.PushBatchItem;
import sweetgirl.croissant.alarm.enums.NotificationTargetType;
import sweetgirl.croissant.alarm.enums.NotificationType;
import sweetgirl.croissant.alarm.repository.NotificationRepository;
import sweetgirl.croissant.alarm.repository.PushBatchItemRepository;
import sweetgirl.croissant.alarm.repository.PushBatchItemRepository.DueGroup;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("PushBatchService — 푸시 모아서 울리기")
class PushBatchServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 14, 30);

	@Mock
	private PushBatchItemRepository pushBatchItemRepository;
	@Mock
	private NotificationRepository notificationRepository;
	@Mock
	private MemberRepository memberRepository;
	@Mock
	private PushDispatcher pushDispatcher;

	@InjectMocks
	private PushBatchService pushBatchService;

	private Member receiver;
	private Member actor;

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(pushBatchService, "windowMinutes", 10L);
		receiver = member(2L, "받는 사람");
		actor = member(7L, "보낸 사람");
	}

	@Test
	@DisplayName("enqueue: 줄을 하나 쌓는다 — 받는 사람 · 알림 · 종류 · 보낸 사람")
	void enqueue() {
		Notification notification = record(100L, "밀도");

		pushBatchService.enqueue(notification, 2L, NOW);

		ArgumentCaptor<PushBatchItem> saved = ArgumentCaptor.forClass(PushBatchItem.class);
		then(pushBatchItemRepository).should().save(saved.capture());
		assertThat(saved.getValue().getMemberId()).isEqualTo(2L);
		assertThat(saved.getValue().getNotificationId()).isEqualTo(100L);
		assertThat(saved.getValue().getType()).isEqualTo(NotificationType.FOLLOWING_NEW_RECORD);
		assertThat(saved.getValue().getActorId()).isEqualTo(7L);
		assertThat(saved.getValue().getCreatedAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("enqueue: 같은 (사람, 알림)이 이미 있으면 다시 쌓지 않는다")
	void enqueueOnce() {
		given(pushBatchItemRepository.existsByMemberIdAndNotificationId(2L, 100L)).willReturn(true);

		pushBatchService.enqueue(record(100L, "밀도"), 2L, NOW);

		then(pushBatchItemRepository).should(never()).save(any());
	}

	@Test
	@DisplayName("flushDue: 창(10분)이 지난 묶음을 창 기준 시각으로 찾는다 — 없으면 0")
	void flushNothing() {
		given(pushBatchItemRepository.findDueGroups(NOW.minusMinutes(10))).willReturn(List.of());

		assertThat(pushBatchService.flushDue(NOW)).isZero();

		then(pushDispatcher).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("flushDue: 묶음의 알림을 쌓인 순서로 모아 접은 문구로 한 번 울리고 줄을 지운다")
	void flushGroup() {
		Notification first = record(100L, "밀도");
		Notification second = record(101L, "어니언");
		List<PushBatchItem> items = List.of(item(2L, first, NOW.minusMinutes(12)),
			item(2L, second, NOW.minusMinutes(9)));
		givenGroup(items);
		given(notificationRepository.findAllById(List.of(100L, 101L))).willReturn(List.of(second, first));
		given(memberRepository.findByIdAndDeletedAtIsNull(2L)).willReturn(Optional.of(receiver));

		assertThat(pushBatchService.flushDue(NOW)).isEqualTo(1);

		ArgumentCaptor<PushDigest> digest = ArgumentCaptor.forClass(PushDigest.class);
		then(pushDispatcher).should().dispatchDigest(eq(List.of(first, second)), eq(receiver), digest.capture(),
			eq(NOW));
		assertThat(digest.getValue().title()).isEqualTo("보낸 사람님이 새 빵로그 2개를 올렸어요");
		assertThat(digest.getValue().body()).isEqualTo("어니언 외 1곳");
		then(pushBatchItemRepository).should().deleteAllInBatch(items);
	}

	@Test
	@DisplayName("flushDue: 받을 사람이 사라졌으면 울리지 않고 줄만 지운다")
	void flushDeletedMember() {
		List<PushBatchItem> items = List.of(item(2L, record(100L, "밀도"), NOW.minusMinutes(12)));
		givenGroup(items);
		given(memberRepository.findByIdAndDeletedAtIsNull(2L)).willReturn(Optional.empty());

		assertThat(pushBatchService.flushDue(NOW)).isZero();

		then(pushDispatcher).shouldHaveNoInteractions();
		then(pushBatchItemRepository).should().deleteAllInBatch(items);
	}

	@Test
	@DisplayName("flushDue: 보내다 터져도 줄을 지우고 다음 묶음으로 간다 — 매분 같은 실패를 되풀이하지 않는다")
	void flushKeepsGoingOnFailure() {
		Notification only = record(100L, "밀도");
		List<PushBatchItem> items = List.of(item(2L, only, NOW.minusMinutes(12)));
		givenGroup(items);
		given(notificationRepository.findAllById(List.of(100L))).willReturn(List.of(only));
		given(memberRepository.findByIdAndDeletedAtIsNull(2L)).willReturn(Optional.of(receiver));
		given(pushDispatcher.dispatchDigest(anyList(), eq(receiver), any(), eq(NOW)))
			.willThrow(new IllegalStateException("fcm down"));

		assertThat(pushBatchService.flushDue(NOW)).isZero();

		then(pushBatchItemRepository).should().deleteAllInBatch(items);
	}

	private void givenGroup(List<PushBatchItem> items) {
		DueGroup group = new DueGroup() {
			@Override
			public Long getMemberId() {
				return 2L;
			}

			@Override
			public NotificationType getType() {
				return NotificationType.FOLLOWING_NEW_RECORD;
			}

			@Override
			public Long getActorId() {
				return 7L;
			}
		};
		given(pushBatchItemRepository.findDueGroups(NOW.minusMinutes(10))).willReturn(List.of(group));
		given(pushBatchItemRepository.findByMemberIdAndTypeAndActorIdOrderByCreatedAtAsc(2L,
			NotificationType.FOLLOWING_NEW_RECORD, 7L)).willReturn(items);
	}

	private PushBatchItem item(Long memberId, Notification notification, LocalDateTime createdAt) {
		return PushBatchItem.of(memberId, notification, createdAt);
	}

	private Notification record(Long id, String storeName) {
		Notification notification = Notification.communityActivity(NotificationType.FOLLOWING_NEW_RECORD, actor,
			NotificationTargetType.RECORD, id, storeName, "보낸 사람님이 새 빵로그를 올렸어요", storeName,
			"/bbanglog/post/" + id);
		ReflectionTestUtils.setField(notification, "id", id);
		return notification;
	}

	private static Member member(Long id, String nickname) {
		Member member = Member.builder().nickname(nickname).build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}
}
