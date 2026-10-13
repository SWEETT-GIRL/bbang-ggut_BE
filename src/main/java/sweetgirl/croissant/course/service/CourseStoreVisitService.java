package sweetgirl.croissant.course.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordCreateResponse;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.service.BreadRecordCommandService;
import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.dto.response.CourseVisitCandidateResponse;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseProgress;
import sweetgirl.croissant.course.entity.CourseStoreVisit;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.CourseStoreRepository;
import sweetgirl.croissant.course.repository.CourseStoreVisitRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.reward.dto.response.RewardItemResponse;
import sweetgirl.croissant.reward.dto.response.TourRewardResponse;
import sweetgirl.croissant.reward.service.CrumbGrantService;
import sweetgirl.croissant.reward.service.TourRewardService;
import sweetgirl.croissant.store.entity.Store;

/**
 * 코스의 가게를 "다녀왔다"고 표시하고, 그 표시를 빵로그 기록에 잇는다.
 *
 * 권한은 편집 권한이 아니라 <b>조회 권한</b>으로 본다. 방문 체크는 코스를 고치는 일이
 * 아니라 각자의 진행 상태이기 때문이다. 남의 공개 코스를 따라가며 체크하는 것이 정상이다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CourseStoreVisitService {

	private final CourseRepository courseRepository;
	private final CourseStoreRepository courseStoreRepository;
	private final CourseStoreVisitRepository courseStoreVisitRepository;
	private final BreadRecordRepository breadRecordRepository;
	private final MemberReader memberReader;
	private final CourseProgressService courseProgressService;
	private final CourseAccessPolicy courseAccessPolicy;
	private final BreadRecordCommandService breadRecordCommandService;
	private final TourRewardService tourRewardService;

	/** 이미 쓴 기록을 골라 다녀왔다고 표시한다. 기록 보상은 그 기록을 쓸 때 받았으므로 여기서는 다녀옴·완주만 본다. */
	public TourRewardResponse markVisited(Long memberId, Long courseId, Long storeId, Long breadRecordId) {
		Member member = memberReader.getMember(memberId);
		Course course = getViewableCourse(courseId, memberId);
		Store store = getCourseStore(courseId, storeId);
		BreadRecord record = getOwnedRecordOfStore(breadRecordId, memberId, storeId);

		link(member, course, store, record);
		return new TourRewardResponse(record.getId(), rewardVisit(member, course, storeId));
	}

	/**
	 * 한 번 탭으로 다녀왔다고 표시한다 — 가게만 있는 가벼운 기록을 만들어 잇는다.
	 * 이미 표시해 둔 가게면 기록을 또 만들지 않고 지금 상태를 돌려준다(두 번 눌러도 기록은 하나다).
	 */
	public TourRewardResponse markVisitedQuick(Long memberId, Long courseId, Long storeId, UUID requestId) {
		Member member = memberReader.getMember(memberId);
		Course course = getViewableCourse(courseId, memberId);
		Store store = getCourseStore(courseId, storeId);

		Optional<CourseStoreVisit> alreadyVisited = courseStoreVisitRepository
			.findByIdCourseIdAndIdMemberIdAndIdStoreId(courseId, memberId, storeId)
			.filter(visit -> !visit.isDeleted());
		if (alreadyVisited.isPresent()) {
			BreadRecord linked = alreadyVisited.get().getBreadRecord();
			return new TourRewardResponse(linked == null ? null : linked.getId(), List.of());
		}

		BreadRecordCreateResponse created = breadRecordCommandService.createTourQuickRecord(
			memberId, courseId, store, requestId);
		BreadRecord record = breadRecordRepository.findByIdAndDeletedAtIsNull(created.recordId())
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_VISIT_RECORD_MISMATCH));
		link(member, course, store, record);

		List<RewardItemResponse> rewards = new ArrayList<>(created.rewards());
		rewards.addAll(rewardVisit(member, course, storeId));
		return new TourRewardResponse(record.getId(), rewards);
	}

	private void link(Member member, Course course, Store store, BreadRecord record) {
		courseStoreVisitRepository
			.findByIdCourseIdAndIdMemberIdAndIdStoreId(course.getId(), member.getId(), store.getId())
			.ifPresentOrElse(
				// 다른 기록으로 바꾸거나, 껐던 체크를 다시 켜는 경우 모두 여기로 온다.
				visit -> visit.relink(record, record.getEatenAt()),
				() -> courseStoreVisitRepository.save(
					CourseStoreVisit.of(course, member, store, record, record.getEatenAt())));
		courseStoreVisitRepository.flush();
	}

	/**
	 * 방문을 표시한 뒤의 보상 — 다녀옴, 그리고 이 표시로 채워진 완주·사진 완주.
	 * "시작하기"를 누르지 않은 회원도 여기서 진행이 시작된다(그래야 완주가 따라온다).
	 */
	private List<RewardItemResponse> rewardVisit(Member member, Course course, Long storeId) {
		LocalDate today = LocalDate.now(CrumbGrantService.ZONE);
		CourseProgress progress = courseProgressService.ensureStartedForVisit(member, course, today);
		List<RewardItemResponse> rewards = new ArrayList<>(
			tourRewardService.rewardVisit(member, course, storeId, progress, today));
		rewards.addAll(tourRewardService.rewardCompletions(
			member, courseProgressService.refreshForMember(member.getId()), today));
		rewards.addAll(tourRewardService.rewardPhotoCompletions(member, today));
		return rewards;
	}

	public void unmarkVisited(Long memberId, Long courseId, Long storeId) {
		memberReader.getMember(memberId);
		getViewableCourse(courseId, memberId);

		CourseStoreVisit visit = courseStoreVisitRepository
			.findByIdCourseIdAndIdMemberIdAndIdStoreId(courseId, memberId, storeId)
			.filter(found -> !found.isDeleted())
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_VISIT_NOT_FOUND));
		visit.softDelete();

		courseProgressService.refreshForMember(memberId);
	}

	@Transactional(readOnly = true)
	public List<CourseVisitCandidateResponse> getCandidates(Long memberId, Long courseId, Long storeId) {
		memberReader.getMember(memberId);
		getViewableCourse(courseId, memberId);
		getCourseStore(courseId, storeId);

		// 이 회원이 이미 어딘가에 연결해 쓰고 있는 기록 — 화면이 "다른 코스에서도 쓰는 중"을 알리게.
		Set<Long> linkedRecordIds = courseStoreVisitRepository
			.findAllByIdMemberIdAndDeletedAtIsNull(memberId).stream()
			.map(CourseStoreVisit::getBreadRecord)
			.filter(java.util.Objects::nonNull)
			.map(BreadRecord::getId)
			.collect(Collectors.toSet());

		return breadRecordRepository
			.findAllByMemberIdAndStoreIdAndDeletedAtIsNullOrderByEatenAtDesc(memberId, storeId).stream()
			.map(record -> CourseVisitCandidateResponse.of(record, linkedRecordIds.contains(record.getId())))
			.toList();
	}

	/**
	 * 비공개 코스는 방장과 활성 멤버만 본다. 편집 권한이 아니라 조회 권한 기준이다.
	 * 링크 공개(UNLISTED)도 제3자는 못 한다 — 방문 체크는 따라가기의 일부라 원본에 매달린다.
	 */
	private Course getViewableCourse(Long courseId, Long memberId) {
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		courseAccessPolicy.requireViewable(course, memberId, null);
		return course;
	}

	private Store getCourseStore(Long courseId, Long storeId) {
		return courseStoreRepository.findByCourseIdAndStoreIdAndDeletedAtIsNull(courseId, storeId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_STORE_NOT_FOUND))
			.getStore();
	}

	/** 남의 기록이나 다른 가게 기록으로는 체크할 수 없다. */
	private BreadRecord getOwnedRecordOfStore(Long breadRecordId, Long memberId, Long storeId) {
		BreadRecord record = breadRecordRepository.findByIdAndDeletedAtIsNull(breadRecordId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_VISIT_RECORD_MISMATCH));
		boolean mine = record.getMember() != null && record.getMember().getId().equals(memberId);
		boolean sameStore = Optional.ofNullable(record.getStore())
			.map(Store::getId)
			.filter(storeId::equals)
			.isPresent();
		if (!mine || !sameStore) {
			throw new CourseException(CourseErrorCode.COURSE_VISIT_RECORD_MISMATCH);
		}
		return record;
	}
}
