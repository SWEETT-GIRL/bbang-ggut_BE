package sweetgirl.croissant.course.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.service.CourseAccessPolicy;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.MemberRestrictionReader;

@ExtendWith(MockitoExtension.class)
@DisplayName("MemoDraftAccessCache")
class MemoDraftAccessCacheTest {

	private static final Long COURSE_ID = 10L;
	private static final Long MEMBER_ID = 1L;

	@Mock
	private CourseRepository courseRepository;
	@Mock
	private CourseAccessPolicy courseAccessPolicy;
	@Mock
	private MemberReader memberReader;
	@Mock
	private MemberRestrictionReader memberRestrictionReader;

	private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
	private FakeTicker ticker;
	private MemoDraftAccessCache accessCache;
	private Course course;

	@BeforeEach
	void setUp() {
		CourseDraftProperties properties = new CourseDraftProperties();
		properties.setAccessCacheTtl(Duration.ofSeconds(10));
		properties.setAccessStaleGrace(Duration.ofMinutes(5));
		properties.setAccessDbBackoff(Duration.ofSeconds(2));
		ticker = new FakeTicker();
		accessCache = new MemoDraftAccessCache(courseRepository, courseAccessPolicy, memberReader,
			memberRestrictionReader, properties, meterRegistry, ticker);
		course = mock(Course.class);
	}

	@Test
	@DisplayName("통과한 판정은 10초 동안 DB 를 다시 읽지 않는다")
	void allowed_isCachedForTtl() {
		given(courseRepository.findByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.of(course));

		accessCache.requireEditable(COURSE_ID, MEMBER_ID);
		accessCache.requireEditable(COURSE_ID, MEMBER_ID);
		ticker.advance(Duration.ofSeconds(9));
		accessCache.requireEditable(COURSE_ID, MEMBER_ID);

		then(courseRepository).should(times(1)).findByIdAndDeletedAtIsNull(COURSE_ID);
		then(courseAccessPolicy).should(times(1)).requireEditable(course, MEMBER_ID);
		// 로그인 판정도 캐시 안 — 매 요청 DB 를 읽지 않는다
		then(memberReader).should(times(1)).requireLoggedIn(MEMBER_ID);
		then(memberRestrictionReader).should(times(1)).validateCanWrite(MEMBER_ID);
	}

	@Test
	@DisplayName("TTL 의 1.5배(지터 상한)가 지나면 반드시 다시 판정한다 — 나간 사람의 초안이 머무는 상한")
	void afterTtl_reevaluates() {
		given(courseRepository.findByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.of(course));

		accessCache.requireEditable(COURSE_ID, MEMBER_ID);
		ticker.advance(Duration.ofSeconds(16));
		accessCache.requireEditable(COURSE_ID, MEMBER_ID);

		then(courseRepository).should(times(2)).findByIdAndDeletedAtIsNull(COURSE_ID);
	}

	@Test
	@DisplayName("권한 없음은 403 으로 던지고 캐시하지 않는다 — 초대를 막 수락한 사람이 다음 요청에서 바로 통과한다")
	void forbidden_throwsAndIsNotCached() {
		given(courseRepository.findByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.of(course));
		willThrow(new CourseException(CourseErrorCode.COURSE_FORBIDDEN))
			.given(courseAccessPolicy).requireEditable(course, MEMBER_ID);

		assertThatThrownBy(() -> accessCache.requireEditable(COURSE_ID, MEMBER_ID))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
		assertThatThrownBy(() -> accessCache.requireEditable(COURSE_ID, MEMBER_ID))
			.isInstanceOf(CourseException.class);

		then(courseRepository).should(times(2)).findByIdAndDeletedAtIsNull(COURSE_ID);
		then(memberRestrictionReader).should(times(0)).validateCanWrite(any());
	}

	@Test
	@DisplayName("쓰기 제한(밴) 회원은 초안도 못 쓴다 — DRAFT 신호로 일행에게 바로 보이는 글이다")
	void restrictedMember_rejected() {
		given(courseRepository.findByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.of(course));
		willThrow(new MemberException(MemberErrorCode.MEMBER_WRITE_RESTRICTED))
			.given(memberRestrictionReader).validateCanWrite(MEMBER_ID);

		assertThatThrownBy(() -> accessCache.requireEditable(COURSE_ID, MEMBER_ID))
			.isInstanceOf(MemberException.class);
	}

