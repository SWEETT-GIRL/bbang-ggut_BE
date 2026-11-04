package sweetgirl.croissant.course.draft;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.dto.response.MemoDraftResponse;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseStore;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.CourseStoreRepository;
import sweetgirl.croissant.course.service.CourseAccessPolicy;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.MemberRestrictionReader;

/**
 * 아직 DB 에 안 간 초안 조회 — 화면 첫 진입·소켓 재연결 때 한 번. 편집 권한이 있는 사람(방장·활성 멤버)만 본다.
 * 플러셔와 같은 기준으로 거른다: 코스에 있는 가게, 지금 쓸 수 있는 회원의 초안만. 그래야 화면과 DB 가 결국 같아진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@ConditionalOnExpression(CourseDraftConfig.REDIS_CONFIGURED)
public class MemoDraftQueryService {

	private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

	private final CourseRepository courseRepository;
	private final CourseStoreRepository courseStoreRepository;
	private final CourseAccessPolicy courseAccessPolicy;
	private final MemberReader memberReader;
	private final MemberRestrictionReader memberRestrictionReader;
	private final MemoDraftBuffer buffer;

	public List<MemoDraftResponse> getDrafts(Long memberId, Long courseId) {
		memberReader.requireLoggedIn(memberId);
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		courseAccessPolicy.requireEditable(course, memberId);

		List<DraftEntry> entries = readEntries(courseId);
		if (entries.isEmpty()) {
			return List.of();
		}
		Set<Long> activeStoreIds = courseStoreRepository
			.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId)
			.stream().map(stop -> stop.getStore().getId()).collect(Collectors.toSet());
		Map<Long, Boolean> writableByMemberId = new HashMap<>();

		return entries.stream()
			.filter(entry -> activeStoreIds.contains(entry.field().storeId()))
			.filter(entry -> writableByMemberId.computeIfAbsent(entry.field().memberId(),
				authorId -> canWrite(course, authorId)))
			.sorted(Comparator.comparingLong((DraftEntry entry) -> entry.field().storeId())
				.thenComparingLong(entry -> entry.field().memberId()))
			.map(entry -> new MemoDraftResponse(
				entry.field().storeId(),
				entry.field().memberId(),
				entry.content(),
				entry.seq(),
				Objects.equals(entry.field().memberId(), memberId),
				LocalDateTime.ofInstant(Instant.ofEpochMilli(entry.updatedAt()), ZONE)))
			.toList();
	}

	private List<DraftEntry> readEntries(Long courseId) {
		try {
			return buffer.readAll(courseId);
		} catch (DataAccessException exception) {
			log.warn("[메모 초안] Redis 조회 실패 — 503 courseId={} cause={}", courseId, exception.toString());
			throw new CourseException(CourseErrorCode.COURSE_DRAFT_UNAVAILABLE);
		}
	}

	private boolean canWrite(Course course, Long authorId) {
		return courseAccessPolicy.canEdit(course, authorId) && !memberRestrictionReader.isWriteRestricted(authorId);
	}
}
