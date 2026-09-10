package sweetgirl.croissant.breadlog.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.config.BreadPhotoTagProperties;

/**
 * 멤버당 분석 요청 수를 센다. 앱 버그나 직접 호출로 Gemini 요금이 새는 걸 막는 장치이지,
 * 정상 사용을 다루는 장치가 아니다 — 기록 하나를 쓰는 동안 요청은 한 번이다.
 *
 * <p>프로세스 안에서만 센다. 서버가 blue-green 두 색 중 하나로만 뜨므로 프로세스 로컬로 족하고,
 * 배포로 카운터가 비는 건 이 목적에 문제가 되지 않는다.
 */
@Component
@RequiredArgsConstructor
public class BreadPhotoTagRateLimiter {

	/** 이만큼 쌓이면 만료된 칸을 걷어낸다 — 회원 수만큼 무한히 늘지 않게 */
	private static final int CLEANUP_THRESHOLD = 10_000;

	private final BreadPhotoTagProperties properties;
	private final Map<Long, Window> windowByMemberId = new ConcurrentHashMap<>();

	public boolean tryAcquire(Long memberId) {
		long now = System.currentTimeMillis();
		long windowMillis = properties.getRateLimitWindow().toMillis();
		if (windowByMemberId.size() > CLEANUP_THRESHOLD) {
			windowByMemberId.values().removeIf(window -> window.expired(now, windowMillis));
		}
		return windowByMemberId
			.computeIfAbsent(memberId, key -> new Window())
			.tryAcquire(now, windowMillis, properties.getRateLimitPerWindow());
	}

	/** 고정 구간으로 센다. 경계에서 잠깐 두 배까지 통과할 수 있지만 폭주를 막는 데는 충분하다 */
	private static final class Window {

		private long startedAtMillis;
		private int count;

		private synchronized boolean tryAcquire(long now, long windowMillis, int limit) {
			if (now - startedAtMillis >= windowMillis) {
				startedAtMillis = now;
				count = 0;
			}
			if (count >= limit) {
				return false;
			}
			count++;
			return true;
		}

		private synchronized boolean expired(long now, long windowMillis) {
			return now - startedAtMillis >= windowMillis;
		}
	}
}
