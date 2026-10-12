package sweetgirl.croissant.world.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import sweetgirl.croissant.world.code.WorldErrorCode;
import sweetgirl.croissant.world.dto.response.WorldPlanetResponse;
import sweetgirl.croissant.world.dto.response.WorldStageResponse;
import sweetgirl.croissant.world.exception.WorldException;

/**
 * 기록 수와 도착 이력으로 지금 있는 행성 · 고도를 정한다. 의존하는 것이 없는 순수 계산이다.
 *
 * <p>두 가지를 하드코딩하지 않는다.
 * <ul>
 *   <li><b>임계값</b> — 행성 행의 {@code span}(구간 길이)을 {@code unlockAfter} 사슬을 따라
 *       누적해 도착점을 만든다. 절대값을 박아두면 외계 행성처럼 골라 가는 구간에서 고르는 순서가
 *       난이도를 바꾼다.</li>
 *   <li><b>단계 수</b> — 3 단계를 가정하지 않는다. 단계가 둘인 행성도 넷인 행성도 같은 코드로 읽는다.</li>
 * </ul>
 *
 * <p>현재 행성은 <b>계산값과 도착 이력 중 더 멀리 간 쪽</b>이다. 운영 중 임계값을 올려도 이미
 * 도착한 회원을 뒤로 밀지 않기 위해서다. 도착 이력이 곧 "간 적 있다"는 사실이라 계산값보다 세다.
 */
@Component
public class WorldProgressCalculator {

	public WorldProgress calculate(
			List<WorldPlanetResponse> planets,
			int maxRecordCount,
			Collection<String> arrivedPlanetCodes) {
		List<WorldPlanetResponse> journey = toJourney(planets);
		List<Integer> arriveAt = toArrivePoints(journey);

		int index = Math.max(
			indexByRecordCount(arriveAt, maxRecordCount),
			indexByArrivalHistory(journey, arrivedPlanetCodes));

		WorldPlanetResponse current = journey.get(index);
		int walked = Math.max(0, maxRecordCount - arriveAt.get(index));
		boolean isLast = index == journey.size() - 1;

		return new WorldProgress(
			current.code(),
			stageCodeAt(current, walked),
			isLast ? null : journey.get(index + 1).code(),
			isLast ? 0 : Math.max(0, arriveAt.get(index + 1) - maxRecordCount),
			ratio(walked, current.span()));
	}

	/**
	 * 레벨 — 지나온 행성들의 단계 수에 지금 행성에서 선 단계의 순번을 더한 것이다.
	 *
	 * <p>현재 행성은 {@link #calculate} 와 같은 규칙(계산값과 도착 이력 중 더 멀리 간 쪽)으로 정한다.
	 * 단계를 안 둔 행성은 한 레벨로 센다.
	 */
	public WorldLevel level(
			List<WorldPlanetResponse> planets,
			int maxRecordCount,
			Collection<String> arrivedPlanetCodes) {
		List<WorldPlanetResponse> journey = toJourney(planets);
		List<Integer> arriveAt = toArrivePoints(journey);
		int index = Math.max(
			indexByRecordCount(arriveAt, maxRecordCount),
			indexByArrivalHistory(journey, arrivedPlanetCodes));

		int level = 0;
		for (int passed = 0; passed < index; passed++) {
			level += Math.max(1, journey.get(passed).stages().size());
		}

		WorldPlanetResponse current = journey.get(index);
		List<WorldStageResponse> stages = current.stages().stream()
			.sorted(Comparator.comparingInt(WorldStageResponse::offset))
			.toList();
		int walked = Math.max(0, maxRecordCount - arriveAt.get(index));
		int stageIndex = 0;
		for (int candidate = 0; candidate < stages.size(); candidate++) {
			if (stages.get(candidate).offset() <= walked) {
				stageIndex = candidate;
			}
		}

		Integer levelUpAt = null;
		if (stageIndex + 1 < stages.size()) {
			levelUpAt = arriveAt.get(index) + stages.get(stageIndex + 1).offset();
		} else if (index + 1 < journey.size()) {
			levelUpAt = arriveAt.get(index + 1);
		}
		return new WorldLevel(
			level + stageIndex + 1,
			levelUpAt,
			current.code(),
			stages.isEmpty() ? null : stages.get(stageIndex).code());
	}

