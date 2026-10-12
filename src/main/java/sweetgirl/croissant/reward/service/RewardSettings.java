package sweetgirl.croissant.reward.service;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.reward.entity.RewardSetting;
import sweetgirl.croissant.reward.enums.RewardSettingKey;
import sweetgirl.croissant.reward.repository.RewardSettingRepository;

/**
 * 보상 수치를 읽는 곳. 표를 통째로 읽어 잠깐 들고 있는다.
 *
 * <p>수치는 홈 진입마다(요약 조회) 필요한데 거의 바뀌지 않는다. 그렇다고 오래 들고 있을 수도 없다 —
 * 인스턴스가 둘 이상일 때 어드민이 다른 인스턴스에서 바꾼 값을 이쪽은 모른다. 그래서 짧은 시간만 들고 있고,
 * 이 인스턴스에서 바꿨을 때는 바로 비운다. 다른 인스턴스는 길어야 {@link #TIME_TO_LIVE} 뒤에 따라온다.
 */
@Component
@RequiredArgsConstructor
public class RewardSettings {

	static final Duration TIME_TO_LIVE = Duration.ofSeconds(30);

	private static final List<RewardSettingKey> ATTENDANCE_DAYS = List.of(
		RewardSettingKey.ATTENDANCE_DAY1, RewardSettingKey.ATTENDANCE_DAY2, RewardSettingKey.ATTENDANCE_DAY3,
		RewardSettingKey.ATTENDANCE_DAY4, RewardSettingKey.ATTENDANCE_DAY5, RewardSettingKey.ATTENDANCE_DAY6,
		RewardSettingKey.ATTENDANCE_DAY7);

	private static final List<RewardSettingKey> FEED_WEDGES = List.of(
		RewardSettingKey.FEED_WEDGE0, RewardSettingKey.FEED_WEDGE1, RewardSettingKey.FEED_WEDGE2,
		RewardSettingKey.FEED_WEDGE3, RewardSettingKey.FEED_WEDGE4, RewardSettingKey.FEED_WEDGE5);

	private final RewardSettingRepository rewardSettingRepository;

	private final AtomicReference<Snapshot> cached = new AtomicReference<>();

	public int get(RewardSettingKey key) {
		return snapshot().values().get(key);
	}

	/** 출석 7 일판의 칸별 보상(1 일째부터). */
	public List<Integer> attendance() {
		return ATTENDANCE_DAYS.stream().map(this::get).toList();
	}

	/** 밥 룰렛의 칸별 보상. 시계 방향이고 12 시가 0 번이다 — 앱의 룰렛 그림 순서와 같다. */
	public List<Integer> feedWedges() {
		return FEED_WEDGES.stream().map(this::get).toList();
	}

	/** 밥 룰렛이 뽑는 값과 그 가중치(값 → 가중치). 값이 작은 것부터다. */
	public Map<Integer, Integer> feedWeights() {
		Map<Integer, Integer> weights = new LinkedHashMap<>();
		weights.put(3, get(RewardSettingKey.FEED_WEIGHT_3));
		weights.put(5, get(RewardSettingKey.FEED_WEIGHT_5));
		weights.put(10, get(RewardSettingKey.FEED_WEIGHT_10));
		weights.put(50, get(RewardSettingKey.FEED_WEIGHT_50));
		return weights;
	}

	/** 지금 쓰이는 값 전부. 키 선언 순서다. */
	public Map<RewardSettingKey, Integer> all() {
		return snapshot().values();
	}

	/** 이 인스턴스에서 값을 바꾼 뒤 부른다. 다음 읽기가 표를 다시 읽는다. */
	public void evict() {
		cached.set(null);
	}

	private Snapshot snapshot() {
		Snapshot current = cached.get();
		Instant now = Instant.now();
		if (current != null && current.loadedAt().plus(TIME_TO_LIVE).isAfter(now)) {
			return current;
		}
		Snapshot loaded = new Snapshot(load(), now);
		cached.set(loaded);
		return loaded;
	}

	private Map<RewardSettingKey, Integer> load() {
		Map<RewardSettingKey, Integer> values = new EnumMap<>(RewardSettingKey.class);
		for (RewardSettingKey key : RewardSettingKey.values()) {
			values.put(key, key.getDefaultValue());
		}
		for (RewardSetting setting : rewardSettingRepository.findAll()) {
			RewardSettingKey.findByKey(setting.getSettingKey())
				.ifPresent(key -> values.put(key, setting.getIntValue()));
		}
		return values;
	}

	private record Snapshot(Map<RewardSettingKey, Integer> values, Instant loadedAt) {
	}
}
