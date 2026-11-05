package sweetgirl.croissant.alarm.entity;

import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.alarm.enums.NotificationChannel;
import sweetgirl.croissant.alarm.enums.NotificationTargetType;
import sweetgirl.croissant.alarm.enums.NotificationType;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.member.entity.Member;

/**
 * 알릴 내용 한 벌. <b>누구에게</b>는 여기 없다 — {@link NotificationRecipient}가 답한다.
 *
 * <p>둘을 가른 이유는 공지다. 예전에는 이 테이블 한 행이 곧 한 사람의 우편함 줄이라, 전체공지를
 * 보내면 같은 문구가 회원 수만큼 복사됐다. 문구를 고치려면 수만 행을 UPDATE 해야 했다. 이제
 * 공지는 무슨 일이 있어도 한 행이고, 사람 수만큼 늘어나는 것은 우편함 쪽이다.
 *
 * <p>대상을 외래키로 묶지 않고 (targetType, targetId, targetTitle)로 펼쳐 둔다. 알림마다 대상
 * 도메인이 다른데 전부 관계로 걸면 컬럼이 도메인 수만큼 늘어나고, 목록 한 번에 조인이 그만큼 붙는다.
 * 제목을 그때 값으로 박아두면 나중에 코스 이름이 바뀌어도 "저장할 당시 그 이름"이 남는다.
 *
 * <p>actor만 관계로 두는 이유는 반대다 — 닉네임은 바뀌면 최신으로 보여야 하고, 탈퇴 여부도
 * 조회 시점에 판단해야 한다.
 */
