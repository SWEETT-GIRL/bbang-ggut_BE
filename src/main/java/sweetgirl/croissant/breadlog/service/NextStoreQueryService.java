package sweetgirl.croissant.breadlog.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.dto.response.NextStoreResponse;
import sweetgirl.croissant.common.util.GeoDistance;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.store.dto.projection.NextStoreCandidateProjection;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.repository.SavedStoreRepository;
import sweetgirl.croissant.store.repository.StoreRepository;

/**
 * 기록 완료 화면의 "다음 빵집" — 첫 기록 뒤 두 번째 기록으로 이어 주는 다음 행동이다.
 *
 * <p>담아 뒀는데 아직 기록이 없는 가게를 먼저(최근 담은 것부터), 모자라면 보낸 좌표 {@link #NEARBY_RADIUS_METERS} m 안에서
 * 공개 빵로그가 많은 가게로 채운다. 방금 기록한 가게는 뺀다. 게스트도 쓴다(담은 가게가 있을 수 있다).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NextStoreQueryService {

	static final int RESULT_SIZE = 3;
	static final double NEARBY_RADIUS_METERS = 2000;
	/** 뺄 가게가 없을 때 쿼리에 넘기는 값 — 어떤 id 와도 겹치지 않는다. */
	static final long NO_STORE = -1L;

	private final SavedStoreRepository savedStoreRepository;
	private final StoreRepository storeRepository;

	public List<NextStoreResponse> nextStores(Long memberId, Long excludeStoreId, Double latitude, Double longitude) {
		if (memberId == null) {
			throw new MemberException(MemberErrorCode.UNAUTHORIZED);
		}
		long excluded = excludeStoreId == null ? NO_STORE : excludeStoreId;
		List<NextStoreResponse> picked = new ArrayList<>();
		for (Store store : savedStoreRepository.findUnrecordedActiveStores(memberId, excluded,
			PageRequest.of(0, RESULT_SIZE))) {
			picked.add(NextStoreResponse.saved(store, distanceOf(store, latitude, longitude)));
		}
		if (picked.size() >= RESULT_SIZE || latitude == null || longitude == null) {
			return picked;
		}
		Set<Long> taken = picked.stream().map(NextStoreResponse::storeId).collect(Collectors.toSet());
		for (NextStoreCandidateProjection candidate : storeRepository.findNearbyUnrecordedPopular(
			memberId, excluded, longitude, latitude, NEARBY_RADIUS_METERS, RESULT_SIZE + taken.size())) {
			if (picked.size() >= RESULT_SIZE) {
				break;
			}
			if (!taken.contains(candidate.getId())) {
				picked.add(NextStoreResponse.nearby(candidate));
			}
		}
		return picked;
	}

	private static Integer distanceOf(Store store, Double latitude, Double longitude) {
		if (latitude == null || longitude == null || store.getLocation() == null) {
			return null;
		}
		return GeoDistance.metersBetween(latitude, longitude, store.getLocation().getY(), store.getLocation().getX());
	}
}
