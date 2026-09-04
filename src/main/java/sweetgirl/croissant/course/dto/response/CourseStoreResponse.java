package sweetgirl.croissant.course.dto.response;

import java.time.LocalTime;
import java.util.List;

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
		/** 구버전 앱 호환 — 사용자 코스에선 myMemo 와 같고, 큐레이션 코스에선 course_stores.memo(코스 설명)다. */
		String memo,
		LocalTime visitTime,
		boolean visited,
		Long visitedRecordId,
		/** 내가 이 정류장에 쓴 메모. 없으면 null. */
		String myMemo,
		/** 참여 순서(course_members.joined_at)로 정렬. 내 메모도 들어 있다. 메모 없는 일행은 안 나온다. */
		List<CourseStoreMemoResponse> memos) {

	/** 큐레이션(어드민) 자리 — 메모는 course_stores.memo 한 칸 그대로, 사람별 메모는 없다. */
	public static CourseStoreResponse from(CourseStore courseStore) {
		return of(courseStore, null, courseStore.getMemo(), null, List.of());
	}

	/**
	 * 방문 표시와 사람별 메모까지 담아 내려준다. memo 에 무엇을 넣을지(내 메모 / 코스 설명)는 호출부가 정한다.
	 *
	 * 예전에는 앱이 "그 가게 기록이 있으면 방문"으로 스스로 판단했다.
	 * 이제는 어느 기록으로 표시했는지가 서버에 있으므로 서버가 알려준다.
	 */
	public static CourseStoreResponse of(
			CourseStore courseStore,
			CourseStoreVisit visit,
			String memo,
			String myMemo,
			List<CourseStoreMemoResponse> memos) {
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
			memo,
			courseStore.getVisitTime(),
			visit != null,
			visit != null && visit.getBreadRecord() != null ? visit.getBreadRecord().getId() : null,
			myMemo,
			memos);
	}
}
