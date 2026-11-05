package sweetgirl.croissant.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.enums.NotificationTargetType;
import sweetgirl.croissant.alarm.enums.NotificationType;
import sweetgirl.croissant.member.entity.Member;

@DisplayName("PushDigest — 묶음 푸시 문구")
class PushDigestTest {

	private final Member actor = actor();

	@Test
	@DisplayName("1건이면 원래 알림의 제목 · 본문 그대로, 링크는 덮지 않는다")
	void single() {
		Notification only = record(1L, "밀도");

		PushDigest digest = PushDigest.of(NotificationType.FOLLOWING_NEW_RECORD, List.of(only));

		assertThat(digest.title()).isEqualTo("보낸 사람님이 새 빵로그를 올렸어요");
		assertThat(digest.body()).isEqualTo("밀도");
		assertThat(digest.link()).isNull();
		assertThat(digest.representative()).isSameAs(only);
	}

	@Test
	@DisplayName("새 빵로그 여러 건은 'N개를 올렸어요' · 가게는 최근 것부터 '외 N곳' · 링크는 보낸 사람 프로필")
	void followingRecords() {
		List<Notification> ordered = List.of(record(1L, "밀도"), record(2L, "어니언"), record(3L, "밀도"),
			record(4L, "포비"));

		PushDigest digest = PushDigest.of(NotificationType.FOLLOWING_NEW_RECORD, ordered);

		assertThat(digest.title()).isEqualTo("보낸 사람님이 새 빵로그 4개를 올렸어요");
		assertThat(digest.body()).isEqualTo("포비 외 2곳");
		assertThat(digest.link()).isEqualTo("/profile/7");
		assertThat(digest.representative().getId()).isEqualTo(4L);
	}

	@Test
	@DisplayName("같은 가게뿐이면 '외'를 붙이지 않는다")
	void samePlace() {
		PushDigest digest = PushDigest.of(NotificationType.RECORD_LIKE, List.of(like(1L, "밀도"), like(2L, "밀도")));

		assertThat(digest.title()).isEqualTo("보낸 사람님이 회원님의 빵로그 2개를 좋아해요");
		assertThat(digest.body()).isEqualTo("밀도");
		assertThat(digest.link()).isNull();
	}

	@Test
	@DisplayName("모아서 울리지 않는 종류를 2건 이상 넘기면 거부한다")
	void notBatched() {
		List<Notification> two = List.of(
			Notification.communityActivity(NotificationType.FOLLOW, actor, NotificationTargetType.MEMBER, 7L, null,
				"팔로우", null, "/profile/7"),
			Notification.communityActivity(NotificationType.FOLLOW, actor, NotificationTargetType.MEMBER, 7L, null,
				"팔로우", null, "/profile/7"));

		assertThatThrownBy(() -> PushDigest.of(NotificationType.FOLLOW, two))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private Notification record(Long id, String storeName) {
		Notification notification = Notification.communityActivity(NotificationType.FOLLOWING_NEW_RECORD, actor,
			NotificationTargetType.RECORD, id, storeName, "보낸 사람님이 새 빵로그를 올렸어요", storeName,
			"/bbanglog/post/" + id);
		ReflectionTestUtils.setField(notification, "id", id);
		return notification;
	}

	private Notification like(Long id, String storeName) {
		Notification notification = Notification.communityActivity(NotificationType.RECORD_LIKE, actor,
			NotificationTargetType.RECORD, id, storeName, "보낸 사람님이 회원님의 빵로그를 좋아해요", storeName,
			"/bbanglog/post/" + id);
		ReflectionTestUtils.setField(notification, "id", id);
		return notification;
	}

	private static Member actor() {
		Member member = Member.builder().nickname("보낸 사람").build();
		ReflectionTestUtils.setField(member, "id", 7L);
		return member;
	}
}
