package sweetgirl.croissant.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Point;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.dto.request.CourseCreateRequest;
import sweetgirl.croissant.course.dto.request.CourseStoreAddRequest;
import sweetgirl.croissant.course.dto.request.CourseStoreReorderRequest;
import sweetgirl.croissant.course.dto.request.CourseStoreUpdateRequest;
import sweetgirl.croissant.course.dto.request.CourseUpdateRequest;
import sweetgirl.croissant.course.dto.response.CourseStoreResponse;
import sweetgirl.croissant.course.dto.response.CourseUpdateResponse;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseImageFile;
import sweetgirl.croissant.course.entity.CourseStore;
import sweetgirl.croissant.course.entity.CourseStoreMemo;
import sweetgirl.croissant.course.enums.CourseType;
import sweetgirl.croissant.course.enums.CourseVisibility;
import sweetgirl.croissant.course.event.CourseStoreAddedEvent;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.repository.CourseImageFileRepository;
import sweetgirl.croissant.course.repository.CourseMemberRepository;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.CourseStoreMemoRepository;
import sweetgirl.croissant.course.repository.CourseStoreRepository;
import sweetgirl.croissant.course.repository.SavedCourseRepository;
import sweetgirl.croissant.course.util.CollabTokenGenerator;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.external.aws.service.ImageProcessingCallbackService;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.service.MemberRestrictionReader;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.repository.StoreRepository;

@ExtendWith(MockitoExtension.class)
class CourseCommandServiceTest {

	@Mock
	CourseRepository courseRepository;
	@Mock
	CourseStoreRepository courseStoreRepository;
	@Mock
	CourseImageFileRepository courseImageFileRepository;
	@Mock
	SavedCourseRepository savedCourseRepository;
	@Mock
	CourseMemberRepository courseMemberRepository;
	@Mock
	CourseStoreMemoRepository courseStoreMemoRepository;
	@Mock
	MemberRepository memberRepository;
	@Mock
	StoreRepository storeRepository;
	@Mock
	S3Service s3Service;
	@Mock
	ImageProcessingCallbackService imageProcessingCallbackService;
	@Mock
	CourseProgressService courseProgressService;
	@Mock
	MemberRestrictionReader memberRestrictionReader;
	@Mock
	ApplicationEventPublisher eventPublisher;

	@InjectMocks
	CourseCommandService courseCommandService;

	/** 권한 판정은 CourseAccessPolicy 로 갔지만 근거는 같은 courseMemberRepository 다 — 실체를 넣어 기존 스텁이 그대로 산다. */
	@BeforeEach
	void injectAccessPolicy() {
		ReflectionTestUtils.setField(courseCommandService, "courseAccessPolicy",
			new CourseAccessPolicy(courseMemberRepository));
	}

	// ───────────────────────── createCourse ─────────────────────────

	@Test
	@DisplayName("createCourse: 코스를 생성하고 ID를 반환한다")
	void createCourse_success() {
		Long memberId = 1L;
		Member member = mockMember(memberId);
		CourseCreateRequest request = mock(CourseCreateRequest.class);
		lenient().when(request.getTitle()).thenReturn("나의 빵지순례");
		lenient().when(request.getIsPublic()).thenReturn(false);

		given(memberRepository.findByIdAndDeletedAtIsNull(memberId)).willReturn(Optional.of(member));
		Course savedCourse = mock(Course.class);
		given(savedCourse.getId()).willReturn(10L);
		given(courseRepository.save(any(Course.class))).willReturn(savedCourse);

		Long courseId = courseCommandService.createCourse(memberId, request);

		assertThat(courseId).isEqualTo(10L);
		then(courseRepository).should().save(any(Course.class));
	}

	@Test
	@DisplayName("createCourse: 지역·여행 날짜를 함께 저장한다")
	void createCourse_withPlan_success() {
		Long memberId = 1L;
		Member member = mockMember(memberId);
		CourseCreateRequest request = mock(CourseCreateRequest.class);
		given(request.getTitle()).willReturn("부산 빵투어");
		given(request.getRegion()).willReturn("부산");
		given(request.getStartDate()).willReturn(LocalDate.of(2026, 8, 20));
		given(request.getEndDate()).willReturn(LocalDate.of(2026, 8, 21));

		given(memberRepository.findByIdAndDeletedAtIsNull(memberId)).willReturn(Optional.of(member));
		given(courseRepository.save(any(Course.class))).willAnswer(inv -> inv.getArgument(0));

		courseCommandService.createCourse(memberId, request);

		ArgumentCaptor<Course> captor = ArgumentCaptor.forClass(Course.class);
		then(courseRepository).should().save(captor.capture());
		Course saved = captor.getValue();
		assertThat(saved.getRegion()).isEqualTo("부산");
		assertThat(saved.getStartDate()).isEqualTo(LocalDate.of(2026, 8, 20));
		assertThat(saved.getEndDate()).isEqualTo(LocalDate.of(2026, 8, 21));
	}

