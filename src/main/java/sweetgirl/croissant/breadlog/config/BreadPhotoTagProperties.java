package sweetgirl.croissant.breadlog.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import lombok.Getter;
import lombok.Setter;

/**
 * 빵로그 사진 태그 제안 설정.
 *
 * <p>menuLimit 60 은 임의의 값이 아니라 정확도를 잰 조건이다 — 이름 정확도 62.1%(메뉴 목록 없이는 7.7%),
 * 프롬프트 보강 뒤 88.2% 가 모두 좋아요순 60개에서 나온 숫자다. 운영 실측(2026-09-10)으로도
 * 이미 달린 태그의 98.9% 가 자기 가게 좋아요순 60위 안이고, 가게의 95%는 메뉴가 60개를 넘지 않는다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "breadlog.photo-tag")
public class BreadPhotoTagProperties {

	/**
	 * 꺼져 있으면 Gemini 를 부르지 않고 빈 결과를 200 으로 돌려준다 — 앱이 에러 처리를 따로 안 해도 되게.
	 *
	 * <p>기본이 꺼짐인 이유는 위험해서가 아니라 한도를 나눠 먹기 때문이다. 메뉴 이름 분류가 같은 키·같은
	 * 모델의 하루 한도를 쓰고 있어서, 켜 두면 둘이 서로를 굶기고 429 만 쌓인다. 그 상태에선 칩이 거의
	 * 안 떠서 효과 측정도 오염된다 — 켜는 날이 딱 정해져야 전후 비교가 된다.
	 */
	private boolean enabled = false;

	/** 프롬프트에 넣을 가게 메뉴 수 (좋아요순) */
	private int menuLimit = 60;

	/** 사진 한 장이 받을 수 있는 제안 수 — 프롬프트도 5개로 시키지만 모델이 어기는 걸 봤다 */
	private int maxSuggestions = 5;

	/**
	 * 사진 한 장당 기다릴 시간. 실측 90 백분위가 6.4초, 최대 16.3초였다 — 10초면 95%를 살린다.
	 * 상한이 없으면 매달린다(평가 스크립트가 실제로 몇 시간 멈췄다).
	 */
	private Duration timeout = Duration.ofSeconds(10);

	/** 사진 한 장 크기 상한. 앱이 긴 변 768px 로 줄여 보내므로 장당 200KB 안팎이다 */
	private DataSize maxPhotoSize = DataSize.ofMegabytes(5);

	/**
	 * 동시에 분석할 사진 수 — 전용 스레드풀 크기다.
	 *
	 * <p>공용 ForkJoinPool 을 쓰면 안 된다. 크기가 (코어 수 - 1) 이라 2코어 서버에서는 1이고,
	 * {@code orTimeout} 은 실행 시작이 아니라 예약 시점부터 재므로 뒤쪽 사진이 Gemini 를 불러보지도
	 * 못한 채 타임아웃으로 버려진다. 사진 상한의 두 배를 두어 동시 요청 둘까지는 바로 시작하게 한다.
	 */
	private int maxConcurrentPhotos = 60;

	/** 멤버당 분당 요청 수. 앱 버그·직접 호출로 돈이 새는 걸 막는다 — 정상 사용은 기록 하나에 한 번이다 */
	private int rateLimitPerWindow = 20;

	private Duration rateLimitWindow = Duration.ofMinutes(1);
}
