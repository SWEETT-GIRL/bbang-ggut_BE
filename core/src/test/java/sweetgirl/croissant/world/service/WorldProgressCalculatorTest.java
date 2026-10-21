package sweetgirl.croissant.world.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.world.dto.response.WorldPlanetResponse;
import sweetgirl.croissant.world.dto.response.WorldStageResponse;
import sweetgirl.croissant.world.enums.PlanetGroup;
import sweetgirl.croissant.world.exception.WorldException;

class WorldProgressCalculatorTest {

	private final WorldProgressCalculator calculator = new WorldProgressCalculator();

	/** 1 차 출시 seed 그대로 — 도착점이 0 · 10 · 40 · 80 · 140 이 된다. */
	private static final List<WorldPlanetResponse> SOLAR = List.of(
		planet("earth", 1, 10, null, List.of(stage("village", 1, 0), stage("sky", 2, 3), stage("space", 3, 7))),
		planet("mars", 2, 30, "earth", List.of(stage("village", 1, 0), stage("sky", 2, 10), stage("space", 3, 20))),
		planet("jupiter", 3, 40, "mars", List.of(stage("village", 1, 0), stage("sky", 2, 13), stage("space", 3, 27))),
		planet("saturn", 4, 60, "jupiter", List.of(stage("village", 1, 0), stage("sky", 2, 20), stage("space", 3, 40))),
		planet("neptune", 5, 60, "saturn",
			List.of(stage("village", 1, 0), stage("sky", 2, 20), stage("space", 3, 40))));

	private static WorldPlanetResponse planet(
			String code, int sortOrder, int span, String unlockAfter, List<WorldStageResponse> stages) {
		return new WorldPlanetResponse(
			code, code, PlanetGroup.SOLAR, sortOrder, span, unlockAfter, false, 1, 2, true, null, stages);
	}

	private static WorldStageResponse stage(String code, int stageOrder, int offset) {
		return new WorldStageResponse(code, code, stageOrder, offset, null);
	}

	/** 리워드 2.0 — 지구의 하늘 3 → 1, 우주 7 → 5 만 앞당긴 값. 레벨이 바뀌는 기록 수는 아래 15 개다. */
	private static final List<WorldPlanetResponse> REWARD = List.of(
		planet("earth", 1, 10, null, List.of(stage("village", 1, 0), stage("sky", 2, 1), stage("space", 3, 5))),
		SOLAR.get(1), SOLAR.get(2), SOLAR.get(3), SOLAR.get(4));

	private static final int[] LEVEL_AT = {0, 1, 5, 10, 20, 30, 40, 53, 67, 80, 100, 120, 140, 160, 180};

	@Nested
	@DisplayName("레벨")
	class Level {

		@Test
		@DisplayName("레벨이 오르는 기록 수는 0 · 1 · 5 · 10 · 20 · 30 · 40 · 53 · 67 · 80 · 100 · 120 · 140 · 160 · 180 이다")
		void followsTheFifteenThresholds() {
			for (int index = 0; index < LEVEL_AT.length; index++) {
				int expectedLevel = index + 1;
				assertThat(calculator.level(REWARD, LEVEL_AT[index], List.of()).level())
					.as("기록 %d개", LEVEL_AT[index]).isEqualTo(expectedLevel);
				if (LEVEL_AT[index] > 0) {
					assertThat(calculator.level(REWARD, LEVEL_AT[index] - 1, List.of()).level())
						.as("기록 %d개", LEVEL_AT[index] - 1).isEqualTo(expectedLevel - 1);
				}
			}
		}

		@Test
		@DisplayName("기록 1 개면 Lv.2 지구 하늘, 5 개면 Lv.3, 10 개면 Lv.4 화성 마을이다")
		void earlyLevels() {
			assertThat(calculator.level(REWARD, 1, List.of())).isEqualTo(new WorldLevel(2, 1, 5, "earth", "sky"));
			assertThat(calculator.level(REWARD, 5, List.of())).isEqualTo(new WorldLevel(3, 5, 10, "earth", "space"));
			assertThat(calculator.level(REWARD, 10, List.of())).isEqualTo(new WorldLevel(4, 10, 20, "mars", "village"));
		}

		@Test
		@DisplayName("마지막 레벨이면 다음 레벨이 없다")
		void lastLevel() {
			assertThat(calculator.level(REWARD, 500, List.of()))
				.isEqualTo(new WorldLevel(15, 180, null, "neptune", "space"));
		}

