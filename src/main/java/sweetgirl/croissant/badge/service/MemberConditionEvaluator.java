package sweetgirl.croissant.badge.service;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.badge.enums.ConditionType;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.course.enums.CourseProgressStatus;
import sweetgirl.croissant.course.enums.CourseType;
import sweetgirl.croissant.course.repository.CourseProgressRepository;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.store.repository.SavedStoreRepository;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberConditionEvaluator {

	private final SavedStoreRepository savedStoreRepository;
	private final CourseRepository courseRepository;
	private final BreadRecordRepository breadRecordRepository;
	private final CourseProgressRepository courseProgressRepository;

	public MemberConditionSnapshot snapshot(Long memberId) {
		return new MemberConditionSnapshot(
			savedStoreRepository.countByMemberId(memberId),
			courseRepository.countByMemberIdAndTypeInAndDeletedAtIsNull(
				memberId, List.of(CourseType.CUSTOM, CourseType.FORKED)),
			breadRecordRepository.countByMemberIdAndDeletedAtIsNull(memberId),
			courseProgressRepository.countByMemberIdAndStatus(memberId, CourseProgressStatus.FINISHED),
			breadRecordRepository.findMaxStoreVisitCount(memberId));
	}

	public ConditionProgress evaluate(
			ConditionType conditionType,
			Integer threshold,
			MemberConditionSnapshot snapshot) {
		int target = conditionType.target(threshold);
		long current = switch (conditionType) {
			case STARTER_ALL -> starterProgress(snapshot);
			case SAVED_STORE_COUNT -> snapshot.savedStoreCount();
			case COURSE_COUNT -> snapshot.courseCount();
			case RECORD_COUNT -> snapshot.recordCount();
			case TOUR_FINISH_COUNT -> snapshot.tourFinishCount();
			case STORE_REVISIT -> snapshot.maxStoreVisitCount();
		};
		return new ConditionProgress(current, target);
	}

	private long starterProgress(MemberConditionSnapshot snapshot) {
		long progress = snapshot.savedStoreCount() > 0 ? 1 : 0;
		progress += snapshot.courseCount() > 0 ? 1 : 0;
		progress += snapshot.recordCount() > 0 ? 1 : 0;
		return progress;
	}
}
