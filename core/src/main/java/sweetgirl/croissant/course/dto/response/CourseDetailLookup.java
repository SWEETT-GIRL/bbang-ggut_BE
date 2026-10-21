package sweetgirl.croissant.course.dto.response;

import java.util.Optional;

/**
 * 조건부 GET 용 상세 조회 결과. 접근 판정을 통과한 코스의 현재 version 은 항상 있고,
 * 본문은 클라이언트가 보낸 ETag 가 낡았을 때만 만든다 — 304 면 비어 있다.
 */
public record CourseDetailLookup(
		long version,
		Optional<CourseDetailResponse> body) {

	public static CourseDetailLookup notModified(long version) {
		return new CourseDetailLookup(version, Optional.empty());
	}

	public static CourseDetailLookup of(CourseDetailResponse body) {
		return new CourseDetailLookup(body.version(), Optional.of(body));
	}
}