		@Test
		@DisplayName("도착 이력이 기록 수보다 멀리 가 있으면 그 행성의 첫 단계 레벨이다 — 레벨은 내려가지 않는다")
		void arrivalHistoryWins() {
			assertThat(calculator.level(REWARD, 3, List.of("earth", "mars")))
				.isEqualTo(new WorldLevel(4, 10, 20, "mars", "village"));
		}

		@Test
		@DisplayName("단계를 안 둔 행성은 한 레벨로 센다")
		void planetWithoutStages() {
			List<WorldPlanetResponse> planets = List.of(
				planet("earth", 1, 10, null, List.of()),
				planet("mars", 2, 30, "earth", List.of(stage("village", 1, 0), stage("sky", 2, 10))));

			assertThat(calculator.level(planets, 0, List.of())).isEqualTo(new WorldLevel(1, 0, 10, "earth", null));
			assertThat(calculator.level(planets, 20, List.of())).isEqualTo(new WorldLevel(3, 20, null, "mars", "sky"));
		}
	}

	@Nested
	@DisplayName("행성 판정")
	class PlanetBoundary {

		@Test
		@DisplayName("기록이 0 이면 첫 행성 첫 단계이고, 다음 행성까지 구간 길이만큼 남는다")
		void startsAtTheFirstPlanet() {
			WorldProgress progress = calculator.calculate(SOLAR, 0, List.of());

			assertThat(progress.planet()).isEqualTo("earth");
			assertThat(progress.stage()).isEqualTo("village");
			assertThat(progress.nextPlanet()).isEqualTo("mars");
			assertThat(progress.toNext()).isEqualTo(10);
			assertThat(progress.ratio()).isEqualTo(0.0);
		}

		@Test
		@DisplayName("도착점에 정확히 닿으면 그 행성으로 넘어간다 — 하나 모자라면 아직 아니다")
		void movesExactlyAtTheArrivePoint() {
			assertThat(calculator.calculate(SOLAR, 9, List.of()).planet()).isEqualTo("earth");
			assertThat(calculator.calculate(SOLAR, 10, List.of()).planet()).isEqualTo("mars");
			assertThat(calculator.calculate(SOLAR, 39, List.of()).planet()).isEqualTo("mars");
			assertThat(calculator.calculate(SOLAR, 40, List.of()).planet()).isEqualTo("jupiter");
		}

		@Test
		@DisplayName("seed 가 만든 도착점은 10 · 40 · 80 · 140 이다")
		void followsTheConfirmedThresholds() {
			assertThat(calculator.calculate(SOLAR, 80, List.of()).planet()).isEqualTo("saturn");
			assertThat(calculator.calculate(SOLAR, 140, List.of()).planet()).isEqualTo("neptune");
		}

		@Test
		@DisplayName("마지막 행성에서는 다음 행성이 없고 남은 수는 0 이다")
		void stopsAtTheLastPlanet() {
			WorldProgress progress = calculator.calculate(SOLAR, 500, List.of());

			assertThat(progress.planet()).isEqualTo("neptune");
			assertThat(progress.nextPlanet()).isNull();
			assertThat(progress.toNext()).isZero();
			assertThat(progress.ratio()).isEqualTo(1.0);
		}
	}

	@Nested
	@DisplayName("고도 판정")
	class StageBoundary {

		@Test
		@DisplayName("고도는 행성 도착점 기준 상대 위치로 정해진다")
		void readsStageFromRelativeOffset() {
			assertThat(calculator.calculate(SOLAR, 2, List.of()).stage()).isEqualTo("village");
			assertThat(calculator.calculate(SOLAR, 3, List.of()).stage()).isEqualTo("sky");
			assertThat(calculator.calculate(SOLAR, 7, List.of()).stage()).isEqualTo("space");
			// 화성 도착(10) 직후는 다시 마을, 화성 기준 +10 이면 하늘
			assertThat(calculator.calculate(SOLAR, 10, List.of()).stage()).isEqualTo("village");
			assertThat(calculator.calculate(SOLAR, 20, List.of()).stage()).isEqualTo("sky");
		}