	@Test
	@DisplayName("없는 코스는 404")
	void missingCourse_notFound() {
		given(courseRepository.findByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> accessCache.requireEditable(COURSE_ID, MEMBER_ID))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_NOT_FOUND.getMessage());
	}

	private static final DataAccessResourceFailureException DB_DOWN = new DataAccessResourceFailureException(
		"Connection is not available");

	private void passOnce() {
		given(courseRepository.findByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.of(course));
		accessCache.requireEditable(COURSE_ID, MEMBER_ID);
	}

	@Test
	@DisplayName("DB 가 죽어도 쓰던 사람은 계속 쓴다 — 캐시가 빠진 뒤 DB 실패는 마지막으로 통과한 판정으로 받는다")
	void dbDown_servesLastDecision() {
		passOnce();
		ticker.advance(Duration.ofSeconds(16));
		willThrow(DB_DOWN).given(memberReader).requireLoggedIn(MEMBER_ID);

		assertThatCode(() -> accessCache.requireEditable(COURSE_ID, MEMBER_ID)).doesNotThrowAnyException();

		assertThat(meterRegistry.counter("draft_access_stale_total").count()).isEqualTo(1.0);
	}

	@Test
	@DisplayName("DB 가 실패한 뒤 미루는 동안은 DB 를 다시 두드리지 않는다 — 요청마다 커넥션을 기다리지 않게")
	void dbDown_doesNotTouchDbDuringBackoff() {
		passOnce();
		ticker.advance(Duration.ofSeconds(16));
		willThrow(DB_DOWN).given(memberReader).requireLoggedIn(MEMBER_ID);
		accessCache.requireEditable(COURSE_ID, MEMBER_ID);

		ticker.advance(Duration.ofSeconds(1));
		accessCache.requireEditable(COURSE_ID, MEMBER_ID);
		accessCache.requireEditable(COURSE_ID, MEMBER_ID);

		// 처음 통과 1번 + 실패한 1번뿐 — 미루는 동안의 두 요청은 DB 를 부르지 않았다
		then(memberReader).should(times(2)).requireLoggedIn(MEMBER_ID);
		assertThat(meterRegistry.counter("draft_access_stale_total").count()).isEqualTo(3.0);
	}

	@Test
	@DisplayName("통과한 이력이 없는 사람은 DB 가 죽어 있으면 바로 실패한다(→ 503) — 미루는 동안에도 기다리지 않고")
	void dbDown_withoutHistory_fails() {
		willThrow(DB_DOWN).given(memberReader).requireLoggedIn(MEMBER_ID);

		assertThatThrownBy(() -> accessCache.requireEditable(COURSE_ID, MEMBER_ID))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> accessCache.requireEditable(COURSE_ID, MEMBER_ID))
			.isInstanceOf(DataAccessException.class);

		then(memberReader).should(times(1)).requireLoggedIn(MEMBER_ID);
	}

	@Test
	@DisplayName("미룬 시간이 지나면 다시 DB 에 묻고, 돌아와 있으면 평소대로 판정한다")
	void dbBack_checksAgain() {
		passOnce();
		ticker.advance(Duration.ofSeconds(16));
		willThrow(DB_DOWN).willDoNothing().given(memberReader).requireLoggedIn(MEMBER_ID);
		accessCache.requireEditable(COURSE_ID, MEMBER_ID);

		ticker.advance(Duration.ofSeconds(3));
		accessCache.requireEditable(COURSE_ID, MEMBER_ID);
		accessCache.requireEditable(COURSE_ID, MEMBER_ID);

		// 통과 1 + 실패 1 + 복귀 확인 1. 그 뒤는 다시 10초 캐시
		then(memberReader).should(times(3)).requireLoggedIn(MEMBER_ID);
		then(courseAccessPolicy).should(times(2)).requireEditable(course, MEMBER_ID);
	}

	@Test
	@DisplayName("DB 가 답한 거절은 그대로 거절이고, 그 뒤 DB 가 죽어도 옛 통과로 되살아나지 않는다")
	void deniedThenDbDown_staysDenied() {
		passOnce();
		ticker.advance(Duration.ofSeconds(16));
		willThrow(new CourseException(CourseErrorCode.COURSE_FORBIDDEN)).given(courseAccessPolicy)
			.requireEditable(course, MEMBER_ID);
		assertThatThrownBy(() -> accessCache.requireEditable(COURSE_ID, MEMBER_ID)).isInstanceOf(CourseException.class);

		willThrow(DB_DOWN).given(memberReader).requireLoggedIn(MEMBER_ID);

		assertThatThrownBy(() -> accessCache.requireEditable(COURSE_ID, MEMBER_ID))
			.isInstanceOf(DataAccessException.class);
	}

	@Test
	@DisplayName("마지막 통과가 인정 기간(5분)보다 오래됐으면 DB 가 죽어 있을 때 받지 않는다")
	void dbDown_afterGrace_fails() {
		passOnce();
		ticker.advance(Duration.ofMinutes(6));
		willThrow(DB_DOWN).given(memberReader).requireLoggedIn(MEMBER_ID);

		assertThatThrownBy(() -> accessCache.requireEditable(COURSE_ID, MEMBER_ID))
			.isInstanceOf(DataAccessException.class);
	}
}
