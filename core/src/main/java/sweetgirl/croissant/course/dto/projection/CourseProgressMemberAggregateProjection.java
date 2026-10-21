package sweetgirl.croissant.course.dto.projection;

public interface CourseProgressMemberAggregateProjection {

	Long getMemberId();

	long getStoreCount();

	long getVisitedStoreCount();
}
