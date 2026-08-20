package sweetgirl.croissant.alarm.service;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.dto.request.CourseNotificationReadRequest;
import sweetgirl.croissant.alarm.dto.response.CourseSaveNotificationListResponse;
import sweetgirl.croissant.alarm.dto.response.CourseSaveNotificationResponse;
import sweetgirl.croissant.alarm.dto.response.UnreadCountResponse;
import sweetgirl.croissant.alarm.entity.CourseSaveNotification;
import sweetgirl.croissant.alarm.repository.CourseSaveNotificationRepository;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;

/**
 * "누가 내 빵투어를 찜했는지" 알림의 적재·조회.
 *
 * <p>자기 코스 찜과 비공개 코스 찜은 {@code SavedCourseCommandService}가 이미 막으므로
 * 여기서 다시 거르지 않는다. 다만 적재는 찜하기와 다른 트랜잭션이라, 그 사이 코스나 회원이
 * 사라졌을 수 있어 조회 실패는 조용히 넘긴다 — 알림 하나 때문에 로그를 시끄럽게 할 일이 아니다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CourseSaveNotificationService {

	private static final int MAX_PAGE_SIZE = 50;

	private static final Logger log = LoggerFactory.getLogger(CourseSaveNotificationService.class);

	private final CourseSaveNotificationRepository courseSaveNotificationRepository;
	private final CourseRepository courseRepository;
	private final MemberRepository memberRepository;

	/**
	 * 알림 한 줄을 쌓는다. 같은 사람이 같은 코스를 다시 찜했으면 새 줄을 만들지 않고
	 * 시각만 갱신해 맨 위로 올린다((actor, course) 유니크).
	 */
	@Transactional
	public void record(Long courseId, Long actorId, Long ownerId) {
		LocalDateTime now = LocalDateTime.now();

		courseSaveNotificationRepository.findByActorIdAndCourseId(actorId, courseId)
			.ifPresentOrElse(
				existing -> existing.renew(now),
				() -> create(courseId, actorId, ownerId, now));
	}

	private void create(Long courseId, Long actorId, Long ownerId, LocalDateTime now) {
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId).orElse(null);
		Member actor = memberRepository.findByIdAndDeletedAtIsNull(actorId).orElse(null);
		Member owner = memberRepository.findByIdAndDeletedAtIsNull(ownerId).orElse(null);
		if (course == null || actor == null || owner == null) {
			log.info("코스 찜 알림 skip — 대상이 사라졌다 [courseId={}, actorId={}, ownerId={}]",
				courseId, actorId, ownerId);
			return;
		}
		courseSaveNotificationRepository.save(CourseSaveNotification.create(owner, actor, course, now));
	}

	public CourseSaveNotificationListResponse getMyNotifications(Long memberId, int page, int size) {
		Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
		Slice<CourseSaveNotification> slice = courseSaveNotificationRepository.findByOwner(memberId, pageable);
		return new CourseSaveNotificationListResponse(
			slice.getContent().stream().map(CourseSaveNotificationResponse::from).toList(),
			slice.hasNext());
	}

	public UnreadCountResponse getUnreadCount(Long memberId) {
		return new UnreadCountResponse(courseSaveNotificationRepository.countUnreadByOwner(memberId));
	}

	/**
	 * 읽음 처리. 남의 알림 id를 섞어 보내도 owner로 걸러져 조용히 무시된다 —
	 * 남의 알림을 읽음 처리하려 한 것이 의미 있는 실패는 아니라서 예외로 만들지 않는다.
	 */
	@Transactional
	public void markAsRead(Long memberId, CourseNotificationReadRequest request) {
		LocalDateTime now = LocalDateTime.now();
		if (request == null || request.isAll()) {
			courseSaveNotificationRepository.markAllAsRead(memberId, now);
			return;
		}
		List<CourseSaveNotification> targets = courseSaveNotificationRepository.findByOwnerIdAndIdIn(memberId,
			request.getNotificationIds());
		targets.forEach(notification -> notification.markAsRead(now));
	}
}
