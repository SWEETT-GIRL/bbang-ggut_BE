package sweetgirl.croissant.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
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

import sweetgirl.croissant.course.dto.projection.CourseProgressAggregateProjection;
import sweetgirl.croissant.course.dto.projection.CourseProgressMemberAggregateProjection;
import sweetgirl.croissant.course.dto.response.CourseProgressResponse;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseProgress;
import sweetgirl.croissant.course.enums.CourseProgressStatus;
import sweetgirl.croissant.course.repository.CourseImageFileRepository;
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

		courseProgressService.refreshForCourse(10L);

		assertThat(completed.getStatus()).isEqualTo(CourseProgressStatus.FINISHED);
		assertThat(incomplete.getStatus()).isEqualTo(CourseProgressStatus.IN_PROGRESS);
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
