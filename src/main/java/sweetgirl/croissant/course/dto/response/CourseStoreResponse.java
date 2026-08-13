package sweetgirl.croissant.course.dto.response;

import java.time.LocalTime;

import org.locationtech.jts.geom.Point;

import sweetgirl.croissant.course.entity.CourseStore;
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
		LocalTime visitTime) {

	public static CourseStoreResponse from(CourseStore courseStore) {
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
			courseStore.getVisitTime());
	}
}
