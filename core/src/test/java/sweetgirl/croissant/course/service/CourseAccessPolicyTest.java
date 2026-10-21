package sweetgirl.croissant.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.enums.CourseType;
import sweetgirl.croissant.course.enums.CourseVisibility;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.repository.CourseMemberRepository;
import sweetgirl.croissant.member.entity.Member;

@ExtendWith(MockitoExtension.class)
class CourseAccessPolicyTest {

	private static final Long COURSE_ID = 10L;
	private static final Long OWNER_ID = 1L;
	private static final Long MEMBER_ID = 2L;
	private static final Long OTHER_ID = 3L;

	@Mock
	private CourseMemberRepository courseMemberRepository;

	@InjectMocks
	private CourseAccessPolicy courseAccessPolicy;

	private Course course(CourseType type, Long ownerId, CourseVisibility visibility) {
		Course course = mock(Course.class);
		lenient().when(course.getId()).thenReturn(COURSE_ID);
		lenient().when(course.getType()).thenReturn(type);
		lenient().when(course.getVisibility()).thenReturn(visibility);
		lenient().when(course.isPublic()).thenReturn(visibility == CourseVisibility.PUBLIC);
		if (ownerId != null) {
			Member owner = mock(Member.class);
			lenient().when(owner.getId()).thenReturn(ownerId);
			lenient().when(course.getMember()).thenReturn(owner);
		}
		return course;
	}

	private Course privateCourse() {
		return course(CourseType.CUSTOM, OWNER_ID, CourseVisibility.PRIVATE);
	}

	private void givenActiveMember(Long memberId, boolean active) {
		given(courseMemberRepository.existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(COURSE_ID, memberId))
			.willReturn(active);
	}

	@Nested
	@DisplayName("isOwner · isActiveMember")
	class OwnerAndMember {

		@Test
		@DisplayName("방장은 courses.member_id 로만 판정한다 — 큐레이션은 방장이 없고, 비로그인은 false")
		void isOwner() {
			assertThat(courseAccessPolicy.isOwner(privateCourse(), OWNER_ID)).isTrue();
			assertThat(courseAccessPolicy.isOwner(privateCourse(), MEMBER_ID)).isFalse();
			assertThat(courseAccessPolicy.isOwner(privateCourse(), null)).isFalse();
			assertThat(courseAccessPolicy.isOwner(course(CourseType.CURATED, null, CourseVisibility.PUBLIC), OWNER_ID))
				.isFalse();
		}

		@Test
		@DisplayName("활성 멤버는 course_members 를 본다 — 비로그인은 조회 없이 false")
		void isActiveMember() {
			givenActiveMember(MEMBER_ID, true);

			assertThat(courseAccessPolicy.isActiveMember(privateCourse(), MEMBER_ID)).isTrue();
			assertThat(courseAccessPolicy.isActiveMember(privateCourse(), null)).isFalse();
			then(courseMemberRepository).should(never())
				.existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(COURSE_ID, null);
		}
	}

	@Nested
	@DisplayName("canEdit · requireEditable · requireOwner")
	class Edit {

		@Test
		@DisplayName("방장은 멤버 조회 없이 편집한다")
		void owner_editsWithoutMemberLookup() {
			assertThatCode(() -> courseAccessPolicy.requireEditable(privateCourse(), OWNER_ID))
				.doesNotThrowAnyException();
			then(courseMemberRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("활성 멤버는 편집하고, 나간 사람·제3자는 COURSE_FORBIDDEN")
		void member_editsAndOutsiderForbidden() {
			givenActiveMember(MEMBER_ID, true);
			givenActiveMember(OTHER_ID, false);

			assertThatCode(() -> courseAccessPolicy.requireEditable(privateCourse(), MEMBER_ID))
				.doesNotThrowAnyException();
			assertThatThrownBy(() -> courseAccessPolicy.requireEditable(privateCourse(), OTHER_ID))
				.isInstanceOf(CourseException.class)
				.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
		}

		@Test
		@DisplayName("큐레이션은 course_members 에 행이 있어도 편집 불가 — CURATED 검사가 소유자 판정보다 먼저라 조회조차 안 한다")
		void curated_neverEditable() {
			Course curated = course(CourseType.CURATED, null, CourseVisibility.PUBLIC);

			assertThat(courseAccessPolicy.canEdit(curated, MEMBER_ID)).isFalse();
			assertThatThrownBy(() -> courseAccessPolicy.requireEditable(curated, MEMBER_ID))
				.isInstanceOf(CourseException.class)
				.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
			then(courseMemberRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("방장 전용 판정은 활성 멤버도 COURSE_FORBIDDEN")
		void requireOwner_memberForbidden() {
			assertThatCode(() -> courseAccessPolicy.requireOwner(privateCourse(), OWNER_ID)).doesNotThrowAnyException();
			assertThatThrownBy(() -> courseAccessPolicy.requireOwner(privateCourse(), MEMBER_ID))
				.isInstanceOf(CourseException.class)
				.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
			then(courseMemberRepository).shouldHaveNoInteractions();
		}
	}

	@Nested
	@DisplayName("canView · requireViewable")
	class View {

		@Test
		@DisplayName("PUBLIC 은 비로그인도 본다 — 멤버 조회 없이")
		void public_anyone() {
			Course publicCourse = course(CourseType.CUSTOM, OWNER_ID, CourseVisibility.PUBLIC);

			assertThat(courseAccessPolicy.canView(publicCourse, null, null)).isTrue();
			then(courseMemberRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("PRIVATE 은 방장·활성 멤버만 — 토큰이 맞아도 제3자는 못 본다")
		void private_ownerAndMemberOnly() {
			// 토큰은 PRIVATE 이면 읽지도 않는다(ShareTokenMatcher 가 visibility 먼저 본다) — 그래서 stub 하지 않는다
			Course privateCourse = privateCourse();
			givenActiveMember(MEMBER_ID, true);
			givenActiveMember(OTHER_ID, false);

			assertThat(courseAccessPolicy.canView(privateCourse, OWNER_ID, null)).isTrue();
			assertThat(courseAccessPolicy.canView(privateCourse, MEMBER_ID, null)).isTrue();
			assertThat(courseAccessPolicy.canView(privateCourse, OTHER_ID, "token")).isFalse();
			assertThatThrownBy(() -> courseAccessPolicy.requireViewable(privateCourse, null, "token"))
				.isInstanceOf(CourseException.class)
				.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
		}

		@Test
		@DisplayName("UNLISTED 는 토큰이 맞으면 제3자·비로그인도 보고, 토큰이 없거나 틀리면 못 본다")
		void unlisted_tokenDecides() {
			Course unlisted = course(CourseType.CUSTOM, OWNER_ID, CourseVisibility.UNLISTED);
			given(unlisted.getShareToken()).willReturn("token");
			givenActiveMember(OTHER_ID, false);

			assertThat(courseAccessPolicy.canView(unlisted, null, "token")).isTrue();
			assertThat(courseAccessPolicy.canView(unlisted, OTHER_ID, "wrong")).isFalse();
			assertThat(courseAccessPolicy.canView(unlisted, OTHER_ID, null)).isFalse();
		}
	}
}
