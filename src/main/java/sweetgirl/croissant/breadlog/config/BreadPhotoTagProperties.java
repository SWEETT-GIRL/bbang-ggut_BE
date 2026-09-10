package sweetgirl.croissant.breadlog.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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

	/** 프롬프트에 넣을 가게 메뉴 수 (좋아요순) */
	private int menuLimit = 60;

	/** 사진 한 장이 받을 수 있는 제안 수 — 프롬프트도 5개로 시키지만 모델이 어기는 걸 봤다 */
	private int maxSuggestions = 5;

	/**
	 * 사진 한 장당 기다릴 시간. 실측 90 백분위가 6.4초, 최대 16.3초였다 — 10초면 95%를 살린다.
	 * 상한이 없으면 매달린다(평가 스크립트가 실제로 몇 시간 멈췄다).
	 */
	private Duration timeout = Duration.ofSeconds(10);
}
