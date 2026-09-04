package sweetgirl.croissant.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.dto.projection.CourseProgressAggregateProjection;
import sweetgirl.croissant.course.dto.projection.CourseProgressMemberAggregateProjection;
import sweetgirl.croissant.course.dto.response.CourseProgressResponse;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseProgress;
import sweetgirl.croissant.course.enums.CourseProgressStatus;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.repository.CourseImageFileRepository;
import sweetgirl.croissant.course.repository.CourseMemberRepository;
import sweetgirl.croissant.course.repository.CourseProgressRepository;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.CourseStoreRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.MemberRestrictionReader;

@ExtendWith(MockitoExtension.class)
class CourseProgressServiceTest {

	@Mock
	MemberReader memberReader;
	@Mock
	MemberRestrictionReader memberRestrictionReader;
	@Mock
	CourseRepository courseRepository;
	@Mock
	CourseProgressRepository courseProgressRepository;
	@Mock
	CourseImageFileRepository courseImageFileRepository;
	@Mock
	CourseStoreRepository courseStoreRepository;
	@Mock
	CourseStoreThumbnailReader courseStoreThumbnailReader;
	@Mock
	CourseMemberRepository courseMemberRepository;

	@InjectMocks
	CourseProgressService courseProgressService;

	@Test
	@DisplayName("start: 진행 이력이 없으면 새로 시작한다")
	void start_newProgress_savesProgress() {
		Member member = member(7L);
		Course course = course(10L);
		given(memberReader.getMember(7L)).willReturn(member);
		given(courseRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(course));
		given(courseProgressRepository.findByMemberIdAndCourseId(7L, 10L)).willReturn(Optional.empty());
		given(courseProgressRepository.findAllByMemberIdAndStatus(7L, CourseProgressStatus.IN_PROGRESS))
			.willReturn(List.of());

		courseProgressService.start(7L, 10L);

		verify(courseProgressRepository).save(any(CourseProgress.class));
	}

	@Test
	@DisplayName("start: 비공개 협업 코스도 활성 멤버는 따라갈 수 있다")
	void start_privateCourse_activeMember_savesProgress() {
		Member member = member(7L);
		Course course = privateCourse(10L, member(2L));
		given(memberReader.getMember(7L)).willReturn(member);
		given(courseRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(course));
		given(courseMemberRepository.existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(10L, 7L)).willReturn(true);
		given(courseProgressRepository.findByMemberIdAndCourseId(7L, 10L)).willReturn(Optional.empty());
		given(courseProgressRepository.findAllByMemberIdAndStatus(7L, CourseProgressStatus.IN_PROGRESS))
			.willReturn(List.of());

		courseProgressService.start(7L, 10L);

		verify(courseProgressRepository).save(any(CourseProgress.class));
	}

	@Test
	@DisplayName("start: 비공개 코스를 제3자가 따라가려 하면 COURSE_FORBIDDEN")
	void start_privateCourse_thirdParty_throwsForbidden() {
		Member member = member(7L);
		Course course = privateCourse(10L, member(2L));
		given(memberReader.getMember(7L)).willReturn(member);
		given(courseRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(course));
		given(courseMemberRepository.existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(10L, 7L)).willReturn(false);

		assertThatThrownBy(() -> courseProgressService.start(7L, 10L))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
		verify(courseProgressRepository, never()).save(any());
	}

	@Test
	@DisplayName("start: 방장은 멤버 조회 없이 비공개 코스를 따라간다")
	void start_privateCourse_owner_skipsMemberCheck() {
		Member owner = member(7L);
		Course course = privateCourse(10L, owner);
		given(memberReader.getMember(7L)).willReturn(owner);
		given(courseRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(course));
		given(courseProgressRepository.findByMemberIdAndCourseId(7L, 10L)).willReturn(Optional.empty());
		given(courseProgressRepository.findAllByMemberIdAndStatus(7L, CourseProgressStatus.IN_PROGRESS))
			.willReturn(List.of());

		courseProgressService.start(7L, 10L);

		verify(courseProgressRepository).save(any(CourseProgress.class));
		verify(courseMemberRepository, never()).existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(any(), any());
	}