	/**
	 * 지금까지 닿은 행성들. 도착 이력을 남길 대상을 고르는 데 쓴다.
	 */
	public List<String> reachedPlanetCodes(List<WorldPlanetResponse> planets, int maxRecordCount) {
		List<WorldPlanetResponse> journey = toJourney(planets);
		List<Integer> arriveAt = toArrivePoints(journey);

		List<String> reached = new ArrayList<>();
		for (int index = 0; index < journey.size(); index++) {
			if (arriveAt.get(index) <= maxRecordCount) {
				reached.add(journey.get(index).code());
			}
		}
		return reached;
	}

	/**
	 * {@code unlockAfter} 사슬을 이어 여정 순서를 만든다. 사슬에 안 걸린 행성은 아직 갈 수 없는
	 * 곳이므로 빠진다.
	 *
	 * <p>한 행성 뒤에 갈래가 둘 이상 달리는 선택형 구간(외계)은 아직 다루지 않는다 — 회원이
	 * 무엇을 골랐는지가 있어야 경로가 정해지는데 그 화면이 없다. 지금은 먼저 만난 갈래로 잇는다.
	 */
	private List<WorldPlanetResponse> toJourney(List<WorldPlanetResponse> planets) {
		Map<String, WorldPlanetResponse> unlockedBy = planets.stream()
			.filter(planet -> planet.unlockAfter() != null)
			.collect(Collectors.toMap(
				WorldPlanetResponse::unlockAfter, Function.identity(), (first, second) -> first));

		WorldPlanetResponse start = planets.stream()
			.filter(planet -> planet.unlockAfter() == null)
			.findFirst()
			.orElseThrow(() -> new WorldException(WorldErrorCode.WORLD_CATALOG_NOT_INITIALIZED));

		// visited 는 사슬이 자기 자신으로 되돌아오는 자료가 들어와도 루프가 반드시 끝나게 하는 안전장치다.
		List<WorldPlanetResponse> journey = new ArrayList<>();
		Set<String> visited = new LinkedHashSet<>();
		WorldPlanetResponse cursor = start;
		while (cursor != null && visited.add(cursor.code())) {
			journey.add(cursor);
			cursor = unlockedBy.get(cursor.code());
		}
		return journey;
	}

	/** 각 행성의 도착점 = 앞선 행성들의 구간 길이를 모두 더한 값. */
	private List<Integer> toArrivePoints(List<WorldPlanetResponse> journey) {
		List<Integer> arriveAt = new ArrayList<>(journey.size());
		int accumulated = 0;
		for (WorldPlanetResponse planet : journey) {
			arriveAt.add(accumulated);
			accumulated += planet.span();
		}
		return arriveAt;
	}

	private int indexByRecordCount(List<Integer> arriveAt, int maxRecordCount) {
		int index = 0;
		for (int candidate = 0; candidate < arriveAt.size(); candidate++) {
			if (arriveAt.get(candidate) <= maxRecordCount) {
				index = candidate;
			}
		}
		return index;
	}

	private int indexByArrivalHistory(
			List<WorldPlanetResponse> journey,
			Collection<String> arrivedPlanetCodes) {
		Set<String> arrived = new HashSet<>(arrivedPlanetCodes);
		int index = 0;
		for (int candidate = 0; candidate < journey.size(); candidate++) {
			if (arrived.contains(journey.get(candidate).code())) {
				index = candidate;
			}
		}
		return index;
	}

	/**
	 * 지나온 만큼(walked)을 넘지 않는 단계 중 가장 높은 것. 단계를 안 둔 행성이면 비운다.
	 * 목록 순서에 기대지 않고 offset 으로 고른다 — 어드민이 단계를 끼워 넣어도 답이 같아야 한다.
	 */
	private String stageCodeAt(WorldPlanetResponse planet, int walked) {
		String stageCode = null;
		int reached = -1;
		for (WorldStageResponse stage : planet.stages()) {
			if (stage.offset() <= walked && stage.offset() > reached) {
				reached = stage.offset();
				stageCode = stage.code();
			}
		}
		return stageCode;
	}

	private double ratio(int walked, int span) {
		if (span <= 0) {
			return 1.0;
		}
		double raw = Math.min(1.0, (double)walked / span);
		return Math.round(raw * 1000) / 1000.0;
	}
}
