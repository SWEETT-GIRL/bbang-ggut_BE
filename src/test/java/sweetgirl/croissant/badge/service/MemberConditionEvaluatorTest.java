package sweetgirl.croissant.badge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.badge.enums.ConditionType;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.course.enums.CourseProgressStatus;
import sweetgirl.croissant.course.enums.CourseType;
import sweetgirl.croissant.course.repository.CourseProgressRepository;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.store.repository.SavedStoreRepository;

@ExtendWith(MockitoExtension.class)
class MemberConditionEvaluatorTest {

	@Mock
	SavedStoreRepository savedStoreRepository;
	@Mock
	CourseRepository courseRepository;
	@Mock
	BreadRecordRepository breadRecordRepository;
	@Mock
	CourseProgressRepository courseProgressRepository;

	@InjectMocks
	MemberConditionEvaluator evaluator;

	MemberConditionSnapshot snapshot;

	@BeforeEach
	void setUp() {
		given(savedStoreRepository.countByMemberId(7L)).willReturn(1L);
		given(courseRepository.countByMemberIdAndTypeInAndDeletedAtIsNull(
			7L, List.of(CourseType.CUSTOM, CourseType.FORKED))).willReturn(1L);
		given(breadRecordRepository.countByMemberIdAndDeletedAtIsNull(7L)).willReturn(12L);
		given(courseProgressRepository.countByMemberIdAndStatus(7L, CourseProgressStatus.FINISHED))
			.willReturn(2L);
		given(breadRecordRepository.findMaxStoreVisitCount(7L)).willReturn(3L);
		snapshot = evaluator.snapshot(7L);
	}

	@Test
	void starterAll_countsEachCompletedStarterAction() {
		ConditionProgress progress = evaluator.evaluate(ConditionType.STARTER_ALL, null, snapshot);

		assertThat(progress.current()).isEqualTo(3);
		assertThat(progress.target()).isEqualTo(3);
		assertThat(progress.done()).isTrue();
	}

	@Test
	void countConditions_shareTheSameSnapshot() {
		ConditionProgress recordProgress = evaluator.evaluate(ConditionType.RECORD_COUNT, 10, snapshot);

		assertThat(recordProgress.current()).isEqualTo(12);
		assertThat(recordProgress.done()).isTrue();
		assertThat(evaluator.evaluate(ConditionType.TOUR_FINISH_COUNT, 5, snapshot).done()).isFalse();
		assertThat(evaluator.evaluate(ConditionType.STORE_REVISIT, 3, snapshot).done()).isTrue();
	}
}
