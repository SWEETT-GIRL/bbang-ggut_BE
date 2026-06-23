package sweetgirl.croissant.course.dto.response;

import java.time.LocalTime;

import org.locationtech.jts.geom.Point;

import sweetgirl.croissant.course.entity.CourseStore;
import sweetgirl.croissant.store.entity.Store;

public record CourseStoreResponse(
		Long courseStoreId,
		int visitOrder,
		Long storeId,
		String storeName,
		double latitude,
		double longitude,
		String region,
		String memo,
		LocalTime visitTime,
		Integer walkingMinutesToNext) {

	public static CourseStoreResponse from(CourseStore courseStore) {
		Store store = courseStore.getStore();
		Point location = store.getLocation();
		return new CourseStoreResponse(
			courseStore.getId(),
			courseStore.getVisitOrder(),
			store.getId(),
			store.getName(),
			location.getY(),
			location.getX(),
			store.getRegion2nd(),
			courseStore.getMemo(),
			courseStore.getVisitTime(),
			courseStore.getWalkingMinutesToNext());
	}
}