	@Test
	@DisplayName("목록 조회 시 모든 가게를 방문한 코스는 완료 처리하고 제외한다")
	void getOngoing_completedProgress_marksCompletedAndExcludesIt() {
		Member member = member(7L);
		Course course = course(10L);
		CourseProgress progress = CourseProgress.start(member, course, LocalDateTime.of(2026, 8, 1, 10, 0));
		given(memberReader.getMember(7L)).willReturn(member);
		given(courseProgressRepository.findAllByMemberIdAndStatus(7L, CourseProgressStatus.IN_PROGRESS))
			.willReturn(List.of(progress));
		given(courseProgressRepository.findAggregates(7L, List.of(10L)))
			.willReturn(List.of(aggregate(10L, 3, 3, LocalDateTime.of(2026, 8, 3, 10, 0))));

		List<CourseProgressResponse> result = courseProgressService.getOngoing(7L);

		assertThat(result).isEmpty();
		assertThat(progress.getStatus()).isEqualTo(CourseProgressStatus.FINISHED);
		assertThat(progress.getFinishedAt()).isNotNull();
	}

	@Test
	@DisplayName("목록은 빵 기록이 없으면 시작 시각을 기준으로 정렬한다")
	void getOngoing_withoutRecords_sortsByStartedAt() {
		Member member = member(7L);
		Course olderCourse = course(10L);
		Course newerCourse = course(11L);
		CourseProgress older = CourseProgress.start(member, olderCourse, LocalDateTime.of(2026, 8, 1, 10, 0));
		CourseProgress newer = CourseProgress.start(member, newerCourse, LocalDateTime.of(2026, 8, 2, 10, 0));
		given(memberReader.getMember(7L)).willReturn(member);
		given(courseProgressRepository.findAllByMemberIdAndStatus(7L, CourseProgressStatus.IN_PROGRESS))
			.willReturn(List.of(older, newer));
		given(courseProgressRepository.findAggregates(7L, List.of(10L, 11L)))
			.willReturn(List.of(aggregate(10L, 3, 0, null), aggregate(11L, 3, 0, null)));
		given(courseImageFileRepository.findAllByCourseIdIn(any())).willReturn(List.of());

		List<CourseProgressResponse> result = courseProgressService.getOngoing(7L);

		assertThat(result).extracting(CourseProgressResponse::id).containsExactly(11L, 10L);
	}

	@Test
	@DisplayName("코스 가게가 변경되면 모든 참여자의 완료 상태를 다시 계산한다")
	void refreshForCourse_updatesEveryParticipant() {
		Member completedMember = member(7L);
		Member incompleteMember = member(8L);
		Course course = course(10L);
		CourseProgress completed = CourseProgress.start(completedMember, course, LocalDateTime.now());
		CourseProgress incomplete = CourseProgress.start(incompleteMember, course, LocalDateTime.now());
		given(courseProgressRepository.findAllByCourseIdAndStatus(10L, CourseProgressStatus.IN_PROGRESS))
			.willReturn(List.of(completed, incomplete));
		given(courseProgressRepository.findMemberAggregates(10L))
			.willReturn(List.of(memberAggregate(7L, 3, 3), memberAggregate(8L, 3, 2)));

		courseProgressService.refreshForCourse(10L, true);

		assertThat(completed.getStatus()).isEqualTo(CourseProgressStatus.FINISHED);
		assertThat(incomplete.getStatus()).isEqualTo(CourseProgressStatus.IN_PROGRESS);
	}

