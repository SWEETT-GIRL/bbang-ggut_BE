package sweetgirl.croissant.course.dto.projection;

import java.time.LocalDateTime;

public interface CourseProgressAggregateProjection {

	Long getCourseId();

	long getStoreCount();

	long getVisitedStoreCount();

	LocalDateTime getLastTouchedAt();
}
