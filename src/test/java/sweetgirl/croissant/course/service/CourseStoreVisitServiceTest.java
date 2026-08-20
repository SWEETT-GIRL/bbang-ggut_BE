package sweetgirl.croissant.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.dto.response.CourseVisitCandidateResponse;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseStore;
import sweetgirl.croissant.course.entity.CourseStoreVisit;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.CourseStoreRepository;
import sweetgirl.croissant.course.repository.CourseStoreVisitRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.store.entity.Store;

@ExtendWith(MockitoExtension.class)
@DisplayName("CourseStoreVisitService")
class CourseStoreVisitServiceTest {

	private static final Long MEMBER_ID = 1L;
	private static final Long COURSE_ID = 100L;
	private static final Long STORE_ID = 10L;
	private static final Long RECORD_ID = 5L;

	@Mock
	private CourseRepository courseRepository;
	@Mock
	private CourseStoreRepository courseStoreRepository;
	@Mock
	private CourseStoreVisitRepository courseStoreVisitRepository;
	@Mock
	private BreadRecordRepository breadRecordRepository;
	@Mock
	private MemberReader memberReader;
	@Mock
	private CourseProgressService courseProgressService;

	@InjectMocks
	private CourseStoreVisitService courseStoreVisitService;

	@Test
	@DisplayName("markVisited: 처음 표시하면 방문을 새로 저장한다")
	void markVisited_firstTime_saves() {
		givenMemberAndPublicCourse();
		givenCourseStore();
		BreadRecord record = mockRecord(MEMBER_ID, STORE_ID);
		given(breadRecordRepository.findByIdAndDeletedAtIsNull(RECORD_ID)).willReturn(Optional.of(record));
		given(courseStoreVisitRepository.findByIdCourseIdAndIdMemberIdAndIdStoreId(COURSE_ID, MEMBER_ID, STORE_ID))
			.willReturn(Optional.empty());

		courseStoreVisitService.markVisited(MEMBER_ID, COURSE_ID, STORE_ID, RECORD_ID);

		then(courseStoreVisitRepository).should().save(any(CourseStoreVisit.class));
		then(courseProgressService).should().refreshForMember(MEMBER_ID);
	}

	@Test
	@DisplayName("markVisited: 이미 표시돼 있으면 새로 저장하지 않고 그 기록으로 다시 잇는다")
	void markVisited_existing_relinks() {
		givenMemberAndPublicCourse();
		givenCourseStore();
		BreadRecord record = mockRecord(MEMBER_ID, STORE_ID);
		given(breadRecordRepository.findByIdAndDeletedAtIsNull(RECORD_ID)).willReturn(Optional.of(record));
		CourseStoreVisit existing = mock(CourseStoreVisit.class);
		given(courseStoreVisitRepository.findByIdCourseIdAndIdMemberIdAndIdStoreId(COURSE_ID, MEMBER_ID, STORE_ID))
			.willReturn(Optional.of(existing));

		courseStoreVisitService.markVisited(MEMBER_ID, COURSE_ID, STORE_ID, RECORD_ID);

		then(existing).should().relink(record, record.getEatenAt());
		then(courseStoreVisitRepository).should(never()).save(any());
	}

