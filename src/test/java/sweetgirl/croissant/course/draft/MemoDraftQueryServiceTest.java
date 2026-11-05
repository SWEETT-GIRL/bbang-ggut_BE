package sweetgirl.croissant.course.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

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
import sweetgirl.croissant.store.entity.Store;

@ExtendWith(MockitoExtension.class)
@DisplayName("MemoDraftQueryService")
class MemoDraftQueryServiceTest {

	private static final Long ME = 1L;
	private static final Long FRIEND = 2L;
	private static final Long COURSE_ID = 10L;
	private static final long STORE_ID = 100L;

	@Mock
	private CourseRepository courseRepository;
	@Mock
	private CourseStoreRepository courseStoreRepository;
	@Mock
	private CourseAccessPolicy courseAccessPolicy;
	@Mock
	private MemberReader memberReader;
	@Mock
	private MemberRestrictionReader memberRestrictionReader;
	@Mock
	private MemoDraftBuffer buffer;

	@InjectMocks
	private MemoDraftQueryService memoDraftQueryService;

	private Course course;

	@BeforeEach
	void setUp() {
		course = mock(Course.class);
	}

	private void givenViewerCanEdit() {
		given(courseRepository.findByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.of(course));
	}

	private CourseStore stopOf(long storeId) {
		Store store = mock(Store.class);
		given(store.getId()).willReturn(storeId);
		CourseStore stop = mock(CourseStore.class);
		given(stop.getStore()).willReturn(store);
		return stop;
	}

	@Test
	@DisplayName("코스에 있는 가게·지금 쓸 수 있는 회원의 초안만, 가게·회원 순으로, isMe 와 함께 돌려준다")
	void getDrafts_filtersAndSorts() {
		givenViewerCanEdit();
		CourseStore stop = stopOf(STORE_ID);
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of(stop));
		given(buffer.readAll(COURSE_ID)).willReturn(List.of(
			new DraftEntry(new DraftField(STORE_ID, FRIEND), "친구 초안", 7L, 1_762_000_000_000L),
			new DraftEntry(new DraftField(STORE_ID, ME), "내 초안", 5L, 1_762_000_000_000L),
			new DraftEntry(new DraftField(999L, ME), "지운 가게 초안", 6L, 1_762_000_000_000L),
			new DraftEntry(new DraftField(STORE_ID, 3L), "나간 사람 초안", 8L, 1_762_000_000_000L)));
		given(courseAccessPolicy.canEdit(course, ME)).willReturn(true);
		given(courseAccessPolicy.canEdit(course, FRIEND)).willReturn(true);
		given(courseAccessPolicy.canEdit(course, 3L)).willReturn(false);
		given(memberRestrictionReader.isWriteRestricted(ME)).willReturn(false);
		given(memberRestrictionReader.isWriteRestricted(FRIEND)).willReturn(false);

		List<MemoDraftResponse> drafts = memoDraftQueryService.getDrafts(ME, COURSE_ID);

		assertThat(drafts).extracting(MemoDraftResponse::memberId).containsExactly(ME, FRIEND);
		assertThat(drafts).extracting(MemoDraftResponse::isMe).containsExactly(true, false);
		assertThat(drafts.get(0).content()).isEqualTo("내 초안");
		assertThat(drafts.get(0).seq()).isEqualTo(5L);
		assertThat(drafts.get(0).updatedAt()).isEqualTo(LocalDateTime.of(2025, 11, 1, 21, 26, 40));
		then(memberReader).should().requireLoggedIn(ME);
		then(courseAccessPolicy).should().requireEditable(course, ME);
	}

	@Test
	@DisplayName("초안이 없으면 가게를 조회하지 않고 빈 목록")
	void getDrafts_empty_skipsStoreLookup() {
		givenViewerCanEdit();
		given(buffer.readAll(COURSE_ID)).willReturn(List.of());

		assertThat(memoDraftQueryService.getDrafts(ME, COURSE_ID)).isEmpty();
		then(courseStoreRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("편집 권한이 없으면 403 — 링크로만 보는 사람은 초안을 못 본다")
	void getDrafts_forbidden() {
		givenViewerCanEdit();
		willThrow(new CourseException(CourseErrorCode.COURSE_FORBIDDEN))
			.given(courseAccessPolicy).requireEditable(course, ME);

		assertThatThrownBy(() -> memoDraftQueryService.getDrafts(ME, COURSE_ID))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
		then(buffer).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("Redis 장애면 503")
	void getDrafts_redisFailure_unavailable() {
		givenViewerCanEdit();
		given(buffer.readAll(COURSE_ID)).willThrow(new RedisConnectionFailureException("down"));

		assertThatThrownBy(() -> memoDraftQueryService.getDrafts(ME, COURSE_ID))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_DRAFT_UNAVAILABLE.getMessage());
	}

	@Test
	@DisplayName("DB(커넥션 풀)가 바쁘면 503 — 앱은 초안 없이 그리고 폴링으로 메운다")
	void getDrafts_dbFailure_unavailable() {
		given(courseRepository.findByIdAndDeletedAtIsNull(COURSE_ID))
			.willThrow(new org.springframework.transaction.CannotCreateTransactionException("pool exhausted"));

		assertThatThrownBy(() -> memoDraftQueryService.getDrafts(ME, COURSE_ID))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_DRAFT_UNAVAILABLE.getMessage());
	}

	@Test
	@DisplayName("없는 코스는 404")
	void getDrafts_courseNotFound() {
		given(courseRepository.findByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> memoDraftQueryService.getDrafts(ME, COURSE_ID))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_NOT_FOUND.getMessage());
	}
}