	@Test
	@DisplayName("createCourse: 종료일이 시작일보다 앞서면 COURSE400_12")
	void createCourse_endBeforeStart_throws() {
		Long memberId = 1L;
		Member member = mockMember(memberId);
		CourseCreateRequest request = mock(CourseCreateRequest.class);
		given(request.getStartDate()).willReturn(LocalDate.of(2026, 8, 21));
		given(request.getEndDate()).willReturn(LocalDate.of(2026, 8, 20));
		given(memberRepository.findByIdAndDeletedAtIsNull(memberId)).willReturn(Optional.of(member));

		assertThatThrownBy(() -> courseCommandService.createCourse(memberId, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_DATES_INVALID.getMessage());
		then(courseRepository).should(never()).save(any());
	}

	@Test
	@DisplayName("createCourse: 시작일만 오면 COURSE400_12")
	void createCourse_startOnly_throws() {
		Long memberId = 1L;
		Member member = mockMember(memberId);
		CourseCreateRequest request = mock(CourseCreateRequest.class);
		given(request.getStartDate()).willReturn(LocalDate.of(2026, 8, 21));
		given(memberRepository.findByIdAndDeletedAtIsNull(memberId)).willReturn(Optional.of(member));

		assertThatThrownBy(() -> courseCommandService.createCourse(memberId, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_DATES_INVALID.getMessage());
	}

	// ───────────────────────── updateCourse ─────────────────────────

	@Test
	@DisplayName("updateCourse: 소유자가 수정하면 성공한다")
	void updateCourse_owner_success() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "원래 제목", CourseType.CUSTOM, memberId, null);
		CourseUpdateRequest request = mockUpdateRequest();
		given(request.getTitle()).willReturn("수정된 제목");
		given(request.getIsPublic()).willReturn(true);

		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));

		courseCommandService.updateCourse(memberId, courseId, request);

		then(course).should().updateTitle("수정된 제목");
		then(course).should().updateVisibility(CourseVisibility.PUBLIC);
		then(courseRepository).should().bumpVersion(courseId);
	}

	@Test
	@DisplayName("updateCourse: 타인이 수정하면 COURSE_FORBIDDEN을 던진다")
	void updateCourse_otherMember_throwsForbidden() {
		Long ownerId = 1L;
		Long otherId = 99L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "제목", CourseType.CUSTOM, ownerId, null);
		CourseUpdateRequest request = mockUpdateRequest();

		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));

		assertThatThrownBy(() -> courseCommandService.updateCourse(otherId, courseId, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
		then(courseRepository).should(never()).bumpVersion(any());
	}

	@Test
	@DisplayName("updateCourse: CURATED 코스 수정 시도 시 COURSE_FORBIDDEN을 던진다")
	void updateCourse_curatedCourse_throwsForbidden() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "큐레이션 코스", CourseType.CURATED, memberId, null);
		CourseUpdateRequest request = mockUpdateRequest();

		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));

		assertThatThrownBy(() -> courseCommandService.updateCourse(memberId, courseId, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
	}

	@Test
	@DisplayName("updateCourse: FORKED 코스이고 원본과 동일하면 COURSE_FORK_NOT_MODIFIED를 던진다")
	void updateCourse_forkedSameAsSource_throwsForkNotModified() {
		Long memberId = 1L;
		Long courseId = 10L;
		Long sourceCourseId = 5L;
		String sourceTitle = "원본 제목";

		Course course = mockCourse(courseId, "copy of 원본 제목", CourseType.FORKED, memberId, sourceCourseId);
		Course sourceCourse = mockCourse(sourceCourseId, sourceTitle, CourseType.CURATED, null, null);

		CourseUpdateRequest request = mockUpdateRequest();
		given(request.getTitle()).willReturn(sourceTitle);
		// 동일 판정은 '공개로 바꾸려 할 때'만 탄다. mockCourse 는 isPublic() 을 스텁하지 않아 false 이므로 요청으로 켠다.
		given(request.getIsPublic()).willReturn(true);

		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseRepository.findByIdAndDeletedAtIsNull(sourceCourseId)).willReturn(Optional.of(sourceCourse));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId))
			.willReturn(List.of());
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(sourceCourseId))
			.willReturn(List.of());

		assertThatThrownBy(() -> courseCommandService.updateCourse(memberId, courseId, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORK_NOT_MODIFIED.getMessage());
	}

	// ───────────────────────── deleteCourse ─────────────────────────

	@Test
	@DisplayName("deleteCourse: 소유자가 삭제하면 소프트 딜리트된다")
	void deleteCourse_success() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "삭제할 코스", CourseType.CUSTOM, memberId, null);

		given(courseRepository.findByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));

		courseCommandService.deleteCourse(memberId, courseId);

		then(course).should().softDelete();
	}

	// ───────────────────────── addStore ─────────────────────────

	@Test
	@DisplayName("addStore: 가게를 코스에 추가하면 CourseStoreResponse를 반환한다")
	void addStore_success() {
		Long memberId = 1L;
		Long courseId = 10L;
		Long storeId = 100L;

		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);
		Store store = mockStore(storeId);
		CourseStoreAddRequest request = mock(CourseStoreAddRequest.class);
		given(request.getStoreId()).willReturn(storeId);
		// Mockito는 Integer 미스텁 메서드에 0을 돌려주므로, day 미전송 상태를 명시 스텁
		given(request.getDay()).willReturn(null);
		given(request.getMemo()).willReturn("메모");
		given(request.getVisitTime()).willReturn(LocalTime.of(10, 0));

		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(storeRepository.findById(storeId)).willReturn(Optional.of(store));

		CourseStore savedCourseStore = mockCourseStore(1, store);
		given(courseStoreRepository.save(any(CourseStore.class))).willReturn(savedCourseStore);
		Member author = mockMember(memberId);
		given(memberRepository.getReferenceById(memberId)).willReturn(author);

		CourseStoreResponse result = courseCommandService.addStore(memberId, courseId, request);

		assertThat(result).isNotNull();
		then(courseStoreRepository).should().save(any(CourseStore.class));
		then(courseRepository).should().bumpVersion(courseId);
		// 가게가 늘었으니 완주 승격 허용
		then(courseProgressService).should().refreshForCourse(courseId, true);
		// 메모는 course_stores.memo 가 아니라 내 메모 행으로 들어가고, 응답엔 myMemo·memos 로 실린다
		then(courseStoreMemoRepository).should().save(any(CourseStoreMemo.class));
		assertThat(result.myMemo()).isEqualTo("메모");
		assertThat(result.memo()).isEqualTo("메모");
		assertThat(result.memos()).hasSize(1);
		assertThat(result.memos().get(0).isMine()).isTrue();
		// 활성 정류장 목록 하나로 중복·다음 순서를 판정한다 — exists·MAX 쿼리는 나가지 않는다
		then(courseStoreRepository).should(never()).existsByCourseIdAndStoreIdAndDeletedAtIsNull(any(), any());
		then(courseStoreRepository).should(never()).findMaxVisitOrderByCourseId(any());
	}

	@Test
	@DisplayName("addStore: 마지막에 붙이면 활성 정류장의 최대 순서 다음을 쓴다")
	void addStore_append_usesNextVisitOrder() {
		Long memberId = 1L;
		Long courseId = 10L;
		Long storeId = 100L;

		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);
		Store store = mockStore(storeId);
		CourseStoreAddRequest request = mock(CourseStoreAddRequest.class);
		given(request.getStoreId()).willReturn(storeId);
		given(request.getDay()).willReturn(null);

		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(storeRepository.findById(storeId)).willReturn(Optional.of(store));
		CourseStore firstStop = mockCourseStore(1, mockStore(101L));
		CourseStore secondStop = mockCourseStore(2, mockStore(102L));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId))
			.willReturn(List.of(firstStop, secondStop));

		CourseStoreResponse result = courseCommandService.addStore(memberId, courseId, request);

		assertThat(result.visitOrder()).isEqualTo(3);
		assertThat(result.day()).isEqualTo(1);
		then(courseStoreRepository).should(never()).flush();
		then(courseStoreRepository).should(never()).findMaxVisitOrderByCourseId(any());
	}

	@Test
	@DisplayName("addStore: 이미 추가된 가게이면 COURSE_STORE_ALREADY_EXISTS를 던진다")
	void addStore_duplicate_throwsAlreadyExists() {
		Long memberId = 1L;
		Long courseId = 10L;
		Long storeId = 100L;

		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);
		CourseStoreAddRequest request = mock(CourseStoreAddRequest.class);
		given(request.getStoreId()).willReturn(storeId);

		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		// 활성 정류장 목록에 같은 가게가 이미 있다
		CourseStore sameStoreStop = mockCourseStore(1, mockStore(storeId));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId))
			.willReturn(List.of(sameStoreStop));

		assertThatThrownBy(() -> courseCommandService.addStore(memberId, courseId, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_STORE_ALREADY_EXISTS.getMessage());
		then(storeRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("addStore: 일차를 지정하면 해당 일차의 마지막 위치에 끼워 넣고 뒷 가게 순서를 민다")
	void addStore_withDay_insertsAfterRequestedDay() {
		Long memberId = 1L;
		Long courseId = 10L;
		Long storeId = 100L;

		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);
		Store store = mockStore(storeId);
		CourseStoreAddRequest request = mock(CourseStoreAddRequest.class);
		given(request.getStoreId()).willReturn(storeId);
		given(request.getDay()).willReturn(1);

		CourseStore day1Store = mockCourseStore(1, 1, mockStore(101L));
		CourseStore day2Store = mockCourseStore(2, 2, mockStore(102L));

		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(storeRepository.findById(storeId)).willReturn(Optional.of(store));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId))
			.willReturn(List.of(day1Store, day2Store));

		CourseStoreResponse result = courseCommandService.addStore(memberId, courseId, request);

		assertThat(result.visitOrder()).isEqualTo(2);
		assertThat(result.day()).isEqualTo(1);
		// 1일차 마지막 뒤에 끼우고, 2일차 가게는 한 칸 밀린다
		then(day1Store).should().updateOrder(-1);
		then(day1Store).should().updateOrder(1);
		then(day2Store).should().updateOrder(-2);
		then(day2Store).should().updateOrder(3);
		then(courseStoreRepository).should().flush();
		then(course).should().ensureDayCountAtLeast(1);
	}

	@Test
	@DisplayName("addStore: 존재하는 일차 범위를 벗어나면 COURSE_STORE_DAY_INVALID를 던진다")
	void addStore_dayOutOfRange_throwsDayInvalid() {
		Long memberId = 1L;
		Long courseId = 10L;
		Long storeId = 100L;

		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);
		Store store = mockStore(storeId);
		CourseStoreAddRequest request = mock(CourseStoreAddRequest.class);
		given(request.getStoreId()).willReturn(storeId);
		given(request.getDay()).willReturn(3);

		CourseStore day1Store = mockCourseStore(1, 1, mockStore(101L));

		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(storeRepository.findById(storeId)).willReturn(Optional.of(store));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId))
			.willReturn(List.of(day1Store));

		assertThatThrownBy(() -> courseCommandService.addStore(memberId, courseId, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_STORE_DAY_INVALID.getMessage());
	}

	@Test
	@DisplayName("addStore: 코스가 아는 일차(날짜 이틀)면 1일차가 비어 있어도 2일차에 담을 수 있다")
	void addStore_skipsEmptyDay_whenCourseKnowsTheDay() {
		Long memberId = 1L;
		Long courseId = 10L;
		Long storeId = 100L;

		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);
		given(course.getStartDate()).willReturn(LocalDate.of(2026, 9, 21));
		given(course.getEndDate()).willReturn(LocalDate.of(2026, 9, 22));
		Store store = mockStore(storeId);
		CourseStoreAddRequest request = mock(CourseStoreAddRequest.class);
		given(request.getStoreId()).willReturn(storeId);
		given(request.getDay()).willReturn(2);

		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(storeRepository.findById(storeId)).willReturn(Optional.of(store));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId))
			.willReturn(List.of());
		given(courseStoreRepository.save(any(CourseStore.class))).willAnswer(call -> call.getArgument(0));
		CourseStoreResponse result = courseCommandService.addStore(memberId, courseId, request);

		assertThat(result.day()).isEqualTo(2);
		then(course).should().ensureDayCountAtLeast(2);
	}

	@Test
	@DisplayName("updateCourse: 날짜를 담긴 마지막 일차보다 짧게 줄이면 COURSE_DAY_COUNT_INVALID를 던진다")
	void updateCourse_shrinkingDatesBelowStores_throwsDayCountInvalid() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);
		CourseUpdateRequest request = mockUpdateRequest();
		given(request.getStartDate()).willReturn(LocalDate.of(2026, 9, 21));
		given(request.getEndDate()).willReturn(LocalDate.of(2026, 9, 21));
		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseStoreRepository.findMaxDayByCourseId(courseId)).willReturn(2);

		assertThatThrownBy(() -> courseCommandService.updateCourse(memberId, courseId, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_DAY_COUNT_INVALID.getMessage());
		then(course).should(never()).updateDates(any(), any());
	}

	// ───────────────────────── updateStore ─────────────────────────

	@Test
	@DisplayName("updateStore: 메모와 방문 시간을 수정한다")
	void updateStore_success() {
		Long memberId = 1L;
		Long courseId = 10L;
		Long storeId = 100L;

		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);
		CourseStore courseStore = mock(CourseStore.class);
		CourseStoreUpdateRequest request = mock(CourseStoreUpdateRequest.class);
		given(request.getMemo()).willReturn("새 메모");
		given(request.getVisitTime()).willReturn(LocalTime.of(14, 30));

		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseStoreRepository.findByCourseIdAndStoreIdAndDeletedAtIsNull(courseId, storeId))
			.willReturn(Optional.of(courseStore));
		Member author = mockMember(memberId);
		given(memberRepository.getReferenceById(memberId)).willReturn(author);

		courseCommandService.updateStore(memberId, courseId, storeId, request);

		// memo 는 내 메모 행으로만 간다 — course_stores.memo 는 더 이상 쓰지 않는다
		then(courseStoreMemoRepository).should().save(any(CourseStoreMemo.class));
		then(courseStore).should(never()).updateMemo(any());
		then(courseStore).should().updateVisitTime(LocalTime.of(14, 30));
		then(courseRepository).should().bumpVersion(courseId);
	}

	// ───────────────────────── removeStore ─────────────────────────

	@Test
	@DisplayName("removeStore: 가게를 소프트 딜리트한다")
	void removeStore_success() {
		Long memberId = 1L;
		Long courseId = 10L;
		Long storeId = 100L;

		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);
		CourseStore courseStore = mock(CourseStore.class);
		given(courseStore.getId()).willReturn(501L);

		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseStoreRepository.findByCourseIdAndStoreIdAndDeletedAtIsNull(courseId, storeId))
			.willReturn(Optional.of(courseStore));

		courseCommandService.removeStore(memberId, courseId, storeId);

		then(courseStore).should().softDelete();
		// 메모는 정류장 행에 묶인다 — 가게를 빼면 전원 메모가 함께 닫힌다
		then(courseStoreMemoRepository).should().softDeleteAllByCourseStoreId(501L);
		// 가게가 줄었으니 완주 승격 금지 — 분모만 작아져 안 간 코스가 완주 처리되면 안 된다
		then(courseProgressService).should().refreshForCourse(courseId, false);
		then(courseRepository).should().bumpVersion(courseId);
	}

	// ───────────────────────── reorderStores ─────────────────────────

	@Test
	@DisplayName("reorderStores: 가게 순서를 일괄 갱신한다")
	void reorderStores_success() {
		Long memberId = 1L;
		Long courseId = 10L;
		Long courseStoreId1 = 1L;
		Long courseStoreId2 = 2L;

		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);

		CourseStoreReorderRequest.CourseStoreOrderItem item1 = mock(
			CourseStoreReorderRequest.CourseStoreOrderItem.class);
		given(item1.getCourseStoreId()).willReturn(courseStoreId1);
		// Mockito는 Integer 미스텁 메서드에 0을 돌려주므로, day 미전송(구버전 앱) 상태를 명시 스텁
		given(item1.getDay()).willReturn(null);

		CourseStoreReorderRequest.CourseStoreOrderItem item2 = mock(
			CourseStoreReorderRequest.CourseStoreOrderItem.class);
		given(item2.getCourseStoreId()).willReturn(courseStoreId2);
		given(item2.getDay()).willReturn(null);

		CourseStoreReorderRequest request = mock(CourseStoreReorderRequest.class);
		given(request.getCourseStoreOrders()).willReturn(List.of(item1, item2));
		// Mockito 는 List 미스텁 메서드에 빈 리스트를 돌려주므로, expectedOrder 미전송(구버전 앱) 상태를 명시 스텁
		given(request.getExpectedOrder()).willReturn(null);

		CourseStore courseStore1 = mock(CourseStore.class);
		given(courseStore1.getId()).willReturn(courseStoreId1);
		CourseStore courseStore2 = mock(CourseStore.class);
		given(courseStore2.getId()).willReturn(courseStoreId2);

		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId))
			.willReturn(List.of(courseStore1, courseStore2));

		courseCommandService.reorderStores(memberId, courseId, request);

		// 유니크 제약 충돌 방지를 위한 임시 음수 순서 → 최종 순서 2단계 갱신
		then(courseStore1).should().updateOrder(-1);
		then(courseStore1).should().updateOrder(1);
		then(courseStore2).should().updateOrder(-2);
		then(courseStore2).should().updateOrder(2);
		then(courseStoreRepository).should().flush();
		// day 미전송이면 기존 일차 유지
		then(courseStore1).should(never()).updateDay(any());
		then(courseStore2).should(never()).updateDay(any());
		then(courseRepository).should().bumpVersion(courseId);
		// 활성 정류장 목록 하나로 집합 검증·행 조회를 다 한다 — count·findAllById 는 나가지 않는다
		then(courseStoreRepository).should(never()).countByCourseIdAndDeletedAtIsNull(any());
		then(courseStoreRepository).should(never()).findAllById(any());
	}

	@Test
	@DisplayName("reorderStores: 일차 정보가 있으면 순서와 함께 일차도 갱신한다")
	void reorderStores_withDays_updatesDays() {
		Long memberId = 1L;
		Long courseId = 10L;

		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);

		CourseStoreReorderRequest.CourseStoreOrderItem item1 = mock(
			CourseStoreReorderRequest.CourseStoreOrderItem.class);
		given(item1.getCourseStoreId()).willReturn(1L);
		given(item1.getDay()).willReturn(1);
		CourseStoreReorderRequest.CourseStoreOrderItem item2 = mock(
			CourseStoreReorderRequest.CourseStoreOrderItem.class);
		given(item2.getCourseStoreId()).willReturn(2L);
		given(item2.getDay()).willReturn(2);

		CourseStoreReorderRequest request = mock(CourseStoreReorderRequest.class);
		given(request.getCourseStoreOrders()).willReturn(List.of(item1, item2));
		// Mockito 는 List 미스텁 메서드에 빈 리스트를 돌려주므로, expectedOrder 미전송(구버전 앱) 상태를 명시 스텁
		given(request.getExpectedOrder()).willReturn(null);

		CourseStore courseStore1 = mock(CourseStore.class);
		given(courseStore1.getId()).willReturn(1L);
		CourseStore courseStore2 = mock(CourseStore.class);
		given(courseStore2.getId()).willReturn(2L);

		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId))
			.willReturn(List.of(courseStore1, courseStore2));

		courseCommandService.reorderStores(memberId, courseId, request);

		then(courseStore1).should().updateOrder(1);
		then(courseStore1).should().updateDay(1);
		then(courseStore2).should().updateOrder(2);
		then(courseStore2).should().updateDay(2);
		then(course).should().ensureDayCountAtLeast(2);
	}

	@Test
	@DisplayName("reorderStores: 일차가 순서대로 내려가면(2,1) COURSE_STORE_DAY_INVALID를 던진다")
	void reorderStores_dayGoesBackwards_throwsDayInvalid() {
		Long memberId = 1L;
		Long courseId = 10L;

		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);

		CourseStoreReorderRequest.CourseStoreOrderItem item1 = mock(
			CourseStoreReorderRequest.CourseStoreOrderItem.class);
		given(item1.getCourseStoreId()).willReturn(1L);
		given(item1.getDay()).willReturn(2);
		CourseStoreReorderRequest.CourseStoreOrderItem item2 = mock(
			CourseStoreReorderRequest.CourseStoreOrderItem.class);
		given(item2.getCourseStoreId()).willReturn(2L);
		given(item2.getDay()).willReturn(1);
		// 코스가 2일차까지 안다 — 첫 항목의 2일차는 되고, 그다음 1일차로 내려가는 게 거부된다
		given(course.getDayCount()).willReturn(2);

		CourseStoreReorderRequest request = mock(CourseStoreReorderRequest.class);
		given(request.getCourseStoreOrders()).willReturn(List.of(item1, item2));
		// Mockito 는 List 미스텁 메서드에 빈 리스트를 돌려주므로, expectedOrder 미전송(구버전 앱) 상태를 명시 스텁
		given(request.getExpectedOrder()).willReturn(null);

		CourseStore courseStore1 = mock(CourseStore.class);
		given(courseStore1.getId()).willReturn(1L);
		CourseStore courseStore2 = mock(CourseStore.class);
		given(courseStore2.getId()).willReturn(2L);

		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId))
			.willReturn(List.of(courseStore1, courseStore2));

		assertThatThrownBy(() -> courseCommandService.reorderStores(memberId, courseId, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_STORE_DAY_INVALID.getMessage());
	}

	@Test
	@DisplayName("reorderStores: 빈 앞 일차를 두고 뒤 일차에 놓을 수 있다(1,3) — 빈 일차는 day_count 가 표현한다")
	void reorderStores_dayGap_isAllowed() {
		Long memberId = 1L;
		Long courseId = 10L;

		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);

		CourseStoreReorderRequest.CourseStoreOrderItem item1 = mock(
			CourseStoreReorderRequest.CourseStoreOrderItem.class);
		given(item1.getCourseStoreId()).willReturn(1L);
		given(item1.getDay()).willReturn(1);
		CourseStoreReorderRequest.CourseStoreOrderItem item2 = mock(
			CourseStoreReorderRequest.CourseStoreOrderItem.class);
		given(item2.getCourseStoreId()).willReturn(2L);
		given(item2.getDay()).willReturn(3);
		// 코스가 3일차까지 안다 — 빈 2일차를 두고 3일차에 놓을 수 있다
		given(course.getDayCount()).willReturn(3);

		CourseStoreReorderRequest request = mock(CourseStoreReorderRequest.class);
		given(request.getCourseStoreOrders()).willReturn(List.of(item1, item2));
		// Mockito 는 List 미스텁 메서드에 빈 리스트를 돌려주므로, expectedOrder 미전송(구버전 앱) 상태를 명시 스텁
		given(request.getExpectedOrder()).willReturn(null);

		CourseStore courseStore1 = mock(CourseStore.class);
		given(courseStore1.getId()).willReturn(1L);
		CourseStore courseStore2 = mock(CourseStore.class);
		given(courseStore2.getId()).willReturn(2L);

		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId))
			.willReturn(List.of(courseStore1, courseStore2));

		courseCommandService.reorderStores(memberId, courseId, request);

		then(course).should().ensureDayCountAtLeast(3);
		then(courseStore2).should().updateDay(3);
	}

	@Test
	@DisplayName("reorderStores: 중복된 가게 ID가 있으면 COURSE_STORE_ORDER_INVALID를 던진다")
	void reorderStores_duplicateCourseStore_throwsInvalidOrder() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);
		CourseStoreReorderRequest.CourseStoreOrderItem item = mock(
			CourseStoreReorderRequest.CourseStoreOrderItem.class);
		given(item.getCourseStoreId()).willReturn(1L);
		CourseStoreReorderRequest request = mock(CourseStoreReorderRequest.class);
		given(request.getCourseStoreOrders()).willReturn(List.of(item, item));
		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId))
			.willReturn(List.of(mock(CourseStore.class), mock(CourseStore.class)));

		assertThatThrownBy(() -> courseCommandService.reorderStores(memberId, courseId, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_STORE_ORDER_INVALID.getMessage());
		then(courseRepository).should(never()).bumpVersion(any());
	}

	@Test
	@DisplayName("reorderStores: 다른 코스의 가게가 섞여 있으면 COURSE_STORE_NOT_FOUND를 던진다")
	void reorderStores_otherCourseStore_throwsNotFound() {
		Long memberId = 1L;
		Long courseId = 10L;
		Long otherCourseStoreId = 77L;

		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);

		CourseStoreReorderRequest.CourseStoreOrderItem item = mock(
			CourseStoreReorderRequest.CourseStoreOrderItem.class);
		given(item.getCourseStoreId()).willReturn(otherCourseStoreId);
		CourseStoreReorderRequest request = mock(CourseStoreReorderRequest.class);
		given(request.getCourseStoreOrders()).willReturn(List.of(item));
		given(request.getExpectedOrder()).willReturn(null);

		// 이 코스의 활성 정류장은 하나뿐이고, 요청한 id 는 그 목록에 없다 — 다른 코스의 행이거나 이미 뺀 행
		CourseStore ownCourseStore = mock(CourseStore.class);
		given(ownCourseStore.getId()).willReturn(1L);

		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId))
			.willReturn(List.of(ownCourseStore));

		assertThatThrownBy(() -> courseCommandService.reorderStores(memberId, courseId, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_STORE_NOT_FOUND.getMessage());
	}

	// ───────────────────────── image ─────────────────────────

	@Test
	@DisplayName("getPresignedUrl: presigned URL을 발급한다")
	void getPresignedUrl_success() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);

		given(courseRepository.findByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(s3Service.generatePresignedUrl(any(), eq("image/jpeg"))).willReturn(
			new sweetgirl.croissant.external.aws.dto.PresignedUrlResponse(
				"file.jpg", "image/jpeg", "https://s3.example.com/presigned", "courses/10/file.jpg"));

		var result = courseCommandService.getPresignedUrl(memberId, courseId, "image/jpeg");

		assertThat(result.uploadUrl()).contains("https://s3.example.com/presigned");
	}

	@Test
	@DisplayName("registerImage: 코스 이미지를 등록한다")
	void registerImage_success() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);
		sweetgirl.croissant.course.dto.request.CourseImageRegisterRequest request = mock(
			sweetgirl.croissant.course.dto.request.CourseImageRegisterRequest.class);
		given(request.getAwsKey()).willReturn("courses/10/abc.jpg");
		given(request.getMimeType()).willReturn("image/jpeg");
		given(request.getName()).willReturn("abc.jpg");

		given(courseRepository.findByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseImageFileRepository.findByCourseId(courseId)).willReturn(Optional.empty());

		courseCommandService.registerImage(memberId, courseId, request);

		then(courseImageFileRepository).should().save(any(CourseImageFile.class));
		then(courseRepository).should().bumpVersion(courseId);
	}

	@Test
	@DisplayName("deleteImage: 코스 이미지를 삭제한다")
	void deleteImage_success() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);
		CourseImageFile imageFile = mock(CourseImageFile.class);
		given(imageFile.getAwsKey()).willReturn("courses/10/abc.jpg");

		given(courseRepository.findByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseImageFileRepository.findByCourseId(courseId)).willReturn(Optional.of(imageFile));

		courseCommandService.deleteImage(memberId, courseId);

		then(s3Service).should().deleteObject("courses/10/abc.jpg");
		then(courseImageFileRepository).should().delete(imageFile);
		then(courseRepository).should().bumpVersion(courseId);
	}

	@Test
	@DisplayName("deleteImage: 이미지가 없으면 COURSE_IMAGE_NOT_FOUND를 던진다")
	void deleteImage_notFound_throwsImageNotFound() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);

		given(courseRepository.findByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseImageFileRepository.findByCourseId(courseId)).willReturn(Optional.empty());

		assertThatThrownBy(() -> courseCommandService.deleteImage(memberId, courseId))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_IMAGE_NOT_FOUND.getMessage());

		then(s3Service).should(never()).deleteObject(any());
		then(courseRepository).should(never()).bumpVersion(any());
	}

	// ───────────────────────── forkCourse ─────────────────────────

	@Test
	@DisplayName("forkCourse: 공개 코스를 포크하면 새 코스 ID를 반환한다")
	void forkCourse_publicCourse_success() {
		Long memberId = 1L;
		Long sourceId = 5L;

		Course sourceCourse = mockCourse(sourceId, "공개 커스텀 코스", CourseType.CUSTOM, null, null);
		given(sourceCourse.isPublic()).willReturn(true);
		Member member = mockMember(memberId);
		CourseStore sourceStore = mockCourseStore(1, mockStore(100L));

		given(courseRepository.findByIdAndDeletedAtIsNull(sourceId)).willReturn(Optional.of(sourceCourse));
		given(memberRepository.findByIdAndDeletedAtIsNull(memberId)).willReturn(Optional.of(member));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(sourceId))
			.willReturn(List.of(sourceStore));

		Course forkedCourse = mock(Course.class);
		given(forkedCourse.getId()).willReturn(20L);
		given(courseRepository.save(any(Course.class))).willReturn(forkedCourse);

		lenient().when(sourceStore.getMemo()).thenReturn("원본 방장 메모");

		Long forkedId = courseCommandService.forkCourse(memberId, sourceId, null);

		assertThat(forkedId).isEqualTo(20L);
		@SuppressWarnings("unchecked") ArgumentCaptor<List<CourseStore>> copiedCaptor = ArgumentCaptor
			.forClass(List.class);
		then(courseStoreRepository).should().saveAll(copiedCaptor.capture());
		// 메모는 사람의 것이라 복제하지 않는다 — 가게·순서·일차·방문시간만
		assertThat(copiedCaptor.getValue()).hasSize(1);
		assertThat(copiedCaptor.getValue().get(0).getMemo()).isNull();
		assertThat(copiedCaptor.getValue().get(0).getStore()).isSameAs(sourceStore.getStore());
		// 포크는 코스를 통째로 담은 것 — 복제된 가게마다 포크한 회원의 COURSE_ADD 신호(#663, 담기와 같은 무게)
		ArgumentCaptor<CourseStoreAddedEvent> event = ArgumentCaptor.forClass(CourseStoreAddedEvent.class);
		then(eventPublisher).should().publishEvent(event.capture());
		assertThat(event.getValue().memberId()).isEqualTo(memberId);
		assertThat(event.getValue().courseId()).isEqualTo(20L);
		assertThat(event.getValue().storeId()).isEqualTo(100L);
		assertThat(event.getValue().recommendationId()).isNull();
	}

	@Test
	@DisplayName("forkCourse: 비공개 코스를 제3자가 포크하면 COURSE_FORK_NOT_PUBLIC — 멤버 여부까지 보고 거부한다")
	void forkCourse_privateCourse_throwsForkNotPublic() {
		Long memberId = 1L;
		Long ownerId = 2L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "비공개 커스텀 코스", CourseType.CUSTOM, ownerId, null);

		given(courseRepository.findByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseMemberRepository.existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(courseId, memberId))
			.willReturn(false);

		assertThatThrownBy(() -> courseCommandService.forkCourse(memberId, courseId, null))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORK_NOT_PUBLIC.getMessage());
		then(courseRepository).should(never()).save(any());
	}

	@Test
	@DisplayName("forkCourse: 비공개 협업 코스도 활성 멤버는 포크할 수 있다")
	void forkCourse_privateCourse_activeMember_success() {
		Long memberId = 1L;
		Long ownerId = 2L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "비공개 협업 코스", CourseType.CUSTOM, ownerId, null);
		Member member = mockMember(memberId);
		CourseStore sourceStore = mockCourseStore(1, mockStore(100L));

		given(courseRepository.findByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseMemberRepository.existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(courseId, memberId))
			.willReturn(true);
		given(memberRepository.findByIdAndDeletedAtIsNull(memberId)).willReturn(Optional.of(member));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId))
			.willReturn(List.of(sourceStore));
		Course forkedCourse = mock(Course.class);
		given(forkedCourse.getId()).willReturn(20L);
		given(courseRepository.save(any(Course.class))).willReturn(forkedCourse);

		Long forkedId = courseCommandService.forkCourse(memberId, courseId, null);

		assertThat(forkedId).isEqualTo(20L);
		then(courseStoreRepository).should().saveAll(any());
	}

	@Test
	@DisplayName("forkCourse: 방장은 멤버 조회 없이 비공개 코스를 포크한다")
	void forkCourse_privateCourse_owner_skipsMemberCheck() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "내 비공개 코스", CourseType.CUSTOM, memberId, null);
		Member member = mockMember(memberId);

		given(courseRepository.findByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(memberRepository.findByIdAndDeletedAtIsNull(memberId)).willReturn(Optional.of(member));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId))
			.willReturn(List.of());
		Course forkedCourse = mock(Course.class);
		given(forkedCourse.getId()).willReturn(20L);
		given(courseRepository.save(any(Course.class))).willReturn(forkedCourse);

		courseCommandService.forkCourse(memberId, courseId, null);

		then(courseMemberRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("forkCourse: 비공개 FORKED 코스 포크 시도 시 COURSE_FORK_NOT_PUBLIC을 던진다")
	void forkCourse_privateForkedCourse_throwsForkNotPublic() {
		Long memberId = 1L;
		Long ownerId = 2L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "비공개 포크 코스", CourseType.FORKED, ownerId, 5L);

		given(courseRepository.findByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));

		assertThatThrownBy(() -> courseCommandService.forkCourse(memberId, courseId, null))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORK_NOT_PUBLIC.getMessage());
	}

	@Test
	@DisplayName("forkCourse: 링크 공개(UNLISTED) 코스는 토큰이 맞으면 제3자도 포크한다 — 링크로 본 코스를 챙겨두는 유일한 길")
	void forkCourse_unlistedCourse_withToken_success() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "링크 공개 코스", CourseType.CUSTOM, 2L, null);
		given(course.getVisibility()).willReturn(CourseVisibility.UNLISTED);
		given(course.getShareToken()).willReturn("secret");
		Member member = mockMember(memberId);
		given(courseRepository.findByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseMemberRepository.existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(courseId, memberId))
			.willReturn(false);
		given(memberRepository.findByIdAndDeletedAtIsNull(memberId)).willReturn(Optional.of(member));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId))
			.willReturn(List.of());
		Course forkedCourse = mock(Course.class);
		given(forkedCourse.getId()).willReturn(20L);
		given(courseRepository.save(any(Course.class))).willReturn(forkedCourse);

		assertThat(courseCommandService.forkCourse(memberId, courseId, "secret")).isEqualTo(20L);
	}

	@Test
	@DisplayName("forkCourse: 링크 공개 코스라도 토큰이 틀리면 COURSE_FORK_NOT_PUBLIC — 순차 id 만으로는 못 가져간다")
	void forkCourse_unlistedCourse_wrongToken_throwsForkNotPublic() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "링크 공개 코스", CourseType.CUSTOM, 2L, null);
		given(course.getVisibility()).willReturn(CourseVisibility.UNLISTED);
		given(course.getShareToken()).willReturn("secret");
		given(courseRepository.findByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseMemberRepository.existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(courseId, memberId))
			.willReturn(false);

		assertThatThrownBy(() -> courseCommandService.forkCourse(memberId, courseId, "wrong"))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORK_NOT_PUBLIC.getMessage());
		then(courseRepository).should(never()).save(any());
	}

	@Test
	@DisplayName("forkCourse: PRIVATE 은 토큰이 맞아도 COURSE_FORK_NOT_PUBLIC — 토큰보다 PRIVATE 이 강하다")
	void forkCourse_privateCourse_withToken_throwsForkNotPublic() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "비공개 코스", CourseType.CUSTOM, 2L, null);
		lenient().when(course.getVisibility()).thenReturn(CourseVisibility.PRIVATE);
		lenient().when(course.getShareToken()).thenReturn("secret");
		given(courseRepository.findByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseMemberRepository.existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(courseId, memberId))
			.willReturn(false);

		assertThatThrownBy(() -> courseCommandService.forkCourse(memberId, courseId, "secret"))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORK_NOT_PUBLIC.getMessage());
	}

	// ───────────────────────── reorderStores: 순서 CAS ─────────────────────────

	/** 정류장 2개(id 1, 2)를 1→2 순으로 가진 코스에서 [2, 1] 로 바꾸는 요청. expectedOrder 만 케이스별로 다르다. */
	private CourseStoreReorderRequest reorderRequestSwapping(Long courseId, Course course, List<Long> expectedOrder) {
		CourseStoreReorderRequest.CourseStoreOrderItem item1 = mock(
			CourseStoreReorderRequest.CourseStoreOrderItem.class);
		lenient().when(item1.getCourseStoreId()).thenReturn(2L);
		lenient().when(item1.getDay()).thenReturn(null);
		CourseStoreReorderRequest.CourseStoreOrderItem item2 = mock(
			CourseStoreReorderRequest.CourseStoreOrderItem.class);
		lenient().when(item2.getCourseStoreId()).thenReturn(1L);
		lenient().when(item2.getDay()).thenReturn(null);
		CourseStoreReorderRequest request = mock(CourseStoreReorderRequest.class);
		given(request.getCourseStoreOrders()).willReturn(List.of(item1, item2));
		lenient().when(request.getExpectedOrder()).thenReturn(expectedOrder);

		CourseStore courseStore1 = mock(CourseStore.class);
		lenient().when(courseStore1.getId()).thenReturn(1L);
		CourseStore courseStore2 = mock(CourseStore.class);
		lenient().when(courseStore2.getId()).thenReturn(2L);
		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		// 현재 DB 순서는 1 → 2
		lenient().when(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId))
			.thenReturn(List.of(courseStore1, courseStore2));
		return request;
	}

	@Test
	@DisplayName("reorderStores: expectedOrder 가 현재 순서와 같으면 저장한다")
	void reorderStores_expectedOrderMatches_saves() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);
		CourseStoreReorderRequest request = reorderRequestSwapping(courseId, course, List.of(1L, 2L));

		courseCommandService.reorderStores(memberId, courseId, request);

		then(courseStoreRepository).should().flush();
		then(courseRepository).should().bumpVersion(courseId);
	}

	@Test
	@DisplayName("reorderStores: expectedOrder 가 현재 순서와 다르면 COURSE_STALE(409) — 일행이 먼저 옮겼다")
	void reorderStores_expectedOrderStale_throwsStale() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);
		CourseStoreReorderRequest request = reorderRequestSwapping(courseId, course, List.of(2L, 1L));

		assertThatThrownBy(() -> courseCommandService.reorderStores(memberId, courseId, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_STALE.getMessage());
		then(courseStoreRepository).should(never()).flush();
		then(courseRepository).should(never()).bumpVersion(any());
	}

	@Test
	@DisplayName("reorderStores: expectedOrder 를 안 보내면(구버전 앱) 순서 CAS 를 건너뛰고 집합 검증만 한다")
	void reorderStores_withoutExpectedOrder_skipsCas() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);
		CourseStoreReorderRequest request = reorderRequestSwapping(courseId, course, null);

		courseCommandService.reorderStores(memberId, courseId, request);

		// 현재 순서(1 → 2)와 요청(2 → 1)이 달라도 CAS 가 없으니 저장된다
		then(courseStoreRepository).should().flush();
		then(courseRepository).should().bumpVersion(courseId);
	}

	@Test
	@DisplayName("reorderStores: 집합이 다르면 409 가 아니라 기존 COURSE_STORE_ORDER_INVALID 다 — 집합 검증이 CAS 보다 먼저")
	void reorderStores_setMismatch_throwsOrderInvalidNotStale() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);
		CourseStoreReorderRequest request = reorderRequestSwapping(courseId, course, List.of(2L, 1L));
		// DB 에는 정류장이 3개 — 요청 집합(2개)과 다르다
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId))
			.willReturn(List.of(mock(CourseStore.class), mock(CourseStore.class), mock(CourseStore.class)));

		assertThatThrownBy(() -> courseCommandService.reorderStores(memberId, courseId, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_STORE_ORDER_INVALID.getMessage());
		then(courseStoreRepository).should(never()).flush();
	}

	// ───────────────────────── updateCourse: 협업 공개 차단 · dayCount ─────────────────────────

	@Test
	@DisplayName("updateCourse: 방장 말고 활성 멤버가 있으면 공개로 바꿀 수 없다 — COURSE_COLLAB_CANNOT_BE_PUBLIC")
	void updateCourse_collabCourse_cannotBePublic() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);
		CourseUpdateRequest request = mockUpdateRequest();
		given(request.getIsPublic()).willReturn(true);
		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseMemberRepository.existsByIdCourseIdAndIdMemberIdNotAndDeletedAtIsNull(courseId, memberId))
			.willReturn(true);

		assertThatThrownBy(() -> courseCommandService.updateCourse(memberId, courseId, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_COLLAB_CANNOT_BE_PUBLIC.getMessage());
		then(course).should(never()).updateVisibility(any());
		then(courseRepository).should(never()).bumpVersion(any());
	}

	@Test
	@DisplayName("updateCourse: 방장 행만 있으면 아직 협업이 아니라 공개할 수 있다")
	void updateCourse_onlyOwnerRow_canBePublic() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);
		CourseUpdateRequest request = mockUpdateRequest();
		given(request.getIsPublic()).willReturn(true);
		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseMemberRepository.existsByIdCourseIdAndIdMemberIdNotAndDeletedAtIsNull(courseId, memberId))
			.willReturn(false);

		courseCommandService.updateCourse(memberId, courseId, request);

		then(course).should().updateVisibility(CourseVisibility.PUBLIC);
	}

	@Test
	@DisplayName("updateCourse: 비공개로 바꿀 때는 협업 여부를 보지 않는다")
	void updateCourse_toPrivate_skipsCollabCheck() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);
		CourseUpdateRequest request = mockUpdateRequest();
		given(request.getIsPublic()).willReturn(false);
		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));

		courseCommandService.updateCourse(memberId, courseId, request);

		then(course).should().updateVisibility(CourseVisibility.PRIVATE);
		then(courseMemberRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("updateCourse: dayCount 는 락 안에서 현재 값·가게가 있는 마지막 일차 이상일 때 저장된다")
	void updateCourse_dayCount_grows() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);
		given(course.getDayCount()).willReturn(2);
		CourseUpdateRequest request = mockUpdateRequest();
		given(request.getDayCount()).willReturn(3);
		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseStoreRepository.findMaxDayByCourseId(courseId)).willReturn(2);

		courseCommandService.updateCourse(memberId, courseId, request);

		then(course).should().updateDayCount(3);
		then(courseRepository).should().bumpVersion(courseId);
	}

	@Test
	@DisplayName("updateCourse: dayCount 를 현재 값보다 줄이면 COURSE_DAY_COUNT_INVALID — 늘어나기만 한다")
	void updateCourse_dayCount_belowCurrent_throws() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);
		given(course.getDayCount()).willReturn(3);
		CourseUpdateRequest request = mockUpdateRequest();
		given(request.getDayCount()).willReturn(2);
		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseStoreRepository.findMaxDayByCourseId(courseId)).willReturn(0);

		assertThatThrownBy(() -> courseCommandService.updateCourse(memberId, courseId, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_DAY_COUNT_INVALID.getMessage());
		then(course).should(never()).updateDayCount(any(Integer.class));
		then(courseRepository).should(never()).bumpVersion(any());
	}

	@Test
	@DisplayName("updateCourse: dayCount 가 가게가 있는 마지막 일차보다 작으면 COURSE_DAY_COUNT_INVALID")
	void updateCourse_dayCount_belowLastStoreDay_throws() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);
		given(course.getDayCount()).willReturn(1);
		CourseUpdateRequest request = mockUpdateRequest();
		given(request.getDayCount()).willReturn(2);
		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseStoreRepository.findMaxDayByCourseId(courseId)).willReturn(3);

		assertThatThrownBy(() -> courseCommandService.updateCourse(memberId, courseId, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_DAY_COUNT_INVALID.getMessage());
	}

	// ───────────────────────── 사람별 메모 ─────────────────────────

	private CourseStoreMemo realMemo(CourseStore courseStore, Long memberId, String content) {
		Member author = mockMember(memberId);
		return CourseStoreMemo.of(courseStore, author, content);
	}

	private CourseStore givenEditableStore(Long memberId, Long courseId, Long storeId) {
		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);
		CourseStore courseStore = mock(CourseStore.class);
		lenient().when(courseStore.getId()).thenReturn(501L);
		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseStoreRepository.findByCourseIdAndStoreIdAndDeletedAtIsNull(courseId, storeId))
			.willReturn(Optional.of(courseStore));
		return courseStore;
	}

	@Test
	@DisplayName("updateStore: 내 메모가 이미 있으면 내용만 바꾼다 — 새 행을 만들지 않는다")
	void updateStore_existingMemo_updatesContent() {
		Long memberId = 1L;
		Long courseId = 10L;
		Long storeId = 100L;
		CourseStore courseStore = givenEditableStore(memberId, courseId, storeId);
		CourseStoreMemo mine = realMemo(courseStore, memberId, "옛 메모");
		given(courseStoreMemoRepository.findByIdCourseStoreIdAndIdMemberId(501L, memberId))
			.willReturn(Optional.of(mine));
		CourseStoreUpdateRequest request = mock(CourseStoreUpdateRequest.class);
		given(request.getMemo()).willReturn("새 메모");

		courseCommandService.updateStore(memberId, courseId, storeId, request);

		assertThat(mine.getContent()).isEqualTo("새 메모");
		assertThat(mine.isDeleted()).isFalse();
		then(courseStoreMemoRepository).should(never()).save(any());
	}

	@Test
	@DisplayName("updateStore: 메모를 비우면 내 메모만 지운다 — 남의 메모는 건드리지 않는다")
	void updateStore_blankMemo_softDeletesMine() {
		Long memberId = 1L;
		Long courseId = 10L;
		Long storeId = 100L;
		CourseStore courseStore = givenEditableStore(memberId, courseId, storeId);
		CourseStoreMemo mine = realMemo(courseStore, memberId, "지울 메모");
		given(courseStoreMemoRepository.findByIdCourseStoreIdAndIdMemberId(501L, memberId))
			.willReturn(Optional.of(mine));
		CourseStoreUpdateRequest request = mock(CourseStoreUpdateRequest.class);
		given(request.getMemo()).willReturn("   ");

		courseCommandService.updateStore(memberId, courseId, storeId, request);

		assertThat(mine.isDeleted()).isTrue();
		then(courseStoreMemoRepository).should(never()).save(any());
		then(courseStoreMemoRepository).should(never()).softDeleteAllByCourseStoreId(any());
	}

	@Test
	@DisplayName("updateStore: 지웠던 내 메모에 다시 쓰면 같은 행을 되살린다")
	void updateStore_deletedMemo_restores() {
		Long memberId = 1L;
		Long courseId = 10L;
		Long storeId = 100L;
		CourseStore courseStore = givenEditableStore(memberId, courseId, storeId);
		CourseStoreMemo mine = realMemo(courseStore, memberId, "옛 메모");
		mine.softDelete();
		given(courseStoreMemoRepository.findByIdCourseStoreIdAndIdMemberId(501L, memberId))
			.willReturn(Optional.of(mine));
		CourseStoreUpdateRequest request = mock(CourseStoreUpdateRequest.class);
		given(request.getMemo()).willReturn("다시 쓴 메모");

		courseCommandService.updateStore(memberId, courseId, storeId, request);

		assertThat(mine.isDeleted()).isFalse();
		assertThat(mine.getContent()).isEqualTo("다시 쓴 메모");
		then(courseStoreMemoRepository).should(never()).save(any());
	}

	@Test
	@DisplayName("updateCourse: FORKED 코스가 원본과 메모만 다르면 여전히 COURSE_FORK_NOT_MODIFIED — 메모는 동일 판정에서 뺀다")
	void updateCourse_forkedSameAsSource_memoDiffers_stillNotModified() {
		Long memberId = 1L;
		Long courseId = 10L;
		Long sourceCourseId = 5L;
		Course course = mockCourse(courseId, "원본 제목", CourseType.FORKED, memberId, sourceCourseId);
		Course sourceCourse = mockCourse(sourceCourseId, "원본 제목", CourseType.CURATED, null, null);
		Store store = mockStore(100L);
		CourseStore current = mockCourseStore(1, 1, store);
		lenient().when(current.getMemo()).thenReturn("내 메모");
		CourseStore source = mockCourseStore(1, 1, store);
		CourseUpdateRequest request = mockUpdateRequest();
		given(request.getTitle()).willReturn("원본 제목");
		given(request.getIsPublic()).willReturn(true);
		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseRepository.findByIdAndDeletedAtIsNull(sourceCourseId)).willReturn(Optional.of(sourceCourse));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId))
			.willReturn(List.of(current));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(sourceCourseId))
			.willReturn(List.of(source));

		assertThatThrownBy(() -> courseCommandService.updateCourse(memberId, courseId, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORK_NOT_MODIFIED.getMessage());
	}

	// ───────────────────────── validateCanEdit ─────────────────────────
	// 큐레이션 코스는 member_id 가 NULL 이라 예전 validateOwner 가 우연히 막고 있었다.
	// 소유자 조건을 멤버까지 넓힌 뒤에도 막히는지 편집 경로 5개 전부에서 못 박는다.
	// 누가 validateCanEdit 의 CURATED 줄을 지우면 아래 5개가 동시에 빨간불이 된다.

	@Test
	@DisplayName("updateCourse: 큐레이션 코스는 course_members에 행이 있어도 편집이 막힌다")
	void updateCourse_curatedCourse_withMemberRow_stillForbidden() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "큐레이션", CourseType.CURATED, null, null);
		givenActiveMemberLenient(courseId, memberId);
		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));

		assertThatThrownBy(() -> courseCommandService.updateCourse(memberId, courseId, mockUpdateRequest()))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
		then(courseMemberRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("addStore: 큐레이션 코스는 course_members에 행이 있어도 편집이 막힌다")
	void addStore_curatedCourse_withMemberRow_stillForbidden() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "큐레이션", CourseType.CURATED, null, null);
		givenActiveMemberLenient(courseId, memberId);
		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));

		assertThatThrownBy(() -> courseCommandService.addStore(memberId, courseId, mock(CourseStoreAddRequest.class)))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
		then(courseMemberRepository).shouldHaveNoInteractions();
		then(courseStoreRepository).should(never()).save(any());
	}

	@Test
	@DisplayName("updateStore: 큐레이션 코스는 course_members에 행이 있어도 편집이 막힌다")
	void updateStore_curatedCourse_withMemberRow_stillForbidden() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "큐레이션", CourseType.CURATED, null, null);
		givenActiveMemberLenient(courseId, memberId);
		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));

		assertThatThrownBy(() -> courseCommandService.updateStore(
			memberId, courseId, 100L, mock(CourseStoreUpdateRequest.class)))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
		then(courseMemberRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("removeStore: 큐레이션 코스는 course_members에 행이 있어도 편집이 막힌다")
	void removeStore_curatedCourse_withMemberRow_stillForbidden() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "큐레이션", CourseType.CURATED, null, null);
		givenActiveMemberLenient(courseId, memberId);
		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));

		assertThatThrownBy(() -> courseCommandService.removeStore(memberId, courseId, 100L))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
		then(courseMemberRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("reorderStores: 큐레이션 코스는 course_members에 행이 있어도 편집이 막힌다")
	void reorderStores_curatedCourse_withMemberRow_stillForbidden() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "큐레이션", CourseType.CURATED, null, null);
		givenActiveMemberLenient(courseId, memberId);
		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));

		assertThatThrownBy(() -> courseCommandService.reorderStores(
			memberId, courseId, mock(CourseStoreReorderRequest.class)))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
		then(courseMemberRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("updateStore: 방장이 아니어도 활성 멤버면 가게를 수정할 수 있다")
	void updateStore_activeMember_success() {
		Long ownerId = 1L;
		Long memberId = 2L;
		Long courseId = 10L;
		Long storeId = 100L;
		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, ownerId, null);
		CourseStore courseStore = mock(CourseStore.class);
		CourseStoreUpdateRequest request = mock(CourseStoreUpdateRequest.class);
		given(request.getMemo()).willReturn("멤버 메모");
		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseMemberRepository.existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(courseId, memberId))
			.willReturn(true);
		given(courseStoreRepository.findByCourseIdAndStoreIdAndDeletedAtIsNull(courseId, storeId))
			.willReturn(Optional.of(courseStore));
		Member author = mockMember(memberId);
		given(memberRepository.getReferenceById(memberId)).willReturn(author);

		courseCommandService.updateStore(memberId, courseId, storeId, request);

		then(courseStoreMemoRepository).should().save(any(CourseStoreMemo.class));
		then(courseRepository).should().bumpVersion(courseId);
	}

	@Test
	@DisplayName("removeStore: 나간 멤버(비활성 행)는 가게를 지울 수 없다")
	void removeStore_leftMember_throwsForbidden() {
		Long ownerId = 1L;
		Long memberId = 2L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, ownerId, null);
		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseMemberRepository.existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(courseId, memberId))
			.willReturn(false);

		assertThatThrownBy(() -> courseCommandService.removeStore(memberId, courseId, 100L))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
		then(courseRepository).should(never()).bumpVersion(any());
	}

	@Test
	@DisplayName("updateCourse: 활성 멤버는 제목을 바꿀 수 있다")
	void updateCourse_activeMember_canEditTitle() {
		Long ownerId = 1L;
		Long memberId = 2L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "원래 제목", CourseType.CUSTOM, ownerId, null);
		CourseUpdateRequest request = mockUpdateRequest();
		given(request.getTitle()).willReturn("멤버가 고친 제목");
		// Mockito 는 Boolean 미스텁에 false 를 돌려준다 — 공개 여부를 안 보낸 요청은 null 이어야 한다
		given(request.getIsPublic()).willReturn(null);
		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseMemberRepository.existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(courseId, memberId))
			.willReturn(true);

		courseCommandService.updateCourse(memberId, courseId, request);

		then(course).should().updateTitle("멤버가 고친 제목");
		then(courseRepository).should().bumpVersion(courseId);
	}

	@Test
	@DisplayName("updateCourse: 활성 멤버라도 공개/비공개 전환은 방장 전용이라 COURSE_FORBIDDEN을 던진다")
	void updateCourse_activeMember_cannotChangePublic() {
		Long ownerId = 1L;
		Long memberId = 2L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, ownerId, null);
		CourseUpdateRequest request = mockUpdateRequest();
		given(request.getIsPublic()).willReturn(true);
		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseMemberRepository.existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(courseId, memberId))
			.willReturn(true);

		assertThatThrownBy(() -> courseCommandService.updateCourse(memberId, courseId, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
		then(course).should(never()).updateVisibility(any());
	}

	@Test
	@DisplayName("updateStore: 쓰기 제한(밴) 회원은 메모를 못 쓴다 — 일행에게 보이는 글이다")
	void updateStore_restrictedMember_cannotWriteMemo() {
		Long memberId = 1L;
		Long courseId = 10L;
		Long storeId = 100L;
		givenEditableStore(memberId, courseId, storeId);
		given(courseStoreMemoRepository.findByIdCourseStoreIdAndIdMemberId(501L, memberId))
			.willReturn(Optional.empty());
		willThrow(new MemberException(MemberErrorCode.MEMBER_WRITE_RESTRICTED))
			.given(memberRestrictionReader).validateCanWrite(memberId);
		CourseStoreUpdateRequest request = mock(CourseStoreUpdateRequest.class);
		given(request.getMemo()).willReturn("남에게 보이는 메모");

		assertThatThrownBy(() -> courseCommandService.updateStore(memberId, courseId, storeId, request))
			.isInstanceOf(MemberException.class);
		then(courseStoreMemoRepository).should(never()).save(any());
		then(courseRepository).should(never()).bumpVersion(any());
	}

	@Test
	@DisplayName("updateStore: 쓰기 제한 회원도 자기 메모를 지우는 건 된다 — 지우기는 남에게 보이는 글이 아니다")
	void updateStore_restrictedMember_canDeleteOwnMemo() {
		Long memberId = 1L;
		Long courseId = 10L;
		Long storeId = 100L;
		CourseStore courseStore = givenEditableStore(memberId, courseId, storeId);
		CourseStoreMemo mine = realMemo(courseStore, memberId, "지울 메모");
		given(courseStoreMemoRepository.findByIdCourseStoreIdAndIdMemberId(501L, memberId))
			.willReturn(Optional.of(mine));
		CourseStoreUpdateRequest request = mock(CourseStoreUpdateRequest.class);
		given(request.getMemo()).willReturn("");

		courseCommandService.updateStore(memberId, courseId, storeId, request);

		assertThat(mine.isDeleted()).isTrue();
		then(memberRestrictionReader).shouldHaveNoInteractions();
	}

	// ───────────────────────── updateCourse: 링크 공개(visibility) ─────────────────────────

	@Test
	@DisplayName("updateCourse: 링크 공개(UNLISTED)로 처음 바꾸면 공유 토큰을 발급하고 방장에게 돌려준다")
	void updateCourse_toUnlisted_issuesShareToken() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);
		CourseUpdateRequest request = mockUpdateRequest();
		given(request.getVisibility()).willReturn(CourseVisibility.UNLISTED);
		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		// 발급 전엔 null, 발급 뒤엔 토큰
		given(course.getShareToken()).willReturn(null).willReturn("issued-token");
		given(course.getVisibility()).willReturn(CourseVisibility.UNLISTED);

		CourseUpdateResponse response = courseCommandService.updateCourse(memberId, courseId, request);

		then(course).should().updateVisibility(CourseVisibility.UNLISTED);
		then(course).should().issueShareToken(argThat(token -> token.length() == CollabTokenGenerator.TOKEN_LENGTH));
		assertThat(response.visibility()).isEqualTo(CourseVisibility.UNLISTED);
		assertThat(response.shareToken()).isEqualTo("issued-token");
		// 협업 여부는 PUBLIC 으로 바꿀 때만 본다 — 링크 공개는 같이 편집 중이어도 된다
		then(courseMemberRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("updateCourse: 토큰이 이미 있으면 다시 발급하지 않는다 — PUBLIC·PRIVATE 을 오가도 링크가 살아 있다")
	void updateCourse_toUnlistedAgain_keepsShareToken() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);
		CourseUpdateRequest request = mockUpdateRequest();
		given(request.getVisibility()).willReturn(CourseVisibility.UNLISTED);
		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(course.getShareToken()).willReturn("kept-token");
		given(course.getVisibility()).willReturn(CourseVisibility.UNLISTED);

		CourseUpdateResponse response = courseCommandService.updateCourse(memberId, courseId, request);

		then(course).should(never()).issueShareToken(any());
		assertThat(response.shareToken()).isEqualTo("kept-token");
	}

	@Test
	@DisplayName("updateCourse: visibility 와 isPublic 이 같이 오면 visibility 가 이긴다")
	void updateCourse_visibilityWinsOverIsPublic() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);
		CourseUpdateRequest request = mockUpdateRequest();
		lenient().when(request.getIsPublic()).thenReturn(true);
		given(request.getVisibility()).willReturn(CourseVisibility.PRIVATE);
		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));

		courseCommandService.updateCourse(memberId, courseId, request);

		then(course).should().updateVisibility(CourseVisibility.PRIVATE);
		then(courseMemberRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("updateCourse: 활성 멤버는 visibility 를 못 바꾼다 — 방장 전용")
	void updateCourse_activeMember_cannotChangeVisibility() {
		Long ownerId = 1L;
		Long memberId = 2L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, ownerId, null);
		CourseUpdateRequest request = mockUpdateRequest();
		given(request.getVisibility()).willReturn(CourseVisibility.UNLISTED);
		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseMemberRepository.existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(courseId, memberId))
			.willReturn(true);

		assertThatThrownBy(() -> courseCommandService.updateCourse(memberId, courseId, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
		then(course).should(never()).updateVisibility(any());
	}

	@Test
	@DisplayName("updateCourse: 멤버가 제목만 고치면 응답에 공유 토큰이 실리지 않는다")
	void updateCourse_member_responseHidesShareToken() {
		Long ownerId = 1L;
		Long memberId = 2L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, ownerId, null);
		lenient().when(course.getVisibility()).thenReturn(CourseVisibility.UNLISTED);
		lenient().when(course.getShareToken()).thenReturn("secret");
		CourseUpdateRequest request = mockUpdateRequest();
		given(request.getTitle()).willReturn("멤버가 고친 제목");
		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseMemberRepository.existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(courseId, memberId))
			.willReturn(true);

		CourseUpdateResponse response = courseCommandService.updateCourse(memberId, courseId, request);

		assertThat(response.visibility()).isEqualTo(CourseVisibility.UNLISTED);
		assertThat(response.shareToken()).isNull();
	}

	// ───────────────────────── helpers ─────────────────────────

	/** Mockito 는 Integer/Boolean 미스텁에 0/false 를 돌려준다 — "안 보낸 필드"는 null 이어야 하므로 기본 스텁을 둔다. */
	private CourseUpdateRequest mockUpdateRequest() {
		CourseUpdateRequest request = mock(CourseUpdateRequest.class);
		lenient().when(request.getIsPublic()).thenReturn(null);
		lenient().when(request.getDayCount()).thenReturn(null);
		return request;
	}

	/** CURATED 검사가 멤버 조회보다 먼저라 이 스텁은 안 불려야 정상이다 — 그래서 lenient. */
	private void givenActiveMemberLenient(Long courseId, Long memberId) {
		lenient().when(courseMemberRepository.existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(courseId, memberId))
			.thenReturn(true);
	}

	private Member mockMember(Long memberId) {
		Member member = mock(Member.class);
		lenient().when(member.getId()).thenReturn(memberId);
		return member;
	}

	private Course mockCourse(Long id, String title, CourseType type, Long memberId, Long sourceCourseId) {
		Course course = mock(Course.class);
		lenient().when(course.getId()).thenReturn(id);
		lenient().when(course.getTitle()).thenReturn(title);
		lenient().when(course.getType()).thenReturn(type);
		lenient().when(course.getSourceCourseId()).thenReturn(sourceCourseId);
		if (memberId != null) {
			Member member = mockMember(memberId);
			lenient().when(course.getMember()).thenReturn(member);
		} else {
			lenient().when(course.getMember()).thenReturn(null);
		}
		return course;
	}

	private Store mockStore(Long storeId) {
		Store store = mock(Store.class);
		lenient().when(store.getId()).thenReturn(storeId);
		Point location = mock(Point.class);
		lenient().when(store.getLocation()).thenReturn(location);
		lenient().when(location.getY()).thenReturn(37.5);
		lenient().when(location.getX()).thenReturn(126.9);
		lenient().when(store.getName()).thenReturn("테스트 빵집");
		lenient().when(store.getRegion2nd()).thenReturn("마포구");
		return store;
	}

	private CourseStore mockCourseStore(int visitOrder, Store store) {
		return mockCourseStore(visitOrder, 1, store);
	}

	private CourseStore mockCourseStore(int visitOrder, int day, Store store) {
		CourseStore courseStore = mock(CourseStore.class);
		lenient().when(courseStore.getStore()).thenReturn(store);
		lenient().when(courseStore.getVisitOrder()).thenReturn(visitOrder);
		lenient().when(courseStore.getDay()).thenReturn(day);
		lenient().when(courseStore.getMemo()).thenReturn(null);
		lenient().when(courseStore.getVisitTime()).thenReturn(null);
		return courseStore;
	}
}