	@Test
	@DisplayName("가게를 빼서 분모만 줄어든 경우엔 완주로 올리지 않고, 가게가 늘어 다시 다 채우면 그때 완주한다")
	void refreshForCourse_removalDoesNotPromote_additionThenVisitDoes() {
		Member follower = member(7L);
		Course course = course(10L);
		CourseProgress progress = CourseProgress.start(follower, course, LocalDateTime.now());
		given(courseProgressRepository.findAllByMemberIdAndStatus(7L, CourseProgressStatus.IN_PROGRESS))
			.willReturn(List.of(progress));
		given(courseProgressRepository.findAllByCourseIdAndStatus(10L, CourseProgressStatus.IN_PROGRESS))
			.willReturn(List.of(progress));
		// 방문 경로(refreshForMember)의 집계: 1단계 5곳 중 3곳 → 5단계 4곳 중 4곳
		given(courseProgressRepository.findAggregates(7L, List.of(10L)))
			.willReturn(List.of(aggregate(10L, 5, 3, null)))
			.willReturn(List.of(aggregate(10L, 4, 4, null)));
		// 가게 변경 경로(refreshForCourse)의 집계: 2단계 삭제 뒤 3곳 중 3곳 → 4단계 추가 뒤 4곳 중 3곳
		given(courseProgressRepository.findMemberAggregates(10L))
			.willReturn(List.of(memberAggregate(7L, 3, 3)))
			.willReturn(List.of(memberAggregate(7L, 4, 3)));

		// 1. 5곳 코스에 팔로워가 3곳 방문 — IN_PROGRESS
		courseProgressService.refreshForMember(7L);
		assertThat(progress.getStatus()).isEqualTo(CourseProgressStatus.IN_PROGRESS);

		// 2~3. 주인이 안 간 2곳을 삭제해 3/3 이 됐지만 승격은 막힌다
		courseProgressService.refreshForCourse(10L, false);
		assertThat(progress.getStatus()).isEqualTo(CourseProgressStatus.IN_PROGRESS);

		// 4. 주인이 1곳 추가 → 3/4. 정상 승격 경로는 살아 있지만 아직 다 못 채웠다
		courseProgressService.refreshForCourse(10L, true);
		assertThat(progress.getStatus()).isEqualTo(CourseProgressStatus.IN_PROGRESS);

		// 5. 팔로워가 그 1곳을 방문해 4/4 → FINISHED
		courseProgressService.refreshForMember(7L);
		assertThat(progress.getStatus()).isEqualTo(CourseProgressStatus.FINISHED);
	}

	@Test
	@DisplayName("승격이 막힌 호출은 다 채운 참여자가 있어도 아무도 완주시키지 않는다")
	void refreshForCourse_promotionDisallowed_finishesNobody() {
		Member member = member(7L);
		Course course = course(10L);
		CourseProgress progress = CourseProgress.start(member, course, LocalDateTime.now());
		given(courseProgressRepository.findAllByCourseIdAndStatus(10L, CourseProgressStatus.IN_PROGRESS))
			.willReturn(List.of(progress));
		given(courseProgressRepository.findMemberAggregates(10L))
			.willReturn(List.of(memberAggregate(7L, 3, 3)));

		courseProgressService.refreshForCourse(10L, false);

		assertThat(progress.getStatus()).isEqualTo(CourseProgressStatus.IN_PROGRESS);
		assertThat(progress.getFinishedAt()).isNull();
	}

	private Member member(Long id) {
		Member member = Member.builder().nickname("테스터").build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	private Course course(Long id) {
		Course course = Course.createCurated("코스 " + id, null);
		ReflectionTestUtils.setField(course, "id", id);
		return course;
	}

	private Course privateCourse(Long id, Member owner) {
		Course course = Course.createCustom("비공개 코스 " + id, owner, false);
		ReflectionTestUtils.setField(course, "id", id);
		return course;
	}

	private CourseProgressAggregateProjection aggregate(
			Long courseId,
			long storeCount,
			long visitedStoreCount,
			LocalDateTime lastTouchedAt) {
		return new CourseProgressAggregateProjection() {
			@Override
			public Long getCourseId() {
				return courseId;
			}

			@Override
			public long getStoreCount() {
				return storeCount;
			}

			@Override
			public long getVisitedStoreCount() {
				return visitedStoreCount;
			}

			@Override
			public LocalDateTime getLastTouchedAt() {
				return lastTouchedAt;
			}
		};
	}

	private CourseProgressMemberAggregateProjection memberAggregate(
			Long memberId,
			long storeCount,
			long visitedStoreCount) {
		return new CourseProgressMemberAggregateProjection() {
			@Override
			public Long getMemberId() {
				return memberId;
			}

			@Override
			public long getStoreCount() {
				return storeCount;
			}

			@Override
			public long getVisitedStoreCount() {
				return visitedStoreCount;
			}
		};
	}
}
