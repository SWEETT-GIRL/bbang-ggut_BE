package sweetgirl.croissant.alarm.service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.entity.PushBatchItem;
import sweetgirl.croissant.alarm.repository.NotificationRepository;
import sweetgirl.croissant.alarm.repository.PushBatchItemRepository;
import sweetgirl.croissant.alarm.repository.PushBatchItemRepository.DueGroup;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;

/**
 * 짧은 시간에 몰리는 푸시(팔로잉 새 빵로그 · 좋아요)를 모았다가 한 번에 울린다.
 *
 * <p>한 사람이 빵로그를 연달아 다섯 개 올리면 팔로워 폰이 다섯 번 울리는 대신, 창(기본 10분)이 지난 뒤
 * "새 빵로그 5개를 올렸어요" 한 번. 알림함에는 다섯 건이 그대로 있다. 묶는 단위는 (받는 사람, 종류, 보낸 사람)이라
 * 다른 사람의 빵로그, 같은 사람의 댓글은 따로 간다.
 */
@Service
@RequiredArgsConstructor
public class PushBatchService {

	private static final Logger log = LoggerFactory.getLogger(PushBatchService.class);

	private final PushBatchItemRepository pushBatchItemRepository;
	private final NotificationRepository notificationRepository;
	private final MemberRepository memberRepository;
	private final PushDispatcher pushDispatcher;

	@Value("${push.batch.window-minutes:10}")
	private long windowMinutes;

	/**
	 * 대기열에 넣는다. 부르는 쪽(푸시 리스너)이 readOnly 트랜잭션이라 새 트랜잭션에서 커밋한다.
	 * 같은 (사람, 알림)이 두 번 오면 한 줄만 — 알림 자체가 기록당 한 행이라 보통은 안 겹치지만 재시도를 막는다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void enqueue(Notification notification, Long memberId, LocalDateTime now) {
		if (pushBatchItemRepository.existsByMemberIdAndNotificationId(memberId, notification.getId())) {
			return;
		}
		pushBatchItemRepository.save(PushBatchItem.of(memberId, notification, now));
	}

	/**
	 * 창이 지난 묶음을 전부 내보낸다. 묶음 하나가 실패해도 그 줄은 지우고 다음으로 간다 — 알림함에는 남아 있고,
	 * 매분 같은 실패를 되풀이하는 것보다 "지금 울리는 것" 하나를 잃는 쪽이 낫다.
	 *
	 * @return 울리려고 시도한 묶음 수
	 */
	@Transactional
	public int flushDue(LocalDateTime now) {
		List<DueGroup> dueGroups = pushBatchItemRepository.findDueGroups(now.minusMinutes(windowMinutes));
		int flushed = 0;
		for (DueGroup group : dueGroups) {
			List<PushBatchItem> items = pushBatchItemRepository
				.findByMemberIdAndTypeAndActorIdOrderByCreatedAtAsc(group.getMemberId(), group.getType(),
					group.getActorId());
			if (items.isEmpty()) {
				continue;
			}
			try {
				if (flushGroup(group, items, now)) {
					flushed++;
				}
			} catch (RuntimeException exception) {
				log.error("푸시 묶음 발송 실패 — 지우고 넘어간다 [memberId={}, type={}, actorId={}, count={}]",
					group.getMemberId(), group.getType(), group.getActorId(), items.size(), exception);
			}
			pushBatchItemRepository.deleteAllInBatch(items);
		}
		return flushed;
	}

	private boolean flushGroup(DueGroup group, List<PushBatchItem> items, LocalDateTime now) {
		Member member = memberRepository.findByIdAndDeletedAtIsNull(group.getMemberId()).orElse(null);
		if (member == null) {
			log.info("푸시 묶음 skip — 받을 사람이 사라졌다 [memberId={}]", group.getMemberId());
			return false;
		}
		List<Notification> ordered = orderedNotificationsOf(items);
		if (ordered.isEmpty()) {
			log.info("푸시 묶음 skip — 알림이 사라졌다 [memberId={}, type={}]", group.getMemberId(), group.getType());
			return false;
		}
		PushDigest digest = PushDigest.of(group.getType(), ordered);
		pushDispatcher.dispatchDigest(ordered, member, digest, now);
		return true;
	}

	/** 줄이 쌓인 순서(오래된 것부터)로 알림을 늘어놓는다. 그 사이 지워진 알림은 뺀다. */
	private List<Notification> orderedNotificationsOf(List<PushBatchItem> items) {
		Map<Long, Notification> byId = notificationRepository
			.findAllById(items.stream().map(PushBatchItem::getNotificationId).toList()).stream()
			.collect(Collectors.toMap(Notification::getId, Function.identity()));
		return items.stream()
			.sorted(Comparator.comparing(PushBatchItem::getCreatedAt))
			.map(item -> byId.get(item.getNotificationId()))
			.filter(notification -> notification != null)
			.toList();
	}
}
