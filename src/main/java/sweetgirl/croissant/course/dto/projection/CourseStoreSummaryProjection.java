package sweetgirl.croissant.course.dto.projection;

/**
 * 목록 화면용 정류장 요약 — 코스별 가게 id·이름·지역만. Store 엔티티(geography 위치 등 15개 컬럼)를 싣지 않는다.
 * 방문 순서로 정렬돼 오므로 storeNames·storeIds·regions 가 순서를 그대로 쓴다.
 */
public interface CourseStoreSummaryProjection {

	Long getCourseId();

	Long getStoreId();

	String getStoreName();

	String getRegion1st();

	String getRegion2nd();
}
