package sweetgirl.croissant.alarm.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.enums.NotificationType;

/**
 * 모아 둔 알림 묶음을 푸시 한 통으로 줄인 문구. 1건이면 원래 알림 그대로, 2건 이상이면 "N개" 로 접는다.
 *
 * @param representative 푸시 데이터(notificationId · 대상 id)를 꺼낼 알림 — 가장 최근 것
 * @param link           푸시가 열 곳. null 이면 representative 의 링크
 */
public record PushDigest(String title, String body, String link, Notification representative) {

	static final String FOLLOWING_RECORDS_TITLE = "%s님이 새 빵로그 %d개를 올렸어요";
	static final String LIKES_TITLE = "%s님이 회원님의 빵로그 %d개를 좋아해요";
	static final String MORE_PLACES = "%s 외 %d곳";

	/** {@code ordered} 는 오래된 것부터. 전부 같은 (받는 사람, 종류, 보낸 사람) 이어야 한다. */
	public static PushDigest of(NotificationType type, List<Notification> ordered) {
		Notification latest = ordered.get(ordered.size() - 1);
		if (ordered.size() == 1) {
			return new PushDigest(latest.getTitle(), latest.getBody(), null, latest);
		}
		String actorName = latest.getActor().getNickname();
		int count = ordered.size();
		String body = placesOf(ordered);
		if (type == NotificationType.FOLLOWING_NEW_RECORD) {
			return new PushDigest(FOLLOWING_RECORDS_TITLE.formatted(actorName, count), body,
				"/profile/" + latest.getActor().getId(), latest);
		}
		if (type == NotificationType.RECORD_LIKE) {
			return new PushDigest(LIKES_TITLE.formatted(actorName, count), body, null, latest);
		}
		throw new IllegalArgumentException("모아서 울리지 않는 종류: " + type);
	}

	/** 가게 이름을 최근 것부터, 겹치면 하나로 — "밀도 외 2곳". 한 곳뿐이면 그 이름만. */
	private static String placesOf(List<Notification> ordered) {
		List<Notification> latestFirst = new ArrayList<>(ordered);
		Collections.reverse(latestFirst);
		List<String> places = latestFirst.stream()
			.map(Notification::getTargetTitle)
			.filter(title -> title != null && !title.isBlank())
			.distinct()
			.toList();
		if (places.isEmpty()) {
			return ordered.get(ordered.size() - 1).getBody();
		}
		if (places.size() == 1) {
			return places.get(0);
		}
		return MORE_PLACES.formatted(places.get(0), places.size() - 1);
	}
}