		@Test
		@DisplayName("단계 수가 셋이 아니어도 같은 규칙으로 읽는다")
		void doesNotAssumeThreeStages() {
			List<WorldPlanetResponse> twoStages = List.of(
				planet("earth", 1, 10, null, List.of(stage("ground", 1, 0), stage("orbit", 2, 5))));
			List<WorldPlanetResponse> fourStages = List.of(
				planet("earth", 1, 10, null,
					List.of(stage("a", 1, 0), stage("b", 2, 2), stage("c", 3, 5), stage("d", 4, 8))));

			assertThat(calculator.calculate(twoStages, 6, List.of()).stage()).isEqualTo("orbit");
			assertThat(calculator.calculate(fourStages, 6, List.of()).stage()).isEqualTo("c");
			assertThat(calculator.calculate(fourStages, 9, List.of()).stage()).isEqualTo("d");
		}

		@Test
		@DisplayName("단계를 안 둔 행성이면 고도는 비어 있다")
		void leavesStageEmptyWhenPlanetHasNone() {
			List<WorldPlanetResponse> noStages = List.of(planet("earth", 1, 10, null, List.of()));

			assertThat(calculator.calculate(noStages, 5, List.of()).stage()).isNull();
		}
	}

	@Nested
	@DisplayName("도착 이력이 계산값보다 세다")
	class ArrivalHistoryWins {

		@Test
		@DisplayName("임계값을 올려도 이미 도착한 회원은 뒤로 밀리지 않는다")
		void doesNotPushBackAfterThresholdIsRaised() {
			// 기록 12 개로 화성에 닿았던 회원. 운영 중 지구 구간을 10 → 30 으로 늘리면
			// 계산만으로는 지구로 되밀린다. 도착 이력이 그것을 막는다.
			List<WorldPlanetResponse> raised = List.of(
				planet("earth", 1, 30, null, List.of(stage("village", 1, 0))),
				planet("mars", 2, 30, "earth", List.of(stage("village", 1, 0), stage("sky", 2, 10))));

			assertThat(calculator.calculate(raised, 12, List.of()).planet()).isEqualTo("earth");
			assertThat(calculator.calculate(raised, 12, List.of("earth", "mars")).planet())
				.isEqualTo("mars");
		}

		@Test
		@DisplayName("도착만 하고 기록이 모자라면 그 행성의 첫 단계에 선다")
		void standsOnTheFirstStageWhenRecordsFallShort() {
			List<WorldPlanetResponse> raised = List.of(
				planet("earth", 1, 30, null, List.of(stage("village", 1, 0))),
				planet("mars", 2, 30, "earth", List.of(stage("village", 1, 0), stage("sky", 2, 10))));

			WorldProgress progress = calculator.calculate(raised, 12, List.of("mars"));

			assertThat(progress.stage()).isEqualTo("village");
			assertThat(progress.ratio()).isEqualTo(0.0);
		}

		@Test
		@DisplayName("카탈로그에 없는 행성 이력은 무시한다")
		void ignoresUnknownArrival() {
			assertThat(calculator.calculate(SOLAR, 0, List.of("pluto")).planet()).isEqualTo("earth");
		}
	}

	@Nested
	@DisplayName("카탈로그가 온전하지 않을 때")
	class BrokenCatalog {

		@Test
		@DisplayName("시작 행성이 없으면 예외다")
		void throwsWhenNoStartingPlanet() {
			List<WorldPlanetResponse> orphan = List.of(planet("mars", 1, 30, "earth", List.of()));

			assertThatThrownBy(() -> calculator.calculate(orphan, 0, List.of()))
				.isInstanceOf(WorldException.class);
		}

		@Test
		@DisplayName("사슬이 끊기면 거기서 멈춘다 — 뒤에 떨어져 있는 행성은 여정에 안 낀다")
		void stopsWhereTheChainBreaks() {
			// 어드민이 mars 를 비활성으로 돌리면 카탈로그에서 빠지고, jupiter 는 없는 행성을 가리키게 된다.
			List<WorldPlanetResponse> broken = List.of(
				planet("earth", 1, 10, null, List.of(stage("village", 1, 0))),
				planet("jupiter", 3, 40, "mars", List.of(stage("village", 1, 0))));

			WorldProgress progress = calculator.calculate(broken, 100, List.of());

			assertThat(progress.planet()).isEqualTo("earth");
			assertThat(progress.nextPlanet()).isNull();
		}
	}
}