@Entity
@Table(name = "notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private NotificationType type;

	/**
	 * 이 알림이 어디까지 나가는가. {@code IN_APP}이면 알림함에만 쌓이고, {@code PUSH}면 알림함에
	 * 쌓은 뒤 FCM까지 나간다. 알림함 적재는 어느 쪽이든 하므로 "푸시만 보내기"라는 값은 없다.
	 *
	 * <p>{@code notification_delivery.channel}과 이름은 같지만 뜻이 다르다. 그쪽은 "이 줄은 그
	 * 채널로 나간 기록"이고, 이쪽은 "이 알림은 그 채널까지 나간다"는 의도다.
	 */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private NotificationChannel channel;

	/** 서버가 문구를 갖는 알림(공지·광고·제보 결과)용. 활동 알림은 앱이 조립하므로 비어 있다. */
	@Column(length = 200)
	private String title;

	@Column(columnDefinition = "TEXT")
	private String body;

	/** 탭했을 때 갈 앱 내부 경로(/course/12). 활동 알림은 target으로 앱이 조립한다. */
	@Column(name = "deep_link", length = 500)
	private String deepLink;

	/** 행동한 사람. 공지처럼 사람이 한 일이 아닌 알림에는 없다. */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "actor_id")
	private Member actor;

	@Enumerated(EnumType.STRING)
	@Column(name = "target_type", length = 20)
	private NotificationTargetType targetType;

	@Column(name = "target_id")
	private Long targetId;

	@Column(name = "target_title", length = 200)
	private String targetTitle;

	/**
	 * 타입마다만 필요한 부수 값. 제보 결과의 reportId·moderationActionId처럼 목록에 실려 나가고
	 * 푸시 data에도 그대로 들어가는 것들이다.
	 *
	 * <p>컬럼으로 펼치지 않는 이유는 타입이 늘 때마다 그 타입만 쓰는 컬럼이 붙어 대부분의 행에서
	 * NULL인 자리가 쌓이기 때문이다. 조건으로 걸어 찾을 일이 없어 한 덩어리로 둔다 — 여기 담긴
	 * 값으로 WHERE를 쓰고 싶어지면 그때 그 값은 컬럼이 될 자격이 있는 것이다.
	 */
	/** 이 접두어로 시작하는 payload 값은 어드민만 본다 — 푸시로 기기에 실리지 않는다. */
	public static final String ADMIN_PAYLOAD_PREFIX = "admin.";

	@Convert(converter = NotificationPayloadConverter.class)
	@Column(columnDefinition = "TEXT")
	private Map<String, String> payload;

	@Builder(access = AccessLevel.PRIVATE)
	private Notification(NotificationType type, NotificationChannel channel, String title,
			String body, String deepLink, Member actor, NotificationTargetType targetType,
			Long targetId, String targetTitle, Map<String, String> payload) {
		this.type = type;
		this.channel = channel;
		this.title = title;
		this.body = body;
		this.deepLink = deepLink;
		this.actor = actor;
		this.targetType = targetType;
		this.targetId = targetId;
		this.targetTitle = targetTitle;
		this.payload = payload;
	}

	/** 사람이 한 일 — 저장·좋아요·초대. 문구는 앱이 조립한다. */
	public static Notification activity(NotificationType type, Member actor,
			NotificationTargetType targetType, Long targetId, String targetTitle) {
		return Notification.builder()
			.type(type)
			.channel(NotificationChannel.PUSH)
			.actor(actor)
			.targetType(targetType)
			.targetId(targetId)
			.targetTitle(targetTitle)
			.build();
	}

	/**
	 * 사람 사이 활동(팔로우 · 좋아요 · 댓글 · 멘션 · 팔로잉 소식). 문구도 같이 둔다 — 스토어의 옛 앱(2.0.x)은 모르는
	 * 타입을 title · body 로 그리고, 새 앱은 actor · target 으로 자기 문장을 만든다.
	 */
	public static Notification communityActivity(NotificationType type, Member actor,
			NotificationTargetType targetType, Long targetId, String targetTitle,
			String title, String body, String deepLink) {
		return Notification.builder()
			.type(type)
			.channel(NotificationChannel.PUSH)
			.actor(actor)
			.targetType(targetType)
			.targetId(targetId)
			.targetTitle(targetTitle)
			.title(title)
			.body(body)
			.deepLink(deepLink)
			.build();
	}

	/**
	 * 누가 내 빵투어를 찜했다. 눌렀을 때 그 빵투어로 가도록 링크를 단다 — courseId 만 보고는
	 * 옮겨 가지 못하는 스토어의 옛 앱도 link 는 따라간다.
	 */
	public static Notification courseSaved(Member actor, Long courseId, String courseTitle) {
		return Notification.builder()
			.type(NotificationType.COURSE_SAVED)
			.channel(NotificationChannel.PUSH)
			.actor(actor)
			.targetType(NotificationTargetType.COURSE)
			.targetId(courseId)
			.targetTitle(courseTitle)
			.deepLink("/course/" + courseId)
			.build();
	}

	/**
	 * 서버가 문구를 들고 있는 알림 — 공지·광고. 앱 배포 없이 고칠 수 있다.
	 *
	 * <p>채널을 부르는 쪽이 정한다. "업데이트됐다" 같은 소식은 알림함에만 두고 싶을 때가 있어서,
	 * 울릴지 말지를 발송하는 사람이 고르게 둔다.
	 */
	public static Notification announcement(NotificationType type, NotificationChannel channel,
			String title, String body, String deepLink) {
		return Notification.builder()
			.type(type)
			.channel(channel)
			.title(title)
			.body(body)
			.deepLink(deepLink)
			.build();
	}

	/**
	 * 서버가 문구를 갖는 알림은 대상과 부수 값까지 함께 가리킬 수 있다 — 제보 결과의 그 가게,
	 * 그리고 이의제기 화면을 여는 데 필요한 id들 같은.
	 */
	public static Notification announcement(NotificationType type, String title, String body,
			String deepLink, NotificationTargetType targetType, Long targetId, String targetTitle,
			Map<String, String> payload) {
		return Notification.builder()
			.type(type)
			.channel(NotificationChannel.PUSH)
			.title(title)
			.body(body)
			.deepLink(deepLink)
			.targetType(targetType)
			.targetId(targetId)
			.targetTitle(targetTitle)
			.payload(payload)
			.build();
	}

	/**
	 * 정해 둔 사람들에게 보내는 편지 발송 — 문구는 푸시·알림함 줄, 부수 값은 어드민만 보는 것.
	 * 울릴지(PUSH) 알림함에만 둘지(IN_APP)는 보내는 사람이 고른다.
	 */
	public static Notification letterSend(NotificationChannel channel, String title, String body,
			String deepLink, Map<String, String> adminPayload) {
		return Notification.builder()
			.type(NotificationType.LETTER)
			.channel(channel)
			.title(title)
			.body(body)
			.deepLink(deepLink)
			.payload(adminPayload)
			.build();
	}

	/** payload에서 한 값을 꺼낸다. 없으면 null — 타입마다 있는 키가 다른 것이 정상이다. */
	public String payloadValue(String key) {
		return this.payload == null ? null : this.payload.get(key);
	}

	/** 같은 대상을 다시 알릴 때 그때의 이름으로 갱신한다. 이름이 비면 옛 값을 지키는 게 낫다. */
	public void refreshTargetTitle(String targetTitle) {
		if (targetTitle != null) {
			this.targetTitle = targetTitle;
		}
	}

	/** 문구를 서버가 들고 있는 알림인가. 앱이 조립할 재료가 없다는 뜻이기도 하다. */
	public boolean hasOwnMessage() {
		return this.title != null;
	}
}
