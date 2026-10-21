package sweetgirl.croissant.recommendation.service;

import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.recommendation.repository.StoreLabelStatsRepository;

/**
 * 라벨 코사인의 중심화 상수 — 아홉 값의 전체 가게 평균을 표에서 읽어 잠깐 들고 있는다.
 *
 * <p><b>왜 코드에 박지 않나.</b> 이 값은 {@code store_recommendation_feature} 의 평균이라 dataops 가 적재할 때마다
 * 바뀐다. 2026-09-21 에 범위를 0~1 로 맞추면서 {@code axis_filling 0.035 → filling_rich 0.517} 로 부호까지
 * 달라졌다 — 그때 옛 상수를 그대로 썼다면 중심이 반대쪽에 찍혀 추천이 조용히 뒤집혔을 것이다. 예외도 안 나고
 * 코스는 그럴듯하게 나오므로 틀린 걸 알 방법이 없다. 축 쪽({@code centerTaste} 등)은 설정에 박혀 있는데, 그건
 * 같은 이유로 언젠가 같은 사고가 난다.
 *
 * <p><b>왜 매번 안 읽나.</b> 37,000행 평균이라 추천 요청마다 돌릴 쿼리가 아니다. 적재는 하루 몇 번이고 평균은
 * 그보다 훨씬 덜 움직이므로 TTL 안에서는 캐시를 쓴다.
 *
 * <p>표가 비어 있으면 {@link #current()} 가 null 을 준다 — 라벨 매칭을 켤 수 없다는 뜻이고, 부르는 쪽은 축으로
 * 돈다. 0 으로 중심화하면 "모든 가게가 평균보다 달콤하다" 가 되어 달콤한 가게만 몰아준다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LabelCenterParams {

	/** 적재는 하루 몇 번이고 평균은 그보다 덜 움직인다. 첫 요청이 쿼리를 한 번 무는 대신 그 뒤로는 안 문다. */
	private static final Duration TTL = Duration.ofMinutes(30);

	private final StoreLabelStatsRepository statsRepository;

	private volatile double[] centers;
	private volatile Instant loadedAt = Instant.EPOCH;

	/** 캐시가 살아 있으면 그대로, 아니면 새로 읽는다. 표가 비어 있으면 null. */
	public double[] current() {
		if (!Instant.now().isBefore(loadedAt.plus(TTL))) {
			return reload();
		}
		return centers;
	}

	public synchronized double[] reload() {
		double[] loaded = statsRepository.findLabelMeans();
		if (loaded == null) {
			log.warn("[추천] 라벨 중심화 상수를 읽지 못했다 — 표가 비어 있다. 라벨 점수를 쓰지 않고 축으로 돈다");
		}
		centers = loaded;
		loadedAt = Instant.now();
		return loaded;
	}
}
