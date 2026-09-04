package sweetgirl.croissant.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Point;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.dto.projection.CourseStoreSummaryProjection;
import sweetgirl.croissant.course.dto.response.CourseDetailLookup;
import sweetgirl.croissant.course.dto.response.CourseDetailResponse;
import sweetgirl.croissant.course.dto.response.CourseMemberResponse;
import sweetgirl.croissant.course.dto.response.CourseStoreMemoResponse;
import sweetgirl.croissant.course.dto.response.CourseStoreResponse;
import sweetgirl.croissant.course.dto.response.CourseSummaryResponse;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseImageFile;
import sweetgirl.croissant.course.entity.CourseMember;
import sweetgirl.croissant.course.entity.CourseMemberId;
import sweetgirl.croissant.course.entity.CourseStore;
import sweetgirl.croissant.course.entity.CourseStoreMemo;
import sweetgirl.croissant.course.entity.CourseStoreMemoId;
import sweetgirl.croissant.course.entity.SavedCourse;
import sweetgirl.croissant.course.enums.CourseProgressStatus;
import sweetgirl.croissant.course.enums.CourseType;
import sweetgirl.croissant.course.enums.CourseVisibility;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.repository.CourseImageFileRepository;
import sweetgirl.croissant.course.repository.CourseMemberRepository;
import sweetgirl.croissant.course.repository.CourseProgressRepository;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.CourseStoreMemoRepository;
import sweetgirl.croissant.course.repository.CourseStoreRepository;
import sweetgirl.croissant.course.repository.CourseStoreVisitRepository;
import sweetgirl.croissant.course.repository.SavedCourseRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.store.entity.Store;

@ExtendWith(MockitoExtension.class)
class CourseQueryServiceTest {

	@Mock
	CourseRepository courseRepository;
	@Mock
	CourseStoreRepository courseStoreRepository;
	@Mock
	SavedCourseRepository savedCourseRepository;
	@Mock
	CourseImageFileRepository courseImageFileRepository;
	@Mock
	CourseProgressRepository courseProgressRepository;
	@Mock
	CourseStoreThumbnailReader courseStoreThumbnailReader;
	@Mock
	CourseStoreVisitRepository courseStoreVisitRepository;
	@Mock
	CourseStoreMemoRepository courseStoreMemoRepository;
	@Mock
	CourseMemberRepository courseMemberRepository;

	@InjectMocks
	CourseQueryService courseQueryService;

	/** 권한 판정은 CourseAccessPolicy 로 갔지만 근거는 같은 courseMemberRepository 다 — 실체를 넣어 기존 스텁이 그대로 산다. */
	@BeforeEach
	void injectAccessPolicy() {
		ReflectionTestUtils.setField(courseQueryService, "courseAccessPolicy",
			new CourseAccessPolicy(courseMemberRepository));
	}

	// ───────────────────────── getCurationCourses ─────────────────────────

	@Test
	@DisplayName("getCurationCourses: 공개 코스 목록을 반환한다")
	void getCurationCourses_success() {
		Course course = mockCourse(1L, "망원동 빵지순례", true, CourseType.CURATED, null);
		// 목록은 Store 엔티티가 아니라 프로젝션(가게 id·이름·지역)만 읽는다
		CourseStoreSummaryProjection firstStop = mockStop(1L, 101L, "빵집A", "마포구");
		CourseStoreSummaryProjection secondStop = mockStop(1L, 102L, "빵집B", "마포구");

		given(courseRepository.findAllPublicOrderedByPinAndSavedCount()).willReturn(List.of(course));
		given(courseStoreRepository.findSummariesByCourseIdIn(List.of(1L))).willReturn(List.of(firstStop, secondStop));
		given(courseStoreThumbnailReader.readByCourse(Map.of(1L, List.of(101L, 102L))))
			.willReturn(Map.of(1L, List.of("s101-320")));

		List<CourseSummaryResponse> result = courseQueryService.getCurationCourses();

		assertThat(result).hasSize(1);
		assertThat(result.get(0).id()).isEqualTo(1L);
		assertThat(result.get(0).title()).isEqualTo("망원동 빵지순례");
		assertThat(result.get(0).storeCount()).isEqualTo(2);
		assertThat(result.get(0).storeIds()).containsExactly(101L, 102L);
		assertThat(result.get(0).storeNames()).containsExactly("빵집A", "빵집B");
		assertThat(result.get(0).regions()).containsExactly("마포구");
		assertThat(result.get(0).storeThumbnailKeys()).containsExactly("s101-320");
	}

	@Test
	@DisplayName("getCurationCourses: 핀 우선, 그 외에는 찜 수 내림차순으로 정렬한다")
	void getCurationCourses_sortsByPinThenSavedCount() {
		Course lessSaved = mockCourse(1L, "찜 적은 코스", true, CourseType.CUSTOM, 10L);
		Course mostSaved = mockCourse(2L, "찜 많은 코스", true, CourseType.CUSTOM, 20L);
		Course pinnedThird = mockCourse(3L, "세 번째 핀 코스", true, CourseType.CURATED, null);
		Course pinnedFirst = mockCourse(4L, "첫 번째 핀 코스", true, CourseType.CURATED, null);
		Course pinnedSecond = mockCourse(5L, "두 번째 핀 코스", true, CourseType.CURATED, null);
		given(pinnedThird.getPinOrder()).willReturn(3);
		given(pinnedFirst.getPinOrder()).willReturn(1);
		given(pinnedSecond.getPinOrder()).willReturn(2);

		given(courseRepository.findAllPublicOrderedByPinAndSavedCount())
			.willReturn(List.of(pinnedFirst, pinnedSecond, pinnedThird, mostSaved, lessSaved));
		given(savedCourseRepository.countGroupedByCourseIdIn(List.of(4L, 5L, 3L, 2L, 1L)))
			.willReturn(List.of(new Object[] {1L, 1L}, new Object[] {2L, 5L}));

		List<CourseSummaryResponse> result = courseQueryService.getCurationCourses();

		assertThat(result).extracting(CourseSummaryResponse::id).containsExactly(4L, 5L, 3L, 2L, 1L);
		assertThat(result).extracting(CourseSummaryResponse::pinOrder).containsExactly(1, 2, 3, null, null);
	}

