package sweetgirl.croissant.course.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseStore;
import sweetgirl.croissant.course.entity.CourseStoreMemo;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.CourseStoreMemoRepository;
import sweetgirl.croissant.course.repository.CourseStoreRepository;
import sweetgirl.croissant.course.service.CourseAccessPolicy;
import sweetgirl.croissant.course.service.CourseVersionBumper;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.service.MemberRestrictionReader;
import sweetgirl.croissant.store.entity.Store;

@ExtendWith(MockitoExtension.class)
@DisplayName("MemoDraftApplier")
class MemoDraftApplierTest {

	private static final long COURSE_ID = 10L;
	private static final long STORE_ID = 100L;
	private static final long COURSE_STORE_ID = 501L;
	private static final Long MEMBER_ID = 1L;
	private static final DraftField FIELD = new DraftField(STORE_ID, MEMBER_ID);

	@Mock
	private CourseRepository courseRepository;
	@Mock
	private CourseStoreRepository courseStoreRepository;
	@Mock
	private CourseStoreMemoRepository courseStoreMemoRepository;
	@Mock
	private MemberRepository memberRepository;
	@Mock
	private CourseAccessPolicy courseAccessPolicy;
	@Mock
	private MemberRestrictionReader memberRestrictionReader;
	@Mock
	private CourseVersionBumper courseVersionBumper;

	@InjectMocks
	private MemoDraftApplier applier;

	private Course course;
	private CourseStore stop;

	@BeforeEach
	void setUp() {
		course = mock(Course.class);
		stop = mock(CourseStore.class);
		Store store = mock(Store.class);
		lenient().when(store.getId()).thenReturn(STORE_ID);
		lenient().when(stop.getStore()).thenReturn(store);
		lenient().when(stop.getId()).thenReturn(COURSE_STORE_ID);
	}

