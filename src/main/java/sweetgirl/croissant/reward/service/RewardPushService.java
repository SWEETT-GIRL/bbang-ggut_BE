package sweetgirl.croissant.reward.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.enums.NotificationChannel;
import sweetgirl.croissant.alarm.enums.NotificationType;
import sweetgirl.croissant.alarm.event.PushRequestedEvent;
import sweetgirl.croissant.alarm.repository.NotificationRepository;
import sweetgirl.croissant.alarm.service.NotificationRecorder;
import sweetgirl.croissant.common.Particles;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.reward.enums.RewardSettingKey;
import sweetgirl.croissant.reward.repository.RewardPushTargetRepository;
import sweetgirl.croissant.reward.repository.RewardPushTargetRepository.AttendanceReminderTarget;
import sweetgirl.croissant.reward.repository.RewardPushTargetRepository.MemberFriend;
import sweetgirl.croissant.world.dto.response.WorldCharacterResponse;
import sweetgirl.croissant.world.service.WorldCatalogService;

/**
 * 리워드의 정기 푸시. 광고가 아니라 서비스 알림이라 마케팅 동의를 보지 않고, 알림 수신을 켠 회원에게만 간다
 * (수신 설정은 {@code PushDispatcher} 가 본다).
 *
 * <p>같은 발송이 두 번 나가지 않게 두 겹으로 막는다 — 인스턴스가 둘이어도 한쪽만 돌도록 잠그고,
 * 그 주(그날)에 이미 만든 알림이 있으면 건너뛴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RewardPushService {

	/** 이 기간 안에 앱을 쓴 회원에게만 편지 도착을 알린다. 오래 안 들어온 회원을 매주 깨우지 않는다. */
	static final int ACTIVE_DAYS = 30;
	static final long LETTER_LOCK_KEY = 7_260_101L;
	static final String LETTER_TITLE_SUFFIX = " 편지를 보냈어요";
	static final String LETTER_BODY = "열어 보면 부스러기도 있어요";
	/** 앱의 홈(app/(tabs)/bbanglog)에서 편지를 연다. */
	static final String LETTER_LINK = "/bbanglog?open=letter";
	static final long ATTENDANCE_LOCK_KEY = 7_260_102L;
	static final String ATTENDANCE_TITLE = "오늘 출석 도장이 아직이에요";
	static final String ATTENDANCE_LINK = "/bbanglog?open=attendance";
	static final String LAPSED_TITLE = "하루 쉬었죠? 오늘 오면 이어져요";
	static final String LAPSED_BODY_SUFFIX = " 그대로";
	static final long RECORD_NUDGE_LOCK_KEY = 7_260_104L;
	static final String RECORD_NUDGE_TITLE = "두 번째 빵로그 어때요?";
	static final String RECORD_NUDGE_BODY_PREFIX = "지금 남기면 +";
	static final String RECORD_NUDGE_BODY_SUFFIX = " 부스러기";
	/** 첫 기록 뒤 이 날짜 범위(일)에 있는 회원을 부른다 — 2 일 전과 3 일 전. */
	static final int RECORD_NUDGE_FROM_DAYS = 3;
	static final int RECORD_NUDGE_TO_DAYS = 1;
	/** 앱의 기록 쓰기 화면. */
	static final String RECORD_NUDGE_LINK = "/bbanglog/record";
	/** 밤 9 시 ~ 아침 8 시에는 출석을 알리지 않는다. */
	static final int QUIET_FROM_HOUR = 21;
	static final int QUIET_UNTIL_HOUR = 8;
	static final long ROULETTE_LOCK_KEY = 7_260_103L;
	static final String ROULETTE_TITLE_SUFFIX = " 아침부터 배고파요";
	static final String ROULETTE_BODY = "룰렛 돌리러 와요";
	/** 홈의 밥 룰렛. open=feed 를 모르는 앱은 홈만 연다. */
	static final String ROULETTE_LINK = "/bbanglog?open=feed";
	/** 세계관을 아직 시작하지 않아 고른 친구가 없을 때의 이름. 회원이 지어 준 이름이 있으면 그 이름으로 부른다. */
	static final String DEFAULT_FRIEND_NAME = "빵댕이";

	private final RewardPushTargetRepository targetRepository;
	private final NotificationRepository notificationRepository;
	private final NotificationRecorder notificationRecorder;
	private final MemberRepository memberRepository;
	private final BbangdaengLetterService bbangdaengLetterService;
	private final ApplicationEventPublisher eventPublisher;
	private final RewardSettings rewardSettings;
	private final WorldCatalogService worldCatalogService;

	/**
	 * 이번 주 편지를 아직 안 연 회원에게 도착을 알린다. 월요일 오전에 부른다.
	 *
	 * <p>편지는 회원이 들어올 때 만들어지므로, 알리기 직전에 이번 주 편지를 먼저 배달한다.
	 * 편지는 회원이 고른 친구가 보낸다 — 제목에 그 친구 이름이 들어가("콩이가 편지를 보냈어요") 이름별로 한 알림씩 만든다.
	 *
	 * @return 알린 회원 수. 이번 주에 이미 보냈거나 다른 인스턴스가 보내는 중이면 -1
	 */
	@Transactional
	public int sendLetterArrivals(LocalDateTime now) {
		LocalDate today = now.toLocalDate();
		LocalDateTime weekStart = BbangdaengLetterService.weekStartOf(today).atStartOfDay();
		if (!targetRepository.tryAdvisoryXactLock(LETTER_LOCK_KEY)
			|| notificationRepository.existsByTypeAndCreatedAtGreaterThanEqual(
				NotificationType.REWARD_LETTER, weekStart)) {
			return -1;
		}

		List<Long> waitingMemberIds = new ArrayList<>();
		for (Long memberId : targetRepository.findMemberIdsActiveSince(now.minusDays(ACTIVE_DAYS))) {
			bbangdaengLetterService.deliverIfDue(memberId, today);
			if (bbangdaengLetterService.summaryOf(memberId, today).waitingCount() > 0) {
				waitingMemberIds.add(memberId);
			}
		}
		if (waitingMemberIds.isEmpty()) {
			return 0;
		}

		Map<String, String> catalogNames = catalogNameByCode();
		Map<Long, String> friendByMember = targetRepository.findFriendsOf(waitingMemberIds).stream()
			.collect(Collectors.toMap(MemberFriend::getMemberId, friend -> friendNameOf(friend, catalogNames)));
		Map<String, List<Long>> memberIdsByFriend = new TreeMap<>();
		for (Long memberId : waitingMemberIds) {
			memberIdsByFriend.computeIfAbsent(friendByMember.getOrDefault(memberId, DEFAULT_FRIEND_NAME),
				name -> new ArrayList<>()).add(memberId);
		}
		int notified = 0;
		for (Map.Entry<String, List<Long>> group : memberIdsByFriend.entrySet()) {
			String friend = group.getKey();
			notified += push(Notification.announcement(
				NotificationType.REWARD_LETTER, NotificationChannel.PUSH,
				friend + Particles.subject(friend) + LETTER_TITLE_SUFFIX, LETTER_BODY, LETTER_LINK),
				group.getValue(), now);
		}
		return notified;
	}

	/**
	 * 어제 출석했고 오늘 아직 안 한 회원에게 도장을 알린다. 매일 저녁 8 시에 부른다.
	 *
	 * <p>밤 9 시부터는 보내지 않는다 — 발송이 밀려 그 시각을 넘겼으면 그날은 버린다(다음 날 아침에 보내지도 않는다.
	 * 그때는 이미 끊겼다). 어제 출석하지 않은 회원은 부르지 않는다. 문구가 칸마다 달라("3일째 +10") 칸별로 한 알림씩 만든다.
	 *
	 * @return 알린 회원 수. 오늘 이미 보냈거나, 다른 인스턴스가 보내는 중이거나, 보낼 시간이 지났으면 -1
	 */
	@Transactional
	public int sendAttendanceReminders(LocalDateTime now) {
		LocalDate today = now.toLocalDate();
		if (now.getHour() >= QUIET_FROM_HOUR || now.getHour() < QUIET_UNTIL_HOUR
			|| !targetRepository.tryAdvisoryXactLock(ATTENDANCE_LOCK_KEY)
			|| notificationRepository.existsByTypeAndCreatedAtGreaterThanEqual(
				NotificationType.REWARD_ATTENDANCE, today.atStartOfDay())) {
			return -1;
		}

		Map<Integer, List<Long>> memberIdsByBoardDay = new TreeMap<>();
		for (AttendanceReminderTarget target : targetRepository.findAttendanceReminderTargets(
			today.minusDays(1), today)) {
			memberIdsByBoardDay
				.computeIfAbsent(AttendanceBoard.following(target.getBoardDay()), boardDay -> new ArrayList<>())
				.add(target.getMemberId());
		}
		List<Integer> rewards = rewardSettings.attendance();
		int notified = 0;
		for (Map.Entry<Integer, List<Long>> group : memberIdsByBoardDay.entrySet()) {
			int boardDay = group.getKey();
			String body = boardDay + "일째 +" + AttendanceBoard.rewardOf(boardDay, rewards);
			notified += push(Notification.announcement(
				NotificationType.REWARD_ATTENDANCE, NotificationChannel.PUSH, ATTENDANCE_TITLE, body,
				ATTENDANCE_LINK), group.getValue(), now);
		}
		// 하루 쉰 회원 — 유예 안이라 오늘 오면 그제 칸에 이어진다. 칸별로 한 알림씩
		Map<Integer, List<Long>> lapsedByBoardDay = new TreeMap<>();
		for (AttendanceReminderTarget target : targetRepository.findLapsedAttendanceTargets(
			today.minusDays(2), today.minusDays(1), today)) {
			lapsedByBoardDay
				.computeIfAbsent(AttendanceBoard.following(target.getBoardDay()), boardDay -> new ArrayList<>())
				.add(target.getMemberId());
		}
		for (Map.Entry<Integer, List<Long>> group : lapsedByBoardDay.entrySet()) {
			int boardDay = group.getKey();
			String body = boardDay + "일째 +" + AttendanceBoard.rewardOf(boardDay, rewards) + LAPSED_BODY_SUFFIX;
			notified += push(Notification.announcement(
				NotificationType.REWARD_ATTENDANCE, NotificationChannel.PUSH, LAPSED_TITLE, body,
				ATTENDANCE_LINK), group.getValue(), now);
		}
		return notified;
	}

	/**
	 * 2 · 3 일 전에 첫 기록을 남기고 두 번째가 없는 회원에게 저녁에 "두 번째 빵로그" 를 권한다.
	 * 첫 주 기록 1 건은 0 건과 리텐션이 같고 2 건부터 남는다(11/3) — 두 번째 기록이 첫 주 보너스 안에 있을 때 부른다.
	 *
	 * @return 알린 회원 수. 오늘 이미 보냈거나 다른 인스턴스가 보내는 중이면 -1
	 */
	@Transactional
	public int sendSecondRecordNudges(LocalDateTime now) {
		LocalDate today = now.toLocalDate();
		if (!targetRepository.tryAdvisoryXactLock(RECORD_NUDGE_LOCK_KEY)
			|| notificationRepository.existsByTypeAndCreatedAtGreaterThanEqual(
				NotificationType.RECORD_NUDGE, today.atStartOfDay())) {
			return -1;
		}
		List<Long> memberIds = targetRepository.findSecondRecordNudgeTargets(
			today.minusDays(RECORD_NUDGE_FROM_DAYS).atStartOfDay(),
			today.minusDays(RECORD_NUDGE_TO_DAYS).atStartOfDay());
		String body = RECORD_NUDGE_BODY_PREFIX + rewardSettings.get(RewardSettingKey.RECORD_SECOND_WEEK)
			+ RECORD_NUDGE_BODY_SUFFIX;
		return push(Notification.announcement(
			NotificationType.RECORD_NUDGE, NotificationChannel.PUSH, RECORD_NUDGE_TITLE, body, RECORD_NUDGE_LINK),
			memberIds, now);
	}

	/**
	 * 어제 가입했는데 아직 출석 도장을 한 번도 안 찍은 회원을 아침에 부른다. 매일 아침 9 시에 부른다.
	 *
	 * <p>첫날 출석을 거른 회원은 저녁 리마인드(어제 출석한 회원만)에 걸리지 않아 둘째 날 아무 푸시도 못 받았다.
	 * 문구에 그 회원의 친구 이름(지어 준 이름이 있으면 그 이름)이 들어가("빵댕이가 아침부터 배고파요") 이름별로 한 알림씩 만든다.
	 *
	 * @return 알린 회원 수. 오늘 이미 보냈거나 다른 인스턴스가 보내는 중이면 -1
	 */
	@Transactional
	public int sendSecondMorningRoulette(LocalDateTime now) {
		LocalDate today = now.toLocalDate();
		if (!targetRepository.tryAdvisoryXactLock(ROULETTE_LOCK_KEY)
			|| notificationRepository.existsByTypeAndCreatedAtGreaterThanEqual(
				NotificationType.REWARD_ROULETTE, today.atStartOfDay())) {
			return -1;
		}

		Map<String, String> catalogNames = catalogNameByCode();
		Map<String, List<Long>> memberIdsByFriend = new TreeMap<>();
		for (MemberFriend target : targetRepository.findSecondMorningTargets(
			today.minusDays(1).atStartOfDay(), today.atStartOfDay())) {
			String friend = friendNameOf(target, catalogNames);
			memberIdsByFriend.computeIfAbsent(friend, name -> new ArrayList<>()).add(target.getMemberId());
		}
		int notified = 0;
		for (Map.Entry<String, List<Long>> group : memberIdsByFriend.entrySet()) {
			String friend = group.getKey();
			notified += push(Notification.announcement(
				NotificationType.REWARD_ROULETTE, NotificationChannel.PUSH,
				friend + Particles.subject(friend) + ROULETTE_TITLE_SUFFIX, ROULETTE_BODY, ROULETTE_LINK),
				group.getValue(), now);
		}
		return notified;
	}

	private Map<String, String> catalogNameByCode() {
		return worldCatalogService.getCatalog().characters().stream()
			.collect(Collectors.toMap(WorldCharacterResponse::code, WorldCharacterResponse::name));
	}

	/** 카탈로그 이름. 고른 친구가 없으면 빵댕이. (지어 준 이름은 2026-10-21 에 없앴다 — 사람 이름은 회원 닉네임 하나다) */
	private static String friendNameOf(MemberFriend friend, Map<String, String> catalogNames) {
		if (friend.getSelectedCharacter() == null) {
			return DEFAULT_FRIEND_NAME;
		}
		return catalogNames.getOrDefault(friend.getSelectedCharacter(), DEFAULT_FRIEND_NAME);
	}

	/** 알림함에 앉히고, 커밋된 뒤 한 사람씩 푸시로 내보내게 한다. 받을 사람이 없으면 알림도 만들지 않는다. */
	private int push(Notification notification, List<Long> memberIds, LocalDateTime now) {
		if (memberIds.isEmpty()) {
			return 0;
		}
		List<Member> members = memberRepository.findAllById(memberIds);
		Notification recorded = notificationRecorder.recordForMembers(notification, members, now);
		members.forEach(member -> eventPublisher.publishEvent(
			PushRequestedEvent.to(recorded.getId(), member.getId())));
		log.info("[리워드 푸시] type={} recipients={}", notification.getType(), members.size());
		return members.size();
	}
}
