package sweetgirl.croissant.recommendation.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.strtree.STRtree;
import org.springframework.stereotype.Component;

import sweetgirl.croissant.common.util.GeoDistance;
import sweetgirl.croissant.recommendation.repository.CandidateStoreRow;

/** 모든 출발점에서 점수순/근거리순 경로를 비교한다. 직선거리 제약 휴리스틱이며 최적해 보장은 없다. */
@Component
public class PolygonCoursePlanner {

	public record Origin(Long storeId, double latitude, double longitude) {
	}

	private record Edge(int index, int distance) {
	}

	private record Route(List<CandidateStoreRow> stores, int retained, double score, int distance) {
		boolean betterThan(Route other) {
			if (stores.size() != other.stores.size()) {
				return stores.size() > other.stores.size();
			}
			if (retained != other.retained) {
				return retained > other.retained;
			}
			if (Double.compare(score, other.score) != 0) {
				return score > other.score;
			}
			return distance < other.distance;
		}
	}

	public List<CandidateStoreRow> plan(List<CandidateStoreRow> ranked, int count, int hop, int budget,
			Origin origin, Set<Long> retain) {
		if (count < 1 || count > 6 || hop < 1 || budget < 0) {
			throw new IllegalArgumentException("유효한 가게 수·거리 상한이 필요합니다.");
		}
		if (ranked.stream().map(CandidateStoreRow::storeId).distinct().count() != ranked.size()) {
			throw new IllegalArgumentException("중복 후보는 경로에 사용할 수 없습니다.");
		}
		List<List<Edge>> neighbors = neighbors(ranked, hop);
		Route best = new Route(List.of(), 0, 0, 0);
		for (int seed = 0; seed < ranked.size(); seed++) {
			CandidateStoreRow first = ranked.get(seed);
			int approach = origin == null ? 0 : GeoDistance.metersBetween(origin.latitude(), origin.longitude(),
				first.latitude(), first.longitude());
			if (approach > hop || approach > budget) {
				continue;
			}
			for (boolean nearest : new boolean[] {false, true}) {
				Route route = extend(ranked, neighbors, seed, approach, count, budget, nearest, retain);
				if (route.betterThan(best)) {
					best = route;
				}
			}
		}
		return List.copyOf(best.stores());
	}

	private Route extend(List<CandidateStoreRow> rows, List<List<Edge>> neighbors, int seed, int approach,
			int count, int budget, boolean nearest, Set<Long> retain) {
		List<CandidateStoreRow> route = new ArrayList<>();
		Set<Integer> used = new HashSet<>();
		int current = seed;
		int traveled = approach;
		while (true) {
			route.add(rows.get(current));
			used.add(current);
			if (route.size() == count) {
				break;
			}
			Edge selected = null;
			for (Edge edge : neighbors.get(current)) {
				if (used.contains(edge.index()) || traveled + edge.distance() > budget) {
					continue;
				}
				if (selected == null || prefer(edge, selected, nearest, rows, retain)) {
					selected = edge;
				}
			}
			if (selected == null) {
				break;
			}
			traveled += selected.distance();
			current = selected.index();
		}
		return new Route(route, (int)route.stream().filter(row -> retain.contains(row.storeId())).count(),
			route.stream().mapToDouble(CandidateStoreRow::score).sum(), traveled);
	}

	private boolean prefer(Edge first, Edge second, boolean nearest, List<CandidateStoreRow> rows, Set<Long> retain) {
		boolean keepFirst = retain.contains(rows.get(first.index()).storeId());
		boolean keepSecond = retain.contains(rows.get(second.index()).storeId());
		if (keepFirst != keepSecond) {
			return keepFirst;
		}
		if (nearest && first.distance() != second.distance()) {
			return first.distance() < second.distance();
		}
		return first.index() < second.index();
	}

	private List<List<Edge>> neighbors(List<CandidateStoreRow> rows, int hop) {
		STRtree tree = new STRtree();
		for (int i = 0; i < rows.size(); i++) {
			CandidateStoreRow row = rows.get(i);
			tree.insert(new Envelope(row.longitude(), row.longitude(), row.latitude(), row.latitude()), i);
		}
		tree.build();
		return IntStream.range(0, rows.size()).mapToObj(index -> {
			CandidateStoreRow row = rows.get(index);
			double latitudeRange = (hop + 1.0) / 111000;
			double longitudeRange = latitudeRange / Math.cos(Math.toRadians(row.latitude()));
			List<Edge> edges = new ArrayList<>();
			for (Object item : tree.query(new Envelope(row.longitude() - longitudeRange,
				row.longitude() + longitudeRange, row.latitude() - latitudeRange, row.latitude() + latitudeRange))) {
				int other = (Integer)item;
				int distance = distance(row, rows.get(other));
				if (other != index && distance <= hop) {
					edges.add(new Edge(other, distance));
				}
			}
			edges.sort(Comparator.comparingInt(Edge::index));
			return edges;
		}).toList();
	}

	public static int distance(CandidateStoreRow first, CandidateStoreRow second) {
		return GeoDistance.metersBetween(first.latitude(), first.longitude(), second.latitude(), second.longitude());
	}
}