	// ───────────────────────── getMyCourses ─────────────────────────

	@Test
	@DisplayName("getMyCourses: CUSTOM + FORKED 내 코스 목록을 최근 수정순으로 반환한다")
	void getMyCourses_success() {
		Long memberId = 10L;
		Course custom = mockCourse(1L, "나의 코스", true, CourseType.CUSTOM, memberId);
		Course forked = mockCourse(2L, "copy of 큐레이션", false, CourseType.FORKED, memberId);
		lenient().when(custom.getUpdatedAt()).thenReturn(LocalDateTime.of(2026, 9, 3, 12, 0));
		lenient().when(forked.getUpdatedAt()).thenReturn(LocalDateTime.of(2026, 9, 1, 12, 0));

		given(courseRepository.findAllByMemberIdAndTypeInAndDeletedAtIsNull(
			memberId, List.of(CourseType.CUSTOM, CourseType.FORKED)))
			.willReturn(List.of(forked, custom));

		List<CourseSummaryResponse> result = courseQueryService.getMyCourses(memberId);

		assertThat(result).hasSize(2);
		assertThat(result.get(0).courseType()).isEqualTo(CourseType.CUSTOM);
		assertThat(result.get(1).courseType()).isEqualTo(CourseType.FORKED);
		assertThat(result).extracting(CourseSummaryResponse::isMember).containsOnly(false);
	}

	@Test
	@DisplayName("getMyCourses: 참여 코스를 합치고, 협업 중인 내 코스는 한 번만, 섞어서 최근 수정순 — 참여 코스만 isMember")
	void getMyCourses_unionsJoinedCourses_dedupsAndSortsByUpdatedAt() {
		Long memberId = 10L;
		Long friendId = 20L;
		Course mine = mockCourse(1L, "내 코스", false, CourseType.CUSTOM, memberId);
		Course myCollab = mockCourse(2L, "내 협업 코스", false, CourseType.CUSTOM, memberId);
		Course friends = mockCourse(3L, "친구 코스", false, CourseType.CUSTOM, friendId);
		lenient().when(mine.getUpdatedAt()).thenReturn(LocalDateTime.of(2026, 9, 1, 12, 0));
		lenient().when(myCollab.getUpdatedAt()).thenReturn(LocalDateTime.of(2026, 9, 3, 12, 0));
		lenient().when(friends.getUpdatedAt()).thenReturn(LocalDateTime.of(2026, 9, 2, 12, 0));
		// 방장 행(내 협업 코스) + 멤버 행(친구 코스)
		CourseMember ownerRow = mockMemberRow(2L, memberId, "다나");
		CourseMember memberRow = mockMemberRow(3L, memberId, "다나");
		lenient().when(ownerRow.getCourse()).thenReturn(myCollab);
		lenient().when(memberRow.getCourse()).thenReturn(friends);

		given(courseRepository.findAllByMemberIdAndTypeInAndDeletedAtIsNull(
			memberId, List.of(CourseType.CUSTOM, CourseType.FORKED)))
			.willReturn(List.of(mine, myCollab));
		given(courseMemberRepository.findAllByIdMemberIdAndDeletedAtIsNull(memberId))
			.willReturn(List.of(ownerRow, memberRow));

		List<CourseSummaryResponse> result = courseQueryService.getMyCourses(memberId);

		assertThat(result).extracting(CourseSummaryResponse::id).containsExactly(2L, 3L, 1L);
		assertThat(result).extracting(CourseSummaryResponse::isMember).containsExactly(false, true, false);
	}

	@Test
	@DisplayName("getMyCourses: 지운 참여 코스는 빠진다")
	void getMyCourses_excludesDeletedJoinedCourse() {
		Long memberId = 10L;
		Course deleted = mock(Course.class);
		given(deleted.isDeleted()).willReturn(true);
		CourseMember memberRow = mock(CourseMember.class);
		given(memberRow.getCourse()).willReturn(deleted);

		given(courseRepository.findAllByMemberIdAndTypeInAndDeletedAtIsNull(
			memberId, List.of(CourseType.CUSTOM, CourseType.FORKED)))
			.willReturn(List.of());
		given(courseMemberRepository.findAllByIdMemberIdAndDeletedAtIsNull(memberId))
			.willReturn(List.of(memberRow));

		assertThat(courseQueryService.getMyCourses(memberId)).isEmpty();
	}

	// ───────────────────────── getSavedCourses ─────────────────────────