	@Test
	@DisplayName("markVisited: 남의 기록으로 표시하면 COURSE_VISIT_RECORD_MISMATCH")
	void markVisited_othersRecord_throws() {
		givenMemberAndPublicCourse();
		givenCourseStore();
		BreadRecord record = mockRecord(999L, STORE_ID);
		given(breadRecordRepository.findByIdAndDeletedAtIsNull(RECORD_ID)).willReturn(Optional.of(record));

		assertThatThrownBy(() -> courseStoreVisitService.markVisited(MEMBER_ID, COURSE_ID, STORE_ID, RECORD_ID))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_VISIT_RECORD_MISMATCH.getMessage());
	}

	@Test
	@DisplayName("markVisited: 다른 가게 기록으로 표시하면 COURSE_VISIT_RECORD_MISMATCH")
	void markVisited_otherStoreRecord_throws() {
		givenMemberAndPublicCourse();
		givenCourseStore();
		BreadRecord record = mockRecord(MEMBER_ID, 77L);
		given(breadRecordRepository.findByIdAndDeletedAtIsNull(RECORD_ID)).willReturn(Optional.of(record));

		assertThatThrownBy(() -> courseStoreVisitService.markVisited(MEMBER_ID, COURSE_ID, STORE_ID, RECORD_ID))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_VISIT_RECORD_MISMATCH.getMessage());
	}

	@Test
	@DisplayName("markVisited: 남의 비공개 코스이면 COURSE_FORBIDDEN")
	void markVisited_othersPrivateCourse_throws() {
		Member member = mockMember(MEMBER_ID);
		Course course = mockCourse(999L, false);
		given(memberReader.getMember(MEMBER_ID)).willReturn(member);
		given(courseRepository.findByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.of(course));

		assertThatThrownBy(() -> courseStoreVisitService.markVisited(MEMBER_ID, COURSE_ID, STORE_ID, RECORD_ID))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
	}

	@Test
	@DisplayName("unmarkVisited: 표시가 없으면 COURSE_VISIT_NOT_FOUND")
	void unmarkVisited_missing_throws() {
		Member member = mockMember(MEMBER_ID);
		Course course = mockCourse(MEMBER_ID, true);
		given(memberReader.getMember(MEMBER_ID)).willReturn(member);
		given(courseRepository.findByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.of(course));
		given(courseStoreVisitRepository.findByIdCourseIdAndIdMemberIdAndIdStoreId(COURSE_ID, MEMBER_ID, STORE_ID))
			.willReturn(Optional.empty());

		assertThatThrownBy(() -> courseStoreVisitService.unmarkVisited(MEMBER_ID, COURSE_ID, STORE_ID))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_VISIT_NOT_FOUND.getMessage());
	}

	@Test
	@DisplayName("getCandidates: 그 가게 기록을 전부 주고, 이미 쓰고 있는 기록은 linked로 표시한다")
	void getCandidates_marksLinked() {
		Member member = mockMember(MEMBER_ID);
		Course course = mockCourse(MEMBER_ID, true);
		given(memberReader.getMember(MEMBER_ID)).willReturn(member);
		given(courseRepository.findByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.of(course));
		givenCourseStore();

		BreadRecord linked = mockRecord(MEMBER_ID, STORE_ID);
		BreadRecord plain = mockRecord(MEMBER_ID, STORE_ID);
		lenient().when(linked.getId()).thenReturn(RECORD_ID);
		lenient().when(plain.getId()).thenReturn(6L);
		given(breadRecordRepository
			.findAllByMemberIdAndStoreIdAndDeletedAtIsNullOrderByEatenAtDesc(MEMBER_ID, STORE_ID))
			.willReturn(List.of(linked, plain));

		CourseStoreVisit used = mock(CourseStoreVisit.class);
		given(used.getBreadRecord()).willReturn(linked);
		given(courseStoreVisitRepository.findAllByIdMemberIdAndDeletedAtIsNull(MEMBER_ID))
			.willReturn(List.of(used));

		List<CourseVisitCandidateResponse> result = courseStoreVisitService.getCandidates(MEMBER_ID, COURSE_ID,
			STORE_ID);

		assertThat(result).hasSize(2);
		assertThat(result.get(0).linked()).isTrue();
		assertThat(result.get(1).linked()).isFalse();
	}

	// ───────────────────────── helpers ─────────────────────────

	private void givenMemberAndPublicCourse() {
		Member member = mockMember(MEMBER_ID);
		Course course = mockCourse(MEMBER_ID, true);
		given(memberReader.getMember(MEMBER_ID)).willReturn(member);
		given(courseRepository.findByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.of(course));
	}

	private void givenCourseStore() {
		CourseStore courseStore = mock(CourseStore.class);
		Store store = mock(Store.class);
		lenient().when(store.getId()).thenReturn(STORE_ID);
		lenient().when(courseStore.getStore()).thenReturn(store);
		given(courseStoreRepository.findByCourseIdAndStoreIdAndDeletedAtIsNull(COURSE_ID, STORE_ID))
			.willReturn(Optional.of(courseStore));
	}

	private Member mockMember(Long id) {
		Member member = mock(Member.class);
		lenient().when(member.getId()).thenReturn(id);
		return member;
	}

	private Course mockCourse(Long ownerId, boolean isPublic) {
		Course course = mock(Course.class);
		lenient().when(course.getId()).thenReturn(COURSE_ID);
		lenient().when(course.isPublic()).thenReturn(isPublic);
		Member owner = mockMember(ownerId);
		lenient().when(course.getMember()).thenReturn(owner);
		return course;
	}

	private BreadRecord mockRecord(Long ownerId, Long storeId) {
		BreadRecord record = mock(BreadRecord.class);
		lenient().when(record.getId()).thenReturn(RECORD_ID);
		Member owner = mockMember(ownerId);
		lenient().when(record.getMember()).thenReturn(owner);
		Store store = mock(Store.class);
		lenient().when(store.getId()).thenReturn(storeId);
		lenient().when(record.getStore()).thenReturn(store);
		lenient().when(record.getEatenAt()).thenReturn(LocalDateTime.now().minusDays(1));
		return record;
	}
}
