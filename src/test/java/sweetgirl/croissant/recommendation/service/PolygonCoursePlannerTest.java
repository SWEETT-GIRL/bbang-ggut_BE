package sweetgirl.croissant.recommendation.service;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import sweetgirl.croissant.recommendation.repository.CandidateStoreRow;

class PolygonCoursePlannerTest {
	private final PolygonCoursePlanner planner = new PolygonCoursePlanner();

	@Test
	void isolatedTopScoreDoesNotHideCompleteCluster() {
		List<CandidateStoreRow> rows = List.of(row(1, 37.1, 10), row(2, 37, 3),
			row(3, 37.001, 2), row(4, 37.002, 1));
		assertThat(planner.plan(rows, 3, 500, 600, null, Set.of()))
			.extracting(CandidateStoreRow::storeId).containsExactly(2L, 3L, 4L);
	}

	@Test
	void enforcesBudgetAndHopIncludingApproachForSingleStore() {
		var origin = new PolygonCoursePlanner.Origin(9L, 37, 127);
		List<CandidateStoreRow> rows = List.of(row(1, 37.005, 2), row(2, 37.002, 1));
		assertThat(planner.plan(rows, 1, 1000, 300, origin, Set.of()))
			.extracting(CandidateStoreRow::storeId).containsExactly(2L);
		assertThat(planner.plan(rows, 1, 100, 1000, origin, Set.of())).isEmpty();
		assertThat(planner.plan(rows, 2, 1000, 400, origin, Set.of())).hasSize(1);
	}

	@Test
	void preservesVisibleStoresOnlyWhenRouteRemainsFeasible() {
		List<CandidateStoreRow> rows = List.of(row(1, 37, 10), row(2, 37.001, 9),
			row(3, 37.002, 1), row(4, 38, 20));
		assertThat(planner.plan(rows, 2, 500, 500, null, Set.of(3L, 4L)))
			.extracting(CandidateStoreRow::storeId).containsExactlyInAnyOrder(1L, 3L);
	}

	@Test
	void routesAreDeterministicUniqueAndRespectEveryDistanceLimit() {
		List<CandidateStoreRow> rows = IntStream.range(0, 80)
			.mapToObj(index -> row(index + 1, 37 + index * 0.0005, 80 - index)).toList();
		List<CandidateStoreRow> result = planner.plan(rows, 6, 200, 450, null, Set.of());
		assertThat(result).isEqualTo(planner.plan(rows, 6, 200, 450, null, Set.of()));
		assertThat(result).doesNotHaveDuplicates();
		int total = 0;
		for (int i = 1; i < result.size(); i++) {
			int distance = PolygonCoursePlanner.distance(result.get(i - 1), result.get(i));
			assertThat(distance).isLessThanOrEqualTo(200);
			total += distance;
		}
		assertThat(total).isLessThanOrEqualTo(450);
	}

	@Test
	void rejectsInvalidPolicyAndDuplicateCandidates() {
		assertThat(planner.plan(List.of(), 4, 100, 300, null, Set.of())).isEmpty();
		assertThatIllegalArgumentException().isThrownBy(() -> planner.plan(List.of(), 7, 100, 300, null, Set.of()));
		assertThatIllegalArgumentException().isThrownBy(() -> planner.plan(List.of(), 1, 0, 300, null, Set.of()));
		assertThatIllegalArgumentException().isThrownBy(() -> planner.plan(List.of(), 1, 100, -1, null, Set.of()));
		assertThatIllegalArgumentException().isThrownBy(() -> planner.plan(List.of(row(1, 37, 1), row(1, 37, 1)),
			1, 100, 300, null, Set.of()));
	}

	private CandidateStoreRow row(long id, double latitude, double score) {
		return new CandidateStoreRow(id, "가게" + id, "동", latitude, 127, null, 0, 0, score, 0, 0,
			null, null, null, null, Map.of(), List.of(), List.of());
	}
}