	private void givenLiveCourseWithStop() {
		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.of(course));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of(stop));
	}

	private void givenWritable(Long memberId) {
		given(courseAccessPolicy.canEdit(course, memberId)).willReturn(true);
		given(memberRestrictionReader.isWriteRestricted(memberId)).willReturn(false);
	}

	private CourseStoreMemo existingMemo(String content, long seq) {
		Member author = mock(Member.class);
		given(author.getId()).willReturn(MEMBER_ID);
		return CourseStoreMemo.of(stop, author, content, seq);
	}

	@Test
	@DisplayName("지운 코스의 초안은 전부 버리되 처리됨으로 돌려준다 — Redis 에서 지워지도록")
	void deletedCourse_discardsAllAsHandled() {
		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.empty());
		DraftEntry entry = new DraftEntry(FIELD, "소금빵", 5L, 1L);

		Map<DraftField, Long> handled = applier.apply(COURSE_ID, List.of(entry));

		assertThat(handled).containsEntry(FIELD, 5L);
		then(courseStoreMemoRepository).shouldHaveNoInteractions();
		then(courseVersionBumper).should(never()).bump(any());
	}

	@Test
	@DisplayName("빈 목록이면 DB 를 건드리지 않는다")
	void noEntries_noDbAccess() {
		assertThat(applier.apply(COURSE_ID, List.of())).isEmpty();
		then(courseRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("코스에 없는(지운) 가게의 초안은 버린다")
	void unknownStore_skipped() {
		givenLiveCourseWithStop();
		DraftEntry entry = new DraftEntry(new DraftField(999L, MEMBER_ID), "소금빵", 5L, 1L);

		Map<DraftField, Long> handled = applier.apply(COURSE_ID, List.of(entry));

		assertThat(handled).hasSize(1);
		then(courseStoreMemoRepository).shouldHaveNoInteractions();
		then(courseVersionBumper).should(never()).bump(any());
	}

	@Test
	@DisplayName("나간 멤버·쓰기 제한 회원의 초안은 버린다")
	void notWritableMember_skipped() {
		givenLiveCourseWithStop();
		given(courseAccessPolicy.canEdit(course, MEMBER_ID)).willReturn(false);
		given(courseAccessPolicy.canEdit(course, 2L)).willReturn(true);
		given(memberRestrictionReader.isWriteRestricted(2L)).willReturn(true);
		List<DraftEntry> entries = List.of(
			new DraftEntry(FIELD, "나간 사람 글", 5L, 1L),
			new DraftEntry(new DraftField(STORE_ID, 2L), "밴된 사람 글", 6L, 1L));

		applier.apply(COURSE_ID, entries);

		then(courseStoreMemoRepository).shouldHaveNoInteractions();
		then(courseVersionBumper).should(never()).bump(any());
	}

	@Test
	@DisplayName("기존 메모에 더 큰 seq 의 글은 반영하고 버전을 한 번 올린다")
	void existingMemo_newerApplied_bumpsOnce() {
		givenLiveCourseWithStop();
		givenWritable(MEMBER_ID);
		CourseStoreMemo memo = existingMemo("옛 글", 3L);
		given(courseStoreMemoRepository.findByIdCourseStoreIdAndIdMemberId(COURSE_STORE_ID, MEMBER_ID))
			.willReturn(Optional.of(memo));

		Map<DraftField, Long> handled = applier.apply(COURSE_ID, List.of(new DraftEntry(FIELD, "새 글", 5L, 1L)));

		assertThat(memo.getContent()).isEqualTo("새 글");
		assertThat(memo.getSeq()).isEqualTo(5L);
		assertThat(handled).containsEntry(FIELD, 5L);
		then(courseStoreMemoRepository).should(never()).save(any());
		then(courseVersionBumper).should(times(1)).bump(COURSE_ID);
	}

	@Test
	@DisplayName("기존 메모가 이미 더 새로우면(PATCH 가 먼저 갔다) 아무것도 바꾸지 않고 버전도 올리지 않는다")
	void existingMemo_alreadyNewer_noChange() {
		givenLiveCourseWithStop();
		givenWritable(MEMBER_ID);
		CourseStoreMemo memo = existingMemo("PATCH 로 먼저 간 글", 9L);
		given(courseStoreMemoRepository.findByIdCourseStoreIdAndIdMemberId(COURSE_STORE_ID, MEMBER_ID))
			.willReturn(Optional.of(memo));

		applier.apply(COURSE_ID, List.of(new DraftEntry(FIELD, "늦은 초안", 5L, 1L)));

		assertThat(memo.getContent()).isEqualTo("PATCH 로 먼저 간 글");
		then(courseVersionBumper).should(never()).bump(any());
	}

	@Test
	@DisplayName("메모 행이 없으면 seq 를 들고 INSERT 한다")
	void noMemo_inserts() {
		givenLiveCourseWithStop();
		givenWritable(MEMBER_ID);
		given(courseStoreMemoRepository.findByIdCourseStoreIdAndIdMemberId(COURSE_STORE_ID, MEMBER_ID))
			.willReturn(Optional.empty());
		Member author = mock(Member.class);
		given(author.getId()).willReturn(MEMBER_ID);
		given(memberRepository.getReferenceById(MEMBER_ID)).willReturn(author);

		applier.apply(COURSE_ID, List.of(new DraftEntry(FIELD, "첫 메모", 5L, 1L)));

		then(courseStoreMemoRepository).should().save(org.mockito.ArgumentMatchers.argThat(
			saved -> saved.getSeq() == 5L && saved.getContent().equals("첫 메모")));
		then(courseVersionBumper).should(times(1)).bump(COURSE_ID);
	}

	@Test
	@DisplayName("메모 행이 없는데 지우기 예약(빈 내용)이면 아무것도 만들지 않는다")
	void noMemo_deletion_noop() {
		givenLiveCourseWithStop();
		givenWritable(MEMBER_ID);
		given(courseStoreMemoRepository.findByIdCourseStoreIdAndIdMemberId(COURSE_STORE_ID, MEMBER_ID))
			.willReturn(Optional.empty());

		Map<DraftField, Long> handled = applier.apply(COURSE_ID, List.of(new DraftEntry(FIELD, "", 5L, 1L)));

		assertThat(handled).containsEntry(FIELD, 5L);
		then(courseStoreMemoRepository).should(never()).save(any());
		then(courseVersionBumper).should(never()).bump(any());
	}

	@Test
	@DisplayName("같은 회원의 초안이 여럿이어도 권한 판정은 한 번, 버전 bump 도 한 번이다")
	void manyEntries_oneAccessCheck_oneBump() {
		CourseStore secondStop = mock(CourseStore.class);
		Store secondStore = mock(Store.class);
		given(secondStore.getId()).willReturn(200L);
		given(secondStop.getStore()).willReturn(secondStore);
		given(secondStop.getId()).willReturn(502L);
		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.of(course));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of(stop, secondStop));
		givenWritable(MEMBER_ID);
		CourseStoreMemo firstMemo = existingMemo("a", 1L);
		CourseStoreMemo secondMemo = existingMemo("b", 1L);
		given(courseStoreMemoRepository.findByIdCourseStoreIdAndIdMemberId(COURSE_STORE_ID, MEMBER_ID))
			.willReturn(Optional.of(firstMemo));
		given(courseStoreMemoRepository.findByIdCourseStoreIdAndIdMemberId(502L, MEMBER_ID))
			.willReturn(Optional.of(secondMemo));

		applier.apply(COURSE_ID, List.of(
			new DraftEntry(FIELD, "a2", 5L, 1L),
			new DraftEntry(new DraftField(200L, MEMBER_ID), "b2", 6L, 1L)));

		then(courseAccessPolicy).should(times(1)).canEdit(course, MEMBER_ID);
		then(courseVersionBumper).should(times(1)).bump(COURSE_ID);
	}
}
