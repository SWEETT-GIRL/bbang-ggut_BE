package sweetgirl.croissant.course.dto.response;

import java.time.LocalTime;

import org.locationtech.jts.geom.Point;

import sweetgirl.croissant.course.entity.CourseStore;
import sweetgirl.croissant.course.entity.CourseStoreVisit;
import sweetgirl.croissant.store.RegionFormatter;
import sweetgirl.croissant.store.entity.Store;

public record CourseStoreResponse(
		Long courseStoreId,
		int visitOrder,
		int day,
		Long storeId,
		String storeName,
		double latitude,
		double longitude,
		String region,
		String memo,
		LocalTime visitTime,
		boolean visited,
		Long visitedRecordId) {

	/** 방문 정보가 필요 없는 자리(어드민, 가게 추가 직후)에서 쓴다. */
	public static CourseStoreResponse from(CourseStore courseStore) {
		return of(courseStore, null);
	}

	/**
	 * 방문 표시까지 담아 내려준다.
	 *
	 * 예전에는 앱이 "그 가게 기록이 있으면 방문"으로 스스로 판단했다.
	 * 이제는 어느 기록으로 표시했는지가 서버에 있으므로 서버가 알려준다.
	 */
	public static CourseStoreResponse of(CourseStore courseStore, CourseStoreVisit visit) {
		Store store = courseStore.getStore();
		Point location = store.getLocation();
		return new CourseStoreResponse(
			courseStore.getId(),
			courseStore.getVisitOrder(),
			courseStore.getDay(),
			store.getId(),
			store.getName(),
			location.getY(),
			location.getX(),
			RegionFormatter.full(store.getRegion1st(), store.getRegion2nd(), store.getRegion3rd()),
			courseStore.getMemo(),
			courseStore.getVisitTime(),
			visit != null,
			visit != null && visit.getBreadRecord() != null ? visit.getBreadRecord().getId() : null);
	}
}
