package sweetgirl.croissant.course.service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.dto.response.CourseVisitCandidateResponse;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseStoreVisit;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.repository.CourseMemberRepository;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.CourseStoreRepository;
import sweetgirl.croissant.course.repository.CourseStoreVisitRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.store.entity.Store;

/**
 * 코스의 가게를 "다녀왔다"고 표시하고, 그 표시를 빵로그 기록에 잇는다.
 *
 * 권한은 편집 권한이 아니라 <b>조회 권한</b>으로 본다. 방문 체크는 코스를 고치는 일이
 * 아니라 각자의 진행 상태이기 때문이다. 남의 공개 코스를 따라가며 체크하는 것이 정상이다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CourseStoreVisitService {

	private final CourseRepository courseRepository;
	private final CourseStoreRepository courseStoreRepository;
	private final CourseStoreVisitRepository courseStoreVisitRepository;
	private final BreadRecordRepository breadRecordRepository;
	private final MemberReader memberReader;
	private final CourseProgressService courseProgressService;
	private final CourseMemberRepository courseMemberRepository;

	public void markVisited(Long memberId, Long courseId, Long storeId, Long breadRecordId) {
		Member member = memberReader.getMember(memberId);
		Course course = getViewableCourse(courseId, memberId);
		Store store = getCourseStore(courseId, storeId);
		BreadRecord record = getOwnedRecordOfStore(breadRecordId, memberId, storeId);

		courseStoreVisitRepository.findByIdCourseIdAndIdMemberIdAndIdStoreId(courseId, memberId, storeId)
			.ifPresentOrElse(
				// 다른 기록으로 바꾸거나, 껐던 체크를 다시 켜는 경우 모두 여기로 온다.
				visit -> visit.relink(record, record.getEatenAt()),
				() -> courseStoreVisitRepository.save(
					CourseStoreVisit.of(course, member, store, record, record.getEatenAt())));

		courseProgressService.refreshForMember(memberId);
	}

	public void unmarkVisited(Long memberId, Long courseId, Long storeId) {
		memberReader.getMember(memberId);
		getViewableCourse(courseId, memberId);

		CourseStoreVisit visit = courseStoreVisitRepository
			.findByIdCourseIdAndIdMemberIdAndIdStoreId(courseId, memberId, storeId)
			.filter(found -> !found.isDeleted())
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_VISIT_NOT_FOUND));
		visit.softDelete();

		courseProgressService.refreshForMember(memberId);
	}

	@Transactional(readOnly = true)
	public List<CourseVisitCandidateResponse> getCandidates(Long memberId, Long courseId, Long storeId) {
		memberReader.getMember(memberId);
		getViewableCourse(courseId, memberId);
		getCourseStore(courseId, storeId);

		// 이 회원이 이미 어딘가에 연결해 쓰고 있는 기록 — 화면이 "다른 코스에서도 쓰는 중"을 알리게.
		Set<Long> linkedRecordIds = courseStoreVisitRepository
			.findAllByIdMemberIdAndDeletedAtIsNull(memberId).stream()
			.map(CourseStoreVisit::getBreadRecord)
			.filter(java.util.Objects::nonNull)
			.map(BreadRecord::getId)
			.collect(Collectors.toSet());

		return breadRecordRepository
			.findAllByMemberIdAndStoreIdAndDeletedAtIsNullOrderByEatenAtDesc(memberId, storeId).stream()
			.map(record -> CourseVisitCandidateResponse.of(record, linkedRecordIds.contains(record.getId())))
			.toList();
	}

	/**
	 * 비공개 코스는 방장과 활성 멤버만 본다. 편집 권한이 아니라 조회 권한 기준이다.
	 * 링크 공개(UNLISTED)도 제3자는 못 한다 — 방문 체크는 따라가기의 일부라 원본에 매달린다.
	 */
	private Course getViewableCourse(Long courseId, Long memberId) {
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		boolean isOwner = course.getMember() != null && course.getMember().getId().equals(memberId);
		if (!course.isPublic() && !isOwner
			&& !courseMemberRepository.existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(courseId, memberId)) {
			throw new CourseException(CourseErrorCode.COURSE_FORBIDDEN);
		}
		return course;
	}

	private Store getCourseStore(Long courseId, Long storeId) {
		return courseStoreRepository.findByCourseIdAndStoreIdAndDeletedAtIsNull(courseId, storeId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_STORE_NOT_FOUND))
			.getStore();
	}

	/** 남의 기록이나 다른 가게 기록으로는 체크할 수 없다. */
	private BreadRecord getOwnedRecordOfStore(Long breadRecordId, Long memberId, Long storeId) {
		BreadRecord record = breadRecordRepository.findByIdAndDeletedAtIsNull(breadRecordId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_VISIT_RECORD_MISMATCH));
		boolean mine = record.getMember() != null && record.getMember().getId().equals(memberId);
		boolean sameStore = Optional.ofNullable(record.getStore())
			.map(Store::getId)
			.filter(storeId::equals)
			.isPresent();
		if (!mine || !sameStore) {
			throw new CourseException(CourseErrorCode.COURSE_VISIT_RECORD_MISMATCH);
		}
		return record;
	}
}