	@Test
	@DisplayName("getSavedCourses: 찜한 코스 목록을 반환하며 삭제된 코스는 제외한다")
	void getSavedCourses_success() {
		Long memberId = 10L;
		Course activeCourse = mockCourse(1L, "찜한 코스", true, CourseType.CURATED, null);

		Course deletedCourse = mock(Course.class);
		given(deletedCourse.isDeleted()).willReturn(true);

		SavedCourse saved1 = mock(SavedCourse.class);
		given(saved1.getCourse()).willReturn(activeCourse);
		SavedCourse saved2 = mock(SavedCourse.class);
		given(saved2.getCourse()).willReturn(deletedCourse);

		given(savedCourseRepository.findAllByMemberId(memberId)).willReturn(List.of(saved1, saved2));

		List<CourseSummaryResponse> result = courseQueryService.getSavedCourses(memberId);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).id()).isEqualTo(1L);
	}

	@Test
	@DisplayName("getSavedCourses: 비공개로 전환된 코스는 목록에서 제외한다")
	void getSavedCourses_excludesPrivateCourse() {
		Long memberId = 10L;
		Course publicCourse = mockCourse(1L, "공개 코스", true, CourseType.CURATED, null);
		// 필터로 걸러지는 코스는 isPublic만 조회됨 — 나머지 스텁은 strict stubbing에 걸리므로 최소 mock 사용
		Course privateCourse = mock(Course.class);
		given(privateCourse.isPublic()).willReturn(false);

		SavedCourse saved1 = mock(SavedCourse.class);
		given(saved1.getCourse()).willReturn(publicCourse);
		SavedCourse saved2 = mock(SavedCourse.class);
		given(saved2.getCourse()).willReturn(privateCourse);

		given(savedCourseRepository.findAllByMemberId(memberId)).willReturn(List.of(saved1, saved2));

		List<CourseSummaryResponse> result = courseQueryService.getSavedCourses(memberId);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).id()).isEqualTo(1L);
	}

	@Test
	@DisplayName("getSavedCourses: 참여 중인 코스는 찜해 뒀어도 목록에서 뺀다 — 내 빵투어에 '함께' 배지로 뜬다")
	void getSavedCourses_excludesJoinedCourse() {
		Long memberId = 10L;
		Course publicCourse = mockCourse(1L, "공개 코스", true, CourseType.CURATED, null);
		Course joinedCourse = mock(Course.class);
		given(joinedCourse.isPublic()).willReturn(true);
		given(joinedCourse.getId()).willReturn(2L);
		SavedCourse saved1 = mock(SavedCourse.class);
		given(saved1.getCourse()).willReturn(publicCourse);
		SavedCourse saved2 = mock(SavedCourse.class);
		given(saved2.getCourse()).willReturn(joinedCourse);
		given(savedCourseRepository.findAllByMemberId(memberId)).willReturn(List.of(saved1, saved2));
		given(courseMemberRepository.findActiveCourseIdsByMemberId(memberId)).willReturn(List.of(2L));

		List<CourseSummaryResponse> result = courseQueryService.getSavedCourses(memberId);

		assertThat(result).extracting(CourseSummaryResponse::id).containsExactly(1L);
	}

	// ───────────────────────── getCourseDetail ─────────────────────────

	@Test
	@DisplayName("getCourseDetail: 공개 코스를 비로그인으로 조회하면 성공한다")
	void getCourseDetail_publicCourse_notLoggedIn_success() {
		Course course = mockCourse(1L, "공개 코스", true, CourseType.CURATED, null);
		CourseStore courseStore = mockCourseStore(1, "빵집A", "종로구");
		CourseImageFile imageFile = mock(CourseImageFile.class);
		given(imageFile.getAwsKey()).willReturn("courses/1/cover.jpg");

		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(1L))
			.willReturn(List.of(courseStore));
		given(courseImageFileRepository.findByCourseId(1L)).willReturn(Optional.of(imageFile));
		given(savedCourseRepository.countByCourseId(1L)).willReturn(5L);
		given(courseProgressRepository.countByCourseIdAndStatus(1L, CourseProgressStatus.FINISHED)).willReturn(37L);

		CourseDetailResponse result = courseQueryService.getCourseDetail(1L, null, null);

		assertThat(result.id()).isEqualTo(1L);
		assertThat(result.isPublic()).isTrue();
		assertThat(result.isSaved()).isFalse();
		assertThat(result.savedCount()).isEqualTo(5L);
		assertThat(result.finisherCount()).isEqualTo(37L);
		assertThat(result.imageKey()).isEqualTo("courses/1/cover.jpg");
		assertThat(result.courseStores()).hasSize(1);
		assertThat(result.regions()).containsExactly("종로구");
	}

	@Test
	@DisplayName("getCourseDetail: 비공개 코스를 소유자가 조회하면 성공한다")
	void getCourseDetail_privateCourse_owner_success() {
		Long memberId = 10L;
		Course course = mockCourse(1L, "비공개 코스", false, CourseType.CUSTOM, memberId);

		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(1L))
			.willReturn(List.of());
		given(courseImageFileRepository.findByCourseId(1L)).willReturn(Optional.empty());
		given(savedCourseRepository.existsByMemberIdAndCourseId(memberId, 1L)).willReturn(false);
		given(savedCourseRepository.countByCourseId(1L)).willReturn(0L);

		CourseDetailResponse result = courseQueryService.getCourseDetail(1L, memberId, null);

		assertThat(result.id()).isEqualTo(1L);
		assertThat(result.isPublic()).isFalse();
	}

	@Test
	@DisplayName("getCourseDetail: 비공개 코스를 타인이 조회하면 COURSE_FORBIDDEN을 던진다")
	void getCourseDetail_privateCourse_otherMember_throwsForbidden() {
		Long ownerId = 10L;
		Long otherId = 99L;

		Course course = mock(Course.class);
		Member owner = mock(Member.class);
		given(owner.getId()).willReturn(ownerId);
		given(course.isPublic()).willReturn(false);
		given(course.getMember()).willReturn(owner);
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));

		assertThatThrownBy(() -> courseQueryService.getCourseDetail(1L, otherId, null))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
	}

	@Test
	@DisplayName("getCourseDetail: 비공개 협업 코스를 활성 멤버가 보면 성공 — canEdit·isMember 는 true, isMine·isOwner 는 false")
	void getCourseDetail_privateCourse_activeMember_success() {
		Long ownerId = 10L;
		Long memberId = 5L;
		Course course = mockCourse(1L, "협업 코스", false, CourseType.CUSTOM, ownerId);
		CourseMember ownerRow = mockMemberRow(1L, ownerId, "다나");
		CourseMember myRow = mockMemberRow(1L, memberId, "지수");

		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));
		given(courseMemberRepository.existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(1L, memberId)).willReturn(true);
		given(courseMemberRepository.findAllByIdCourseIdAndDeletedAtIsNullOrderByJoinedAtAsc(1L))
			.willReturn(List.of(ownerRow, myRow));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(1L))
			.willReturn(List.of());
		given(courseImageFileRepository.findByCourseId(1L)).willReturn(Optional.empty());
		given(savedCourseRepository.countByCourseId(1L)).willReturn(0L);

		CourseDetailResponse result = courseQueryService.getCourseDetail(1L, memberId, null);

		assertThat(result.isMine()).isFalse();
		assertThat(result.isOwner()).isFalse();
		assertThat(result.isMember()).isTrue();
		assertThat(result.canEdit()).isTrue();
		assertThat(result.memberCount()).isEqualTo(2);
		assertThat(result.myDisplayName()).isEqualTo("지수");
		// 참여 순서 그대로, 방장·나 표시
		assertThat(result.members()).extracting(CourseMemberResponse::memberId).containsExactly(ownerId, memberId);
		assertThat(result.members()).extracting(CourseMemberResponse::isOwner).containsExactly(true, false);
		assertThat(result.members()).extracting(CourseMemberResponse::isMe).containsExactly(false, true);
		assertThat(result.visibility()).isEqualTo(CourseVisibility.PRIVATE);
		assertThat(result.shareToken()).isNull();
	}

	@Test
	@DisplayName("getCourseDetail: 방장이 보면 isOwner·canEdit 가 true 이고 isMember 는 false, 내 이름은 방장 행에서")
	void getCourseDetail_privateCourse_owner_collabFlags() {
		Long ownerId = 10L;
		Long memberId = 5L;
		Course course = mockCourse(1L, "협업 코스", false, CourseType.CUSTOM, ownerId);
		given(course.getVersion()).willReturn(41L);
		given(course.getDayCount()).willReturn(3);
		CourseMember ownerRow = mockMemberRow(1L, ownerId, "다나");
		CourseMember memberRow = mockMemberRow(1L, memberId, "지수");

		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));
		given(courseMemberRepository.findAllByIdCourseIdAndDeletedAtIsNullOrderByJoinedAtAsc(1L))
			.willReturn(List.of(ownerRow, memberRow));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(1L))
			.willReturn(List.of());
		given(courseImageFileRepository.findByCourseId(1L)).willReturn(Optional.empty());
		given(savedCourseRepository.countByCourseId(1L)).willReturn(0L);

		CourseDetailResponse result = courseQueryService.getCourseDetail(1L, ownerId, null);

		assertThat(result.isMine()).isTrue();
		assertThat(result.isOwner()).isTrue();
		assertThat(result.isMember()).isFalse();
		assertThat(result.canEdit()).isTrue();
		assertThat(result.myDisplayName()).isEqualTo("다나");
		assertThat(result.version()).isEqualTo(41L);
		assertThat(result.dayCount()).isEqualTo(3);
	}

	@Test
	@DisplayName("getCourseDetail: 비공개 협업 코스를 나갔던 사람이나 제3자가 보면 COURSE_FORBIDDEN")
	void getCourseDetail_privateCourse_nonMember_throwsForbidden() {
		Long ownerId = 10L;
		// 접근 판정에서 끝나는 코스는 제목·배지가 안 읽혀 strict stubbing 에 걸린다 — 최소 mock
		Course course = mock(Course.class);
		Member owner = mock(Member.class);
		given(owner.getId()).willReturn(ownerId);
		given(course.getId()).willReturn(1L);
		given(course.isPublic()).willReturn(false);
		given(course.getMember()).willReturn(owner);
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));
		// 활성 행이 없다 = 나갔거나 애초에 멤버가 아니다. 판정은 exists 로 끝나고 참여자 목록(과 type)은 읽지 않는다
		given(courseMemberRepository.existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(1L, 99L)).willReturn(false);

		assertThatThrownBy(() -> courseQueryService.getCourseDetail(1L, 99L, null))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
		then(courseMemberRepository).should(never()).findAllByIdCourseIdAndDeletedAtIsNullOrderByJoinedAtAsc(any());
	}

	@Test
	@DisplayName("getCourseDetail: 협업이 아닌 내 코스는 members 가 비고 memberCount 0, myDisplayName null")
	void getCourseDetail_noCollab_emptyMembers() {
		Long ownerId = 10L;
		Course course = mockCourse(1L, "내 코스", false, CourseType.CUSTOM, ownerId);
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));
		given(courseMemberRepository.findAllByIdCourseIdAndDeletedAtIsNullOrderByJoinedAtAsc(1L))
			.willReturn(List.of());
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(1L))
			.willReturn(List.of());
		given(courseImageFileRepository.findByCourseId(1L)).willReturn(Optional.empty());
		given(savedCourseRepository.countByCourseId(1L)).willReturn(0L);

		CourseDetailResponse result = courseQueryService.getCourseDetail(1L, ownerId, null);

		assertThat(result.canEdit()).isTrue();
		assertThat(result.memberCount()).isZero();
		assertThat(result.members()).isEmpty();
		assertThat(result.myDisplayName()).isNull();
	}

	@Test
	@DisplayName("getCourseDetail: 큐레이션 코스는 canEdit 가 항상 false 이고 참여자 테이블을 읽지 않는다")
	void getCourseDetail_curated_neverEditable() {
		Course course = mockCourse(1L, "큐레이션", true, CourseType.CURATED, null);
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(1L))
			.willReturn(List.of());
		given(courseImageFileRepository.findByCourseId(1L)).willReturn(Optional.empty());
		given(savedCourseRepository.countByCourseId(1L)).willReturn(0L);

		CourseDetailResponse result = courseQueryService.getCourseDetail(1L, 10L, null);

		assertThat(result.canEdit()).isFalse();
		assertThat(result.isOwner()).isFalse();
		assertThat(result.visibility()).isEqualTo(CourseVisibility.PUBLIC);
		then(courseMemberRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("getCourseDetail: 소유자가 없는 비공개 코스는 로그인해도 COURSE_FORBIDDEN을 던진다")
	void getCourseDetail_privateCourseWithoutOwner_throwsForbidden() {
		Course course = mock(Course.class);
		given(course.isPublic()).willReturn(false);
		given(course.getMember()).willReturn(null);
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));

		assertThatThrownBy(() -> courseQueryService.getCourseDetail(1L, 10L, null))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
	}

	@Test
	@DisplayName("getCourseDetail: 삭제된 코스를 조회하면 COURSE_NOT_FOUND를 던진다")
	void getCourseDetail_deletedCourse_throwsNotFound() {
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> courseQueryService.getCourseDetail(1L, null, null))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_NOT_FOUND.getMessage());
	}

	// ───────────────────────── getCourseDetail: 링크 공개(UNLISTED) ─────────────────────────

	/** 상세를 끝까지 만드는 케이스용 — mockCourse 의 strict 스텁(제목·배지·닉네임)이 전부 읽힌다 */
	private Course unlistedCourse(Long ownerId, String shareToken) {
		Course course = mockCourse(1L, "링크 공개 코스", false, CourseType.CUSTOM, ownerId);
		lenient().when(course.getVisibility()).thenReturn(CourseVisibility.UNLISTED);
		lenient().when(course.getShareToken()).thenReturn(shareToken);
		return course;
	}

	/** 접근 판정에서 끝나는 케이스용 — 판정에 필요한 것만 스텁(strict stubbing 회피) */
	private Course minimalCourse(CourseVisibility visibility, Long ownerId, String shareToken) {
		Course course = mock(Course.class);
		Member owner = mock(Member.class);
		lenient().when(owner.getId()).thenReturn(ownerId);
		lenient().when(course.getMember()).thenReturn(owner);
		lenient().when(course.getType()).thenReturn(CourseType.CUSTOM);
		lenient().when(course.isPublic()).thenReturn(visibility == CourseVisibility.PUBLIC);
		lenient().when(course.getVisibility()).thenReturn(visibility);
		lenient().when(course.getShareToken()).thenReturn(shareToken);
		return course;
	}

	private void givenDetailReads() {
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(1L)).willReturn(List.of());
		given(courseImageFileRepository.findByCourseId(1L)).willReturn(Optional.empty());
		given(savedCourseRepository.countByCourseId(1L)).willReturn(0L);
	}

	@Test
	@DisplayName("getCourseDetail: 링크 공개 코스는 토큰이 맞으면 비로그인도 본다 — 응답에 shareToken 은 없다")
	void getCourseDetail_unlisted_withToken_notLoggedIn_success() {
		Course course = unlistedCourse(10L, "secret");
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));
		givenDetailReads();

		CourseDetailResponse result = courseQueryService.getCourseDetail(1L, null, "secret");

		assertThat(result.visibility()).isEqualTo(CourseVisibility.UNLISTED);
		assertThat(result.shareToken()).isNull();
		assertThat(result.canEdit()).isFalse();
	}

	@Test
	@DisplayName("getCourseDetail: 링크 공개 코스라도 토큰이 없거나 틀리면 제3자는 COURSE_FORBIDDEN")
	void getCourseDetail_unlisted_wrongToken_throwsForbidden() {
		Course course = minimalCourse(CourseVisibility.UNLISTED, 10L, "secret");
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));

		assertThatThrownBy(() -> courseQueryService.getCourseDetail(1L, 99L, "wrong"))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
		assertThatThrownBy(() -> courseQueryService.getCourseDetail(1L, null, null))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
	}

	@Test
	@DisplayName("getCourseDetail: PRIVATE 은 토큰이 맞아도 COURSE_FORBIDDEN — 토큰보다 PRIVATE 이 강하다")
	void getCourseDetail_private_withToken_throwsForbidden() {
		// 예전에 링크 공개였다가 PRIVATE 으로 내린 코스 — 토큰은 보존돼 있다
		Course course = minimalCourse(CourseVisibility.PRIVATE, 10L, "secret");
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));

		assertThatThrownBy(() -> courseQueryService.getCourseDetail(1L, null, "secret"))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
	}

	@Test
	@DisplayName("getCourseDetail: 링크 공개 코스의 shareToken 은 방장에게만 실리고 멤버에겐 안 실린다")
	void getCourseDetail_unlisted_shareTokenOnlyForOwner() {
		Long ownerId = 10L;
		Long memberId = 5L;
		Course course = unlistedCourse(ownerId, "secret");
		CourseMember ownerRow = mockMemberRow(1L, ownerId, "다나");
		CourseMember memberRow = mockMemberRow(1L, memberId, "지수");
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));
		// 멤버의 접근 판정은 exists 로, 본문의 members·isMember 는 목록으로
		given(courseMemberRepository.existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(1L, memberId)).willReturn(true);
		given(courseMemberRepository.findAllByIdCourseIdAndDeletedAtIsNullOrderByJoinedAtAsc(1L))
			.willReturn(List.of(ownerRow, memberRow));
		givenDetailReads();

		assertThat(courseQueryService.getCourseDetail(1L, ownerId, null).shareToken()).isEqualTo("secret");
		assertThat(courseQueryService.getCourseDetail(1L, memberId, null).shareToken()).isNull();
	}

	// ───────────────────────── getCourseDetailByShareToken ─────────────────────────

	@Test
	@DisplayName("getCourseDetailByShareToken: 토큰으로 찾은 링크 공개 코스를 비로그인으로 본다")
	void getCourseDetailByShareToken_unlisted_success() {
		Course course = unlistedCourse(10L, "secret");
		given(courseRepository.findByShareTokenAndDeletedAtIsNull("secret")).willReturn(Optional.of(course));
		givenDetailReads();

		CourseDetailResponse result = courseQueryService.getCourseDetailByShareToken("secret", null);

		assertThat(result.id()).isEqualTo(1L);
		assertThat(result.visibility()).isEqualTo(CourseVisibility.UNLISTED);
		assertThat(result.isMine()).isFalse();
		assertThat(result.shareToken()).isNull();
	}

	@Test
	@DisplayName("getCourseDetailByShareToken: 모르는 토큰이거나 지금은 링크 공개가 아니면 403 이 아니라 COURSE_NOT_FOUND")
	void getCourseDetailByShareToken_unknownOrNotUnlisted_throwsNotFound() {
		// 토큰은 맞지만 PRIVATE 으로 내린 코스 — "토큰은 맞다"를 알려주면 토큰 유효성이 샌다
		Course downgraded = mock(Course.class);
		given(downgraded.getVisibility()).willReturn(CourseVisibility.PRIVATE);
		given(courseRepository.findByShareTokenAndDeletedAtIsNull("downgraded")).willReturn(Optional.of(downgraded));
		given(courseRepository.findByShareTokenAndDeletedAtIsNull("unknown")).willReturn(Optional.empty());

		assertThatThrownBy(() -> courseQueryService.getCourseDetailByShareToken("downgraded", 10L))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_NOT_FOUND.getMessage());
		assertThatThrownBy(() -> courseQueryService.getCourseDetailByShareToken("unknown", 10L))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_NOT_FOUND.getMessage());
	}

	// ───────────────────────── getCourseDetail(notModified): 폴링 ETag ─────────────────────────

	/** 304 경로는 제목·배지를 안 읽으므로 mockCourse 의 strict 스텁이 남는다 — 판정에 필요한 것만 스텁 */
	private Course versionedCourse(boolean isPublic, Long ownerId, long version) {
		Course course = mock(Course.class);
		lenient().when(course.getId()).thenReturn(1L);
		lenient().when(course.isPublic()).thenReturn(isPublic);
		lenient().when(course.getVersion()).thenReturn(version);
		if (ownerId != null) {
			Member owner = mock(Member.class);
			lenient().when(owner.getId()).thenReturn(ownerId);
			lenient().when(course.getMember()).thenReturn(owner);
		}
		return course;
	}

	@Test
	@DisplayName("304 폴링: 공개 코스는 비로그인도 version 을 받고, ETag 가 같으면 참여자·정류장을 읽지 않는다")
	void detailNotModified_public_notLoggedIn() {
		Course course = versionedCourse(true, 10L, 41L);
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));

		CourseDetailLookup lookup = courseQueryService.getCourseDetail(1L, null, null, version -> version == 41L);

		assertThat(lookup.version()).isEqualTo(41L);
		assertThat(lookup.body()).isEmpty();
		then(courseMemberRepository).shouldHaveNoInteractions();
		then(courseStoreRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("ETag 가 낡았으면 본문을 만든다 — 코스는 판정과 조립에 한 번만 읽는다")
	void detailModified_buildsBodyWithSingleCourseRead() {
		Course course = mockCourse(1L, "공개 코스", true, CourseType.CURATED, null);
		lenient().when(course.getVersion()).thenReturn(42L);
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));

		CourseDetailLookup lookup = courseQueryService.getCourseDetail(1L, null, null, version -> version == 41L);

		assertThat(lookup.version()).isEqualTo(42L);
		assertThat(lookup.body()).map(CourseDetailResponse::version).contains(42L);
		then(courseRepository).should(times(1)).findByIdAndDeletedAtIsNull(1L);
	}

	@Test
	@DisplayName("304 폴링: 비공개 코스는 방장과 활성 멤버만 — 제3자·비로그인은 304 대신 COURSE_FORBIDDEN")
	void detailNotModified_private_ownerAndMemberOnly() {
		Course course = versionedCourse(false, 10L, 7L);
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));
		given(courseMemberRepository.existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(1L, 5L)).willReturn(true);
		given(courseMemberRepository.existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(1L, 99L)).willReturn(false);

		assertThat(courseQueryService.getCourseDetail(1L, 10L, null, version -> true).version()).isEqualTo(7L);
		assertThat(courseQueryService.getCourseDetail(1L, 5L, null, version -> true).version()).isEqualTo(7L);
		assertThatThrownBy(() -> courseQueryService.getCourseDetail(1L, 99L, null, version -> true))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
		assertThatThrownBy(() -> courseQueryService.getCourseDetail(1L, null, null, version -> true))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
	}

	@Test
	@DisplayName("304 폴링: 지운 코스는 COURSE_NOT_FOUND")
	void detailNotModified_deleted_throwsNotFound() {
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> courseQueryService.getCourseDetail(1L, 10L, null, version -> true))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_NOT_FOUND.getMessage());
	}

	@Test
	@DisplayName("304 폴링: 링크 공개 코스는 토큰이 맞아야 version 을 준다 — PRIVATE 은 토큰이 맞아도 COURSE_FORBIDDEN")
	void detailNotModified_unlisted_tokenRequired() {
		Course unlisted = minimalCourse(CourseVisibility.UNLISTED, 10L, "secret");
		lenient().when(unlisted.getVersion()).thenReturn(3L);
		Course downgraded = minimalCourse(CourseVisibility.PRIVATE, 10L, "secret");
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(unlisted));
		given(courseRepository.findByIdAndDeletedAtIsNull(2L)).willReturn(Optional.of(downgraded));

		assertThat(courseQueryService.getCourseDetail(1L, null, "secret", version -> true).version()).isEqualTo(3L);
		assertThatThrownBy(() -> courseQueryService.getCourseDetail(1L, null, "wrong", version -> true))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
		assertThatThrownBy(() -> courseQueryService.getCourseDetail(2L, null, "secret", version -> true))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
	}

	// ───────────────────────── getCourseDetail: 사람별 메모 ─────────────────────────

	private CourseStoreMemo mockMemo(Long courseStoreId, Long memberId, String nickname, String content) {
		CourseStoreMemo memo = mock(CourseStoreMemo.class);
		Member author = mock(Member.class);
		lenient().when(author.getNickname()).thenReturn(nickname);
		lenient().when(memo.getId()).thenReturn(new CourseStoreMemoId(courseStoreId, memberId));
		lenient().when(memo.getMember()).thenReturn(author);
		lenient().when(memo.getContent()).thenReturn(content);
		return memo;
	}

	private CourseMember mockMemberRow(Long courseId, Long memberId, String displayName) {
		CourseMember row = mock(CourseMember.class);
		lenient().when(row.getId()).thenReturn(new CourseMemberId(courseId, memberId));
		lenient().when(row.getDisplayName()).thenReturn(displayName);
		return row;
	}

	@Test
	@DisplayName("getCourseDetail: 메모는 참여 순서로 정렬되고 내 메모가 myMemo·memo 로 내려온다. 행 없는 사람은 닉네임으로 맨 뒤")
	void getCourseDetail_memos_sortedByJoinOrder_withMyMemo() {
		Long ownerId = 10L;
		Long memberId = 5L;
		Long legacyId = 7L;
		Course course = mockCourse(1L, "협업 코스", false, CourseType.CUSTOM, ownerId);
		CourseStore courseStore = mockCourseStore(1, "빵집A", "종로구");
		given(courseStore.getId()).willReturn(501L);
		CourseStoreMemo ownerMemo = mockMemo(501L, ownerId, "다나닉", "소금빵 꼭 먹기");
		CourseStoreMemo memberMemo = mockMemo(501L, memberId, "지수닉", "주차 어렵대");
		CourseStoreMemo legacyMemo = mockMemo(501L, legacyId, "옛사람", "옛 메모");
		CourseMember ownerRow = mockMemberRow(1L, ownerId, "다나");
		CourseMember memberRow = mockMemberRow(1L, memberId, "지수");

		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(1L))
			.willReturn(List.of(courseStore));
		given(courseImageFileRepository.findByCourseId(1L)).willReturn(Optional.empty());
		given(savedCourseRepository.countByCourseId(1L)).willReturn(0L);
		// 리포지토리는 정렬을 보장하지 않는다 — 서버가 참여 순서로 세운다
		given(courseStoreMemoRepository.findAllByIdCourseStoreIdInAndDeletedAtIsNull(List.of(501L)))
			.willReturn(List.of(legacyMemo, memberMemo, ownerMemo));
		given(courseMemberRepository.findAllByIdCourseIdAndDeletedAtIsNullOrderByJoinedAtAsc(1L))
			.willReturn(List.of(ownerRow, memberRow));

		CourseDetailResponse result = courseQueryService.getCourseDetail(1L, ownerId, null);

		CourseStoreResponse stop = result.courseStores().get(0);
		assertThat(stop.memos()).extracting(CourseStoreMemoResponse::displayName)
			.containsExactly("다나", "지수", "옛사람");
		assertThat(stop.memos()).extracting(CourseStoreMemoResponse::isMine)
			.containsExactly(true, false, false);
		assertThat(stop.myMemo()).isEqualTo("소금빵 꼭 먹기");
		assertThat(stop.memo()).isEqualTo("소금빵 꼭 먹기");
	}

	@Test
	@DisplayName("getCourseDetail: 남의 메모만 있는 정류장은 myMemo·memo 가 null 이고 memos 엔 남의 것이 있다")
	void getCourseDetail_memos_othersOnly() {
		Long ownerId = 10L;
		Long viewerId = 5L;
		Course course = mockCourse(1L, "공개 코스", true, CourseType.CUSTOM, ownerId);
		CourseStore courseStore = mockCourseStore(1, "빵집A", "종로구");
		given(courseStore.getId()).willReturn(501L);
		CourseStoreMemo ownerMemo = mockMemo(501L, ownerId, "다나닉", "소금빵 꼭 먹기");
		CourseMember ownerRow = mockMemberRow(1L, ownerId, "다나");

		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(1L))
			.willReturn(List.of(courseStore));
		given(courseImageFileRepository.findByCourseId(1L)).willReturn(Optional.empty());
		given(savedCourseRepository.countByCourseId(1L)).willReturn(0L);
		given(courseStoreMemoRepository.findAllByIdCourseStoreIdInAndDeletedAtIsNull(List.of(501L)))
			.willReturn(List.of(ownerMemo));
		given(courseMemberRepository.findAllByIdCourseIdAndDeletedAtIsNullOrderByJoinedAtAsc(1L))
			.willReturn(List.of(ownerRow));

		CourseStoreResponse stop = courseQueryService.getCourseDetail(1L, viewerId, null).courseStores().get(0);

		assertThat(stop.myMemo()).isNull();
		assertThat(stop.memo()).isNull();
		assertThat(stop.memos()).hasSize(1);
		assertThat(stop.memos().get(0).isMine()).isFalse();
	}

	@Test
	@DisplayName("getCourseDetail: 메모 작성자가 탈퇴한 회원이어도 상세는 살아 있고 이름은 닉네임으로 폴백한다")
	void getCourseDetail_memoAuthorDeleted_stillSucceeds() {
		Long ownerId = 10L;
		Long deletedId = 7L;
		Course course = mockCourse(1L, "협업 코스", false, CourseType.CUSTOM, ownerId);
		CourseStore courseStore = mockCourseStore(1, "빵집A", "종로구");
		given(courseStore.getId()).willReturn(501L);
		// 탈퇴자의 메모는 탈퇴 때 닫히지만, 그 경계 순간에 남은 행 하나가 응답 전체를 죽이면 안 된다 —
		// 작성자는 MemberReader.getMember(탈퇴자면 예외)가 아니라 조인으로 읽는다
		Member deletedAuthor = Member.builder().nickname("탈퇴자").build();
		deletedAuthor.softDelete();
		CourseStoreMemo memo = mock(CourseStoreMemo.class);
		given(memo.getId()).willReturn(new CourseStoreMemoId(501L, deletedId));
		given(memo.getMember()).willReturn(deletedAuthor);
		given(memo.getContent()).willReturn("남긴 메모");

		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(1L))
			.willReturn(List.of(courseStore));
		given(courseImageFileRepository.findByCourseId(1L)).willReturn(Optional.empty());
		given(savedCourseRepository.countByCourseId(1L)).willReturn(0L);
		given(courseStoreMemoRepository.findAllByIdCourseStoreIdInAndDeletedAtIsNull(List.of(501L)))
			.willReturn(List.of(memo));
		given(courseMemberRepository.findAllByIdCourseIdAndDeletedAtIsNullOrderByJoinedAtAsc(1L))
			.willReturn(List.of());

		CourseStoreResponse stop = courseQueryService.getCourseDetail(1L, ownerId, null).courseStores().get(0);

		assertThat(stop.memos()).extracting(CourseStoreMemoResponse::displayName).containsExactly("탈퇴자");
	}

	@Test
	@DisplayName("getCourseDetail: 큐레이션 코스는 course_stores.memo 를 그대로 주고 메모 테이블을 읽지 않는다")
	void getCourseDetail_curated_keepsLegacyMemo() {
		Course course = mockCourse(1L, "큐레이션", true, CourseType.CURATED, null);
		CourseStore courseStore = mockCourseStore(1, "빵집A", "종로구");
		given(courseStore.getMemo()).willReturn("코스 설명 메모");

		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(1L))
			.willReturn(List.of(courseStore));
		given(courseImageFileRepository.findByCourseId(1L)).willReturn(Optional.empty());
		given(savedCourseRepository.countByCourseId(1L)).willReturn(0L);

		CourseStoreResponse stop = courseQueryService.getCourseDetail(1L, null, null).courseStores().get(0);

		assertThat(stop.memo()).isEqualTo("코스 설명 메모");
		assertThat(stop.myMemo()).isNull();
		assertThat(stop.memos()).isEmpty();
		then(courseStoreMemoRepository).shouldHaveNoInteractions();
	}

	private Course mockCourse(Long id, String title, boolean isPublic, CourseType type, Long memberId) {
		Course course = mock(Course.class);
		given(course.getId()).willReturn(id);
		given(course.getTitle()).willReturn(title);
		// getCourseDetail에서만 호출됨 — 다른 테스트에서 호출 안 될 수 있으므로 lenient
		lenient().when(course.isPublic()).thenReturn(isPublic);
		lenient().when(course.getVisibility()).thenReturn(CourseVisibility.fromPublic(isPublic));
		// Mockito는 Integer 반환 미스텁 메서드에 null이 아닌 0을 돌려주므로, 실제 미핀 상태(null)를 명시 스텁
		lenient().when(course.getPinOrder()).thenReturn(null);
		given(course.getType()).willReturn(type);
		given(course.getBadge()).willReturn(null);
		if (memberId != null) {
			Member member = mock(Member.class);
			// 접근 제어 체크에서만 사용 — summary 빌드 시 미호출
			lenient().when(member.getId()).thenReturn(memberId);
			given(member.getNickname()).willReturn("테스터");
			given(course.getMember()).willReturn(member);
		}
		return course;
	}

	private CourseStoreSummaryProjection mockStop(Long courseId, Long storeId, String storeName, String region2nd) {
		CourseStoreSummaryProjection stop = mock(CourseStoreSummaryProjection.class);
		lenient().when(stop.getCourseId()).thenReturn(courseId);
		lenient().when(stop.getStoreId()).thenReturn(storeId);
		lenient().when(stop.getStoreName()).thenReturn(storeName);
		lenient().when(stop.getRegion1st()).thenReturn(null);
		lenient().when(stop.getRegion2nd()).thenReturn(region2nd);
		return stop;
	}

	private CourseStore mockCourseStore(int visitOrder, String storeName, String region2nd) {
		CourseStore courseStore = mock(CourseStore.class);
		Store store = mock(Store.class);
		Point location = mock(Point.class);

		// CourseStoreResponse.from() 호출 시에만 필요 — summary 빌드 시 미호출
		lenient().when(courseStore.getVisitOrder()).thenReturn(visitOrder);
		given(courseStore.getStore()).willReturn(store);
		lenient().when(courseStore.getMemo()).thenReturn(null);
		lenient().when(courseStore.getVisitTime()).thenReturn(null);
		lenient().when(store.getId()).thenReturn(1L);
		lenient().when(store.getName()).thenReturn(storeName);
		given(store.getRegion2nd()).willReturn(region2nd);
		lenient().when(store.getLocation()).thenReturn(location);
		lenient().when(location.getY()).thenReturn(37.565);
		lenient().when(location.getX()).thenReturn(126.977);
		return courseStore;
	}
}
