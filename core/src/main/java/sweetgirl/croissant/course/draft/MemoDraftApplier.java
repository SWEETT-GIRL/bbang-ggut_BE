package sweetgirl.croissant.course.draft;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

/**
 * 코스 하나의 초안을 트랜잭션 하나로 DB 에 반영한다 — 타자 N번이 코스 행 락 1번·버전 bump 1번이 된다.
 *
 * <p>락 순서는 PATCH 와 같다(courses → course_stores). 반영 직전에 다시 판정해 지운 코스·지운 가게·나간 멤버·쓰기 제한 회원의
 * 초안은 버린다(PUT 의 10초 권한 캐시가 틀릴 수 있는 방향을 여기서 바로잡는다). 버린 것도 "처리됨"으로 돌려줘 Redis 에서 지워진다.
 * 메모 쓰기 분기는 {@code CourseCommandService.upsertMyMemo} 와 같다 — 빈 내용은 지우기, 지워진 행은 되살리기, 행이 없으면 INSERT.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Transactional
@ConditionalOnExpression(CourseDraftConfig.REDIS_CONFIGURED)
public class MemoDraftApplier {

	private final CourseRepository courseRepository;
	private final CourseStoreRepository courseStoreRepository;
	private final CourseStoreMemoRepository courseStoreMemoRepository;
	private final MemberRepository memberRepository;
	private final CourseAccessPolicy courseAccessPolicy;
	private final MemberRestrictionReader memberRestrictionReader;
	private final CourseVersionBumper courseVersionBumper;

	/** @return 처리한(반영했거나 버린) 필드 → seq. 호출자가 이 seq 이하의 초안만 Redis 에서 지운다 */
	public Map<DraftField, Long> apply(long courseId, List<DraftEntry> entries) {
		Map<DraftField, Long> handled = new HashMap<>();
		entries.forEach(entry -> handled.put(entry.field(), entry.seq()));
		if (entries.isEmpty()) {
			return handled;
		}
		Optional<Course> found = courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId);
		if (found.isEmpty()) {
			log.info("[메모 초안] 지운 코스의 초안 {}건을 버린다 courseId={}", entries.size(), courseId);
			return handled;
		}
		Course course = found.get();
		Map<Long, CourseStore> stopsByStoreId = courseStoreRepository
			.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId).stream()
			.collect(Collectors.toMap(stop -> stop.getStore().getId(), Function.identity(), (first, second) -> first));
		Map<Long, Boolean> writableByMemberId = new HashMap<>();

		boolean changed = false;
		for (DraftEntry entry : entries) {
			CourseStore stop = stopsByStoreId.get(entry.field().storeId());
			if (stop == null) {
				continue;
			}
			boolean writable = writableByMemberId.computeIfAbsent(entry.field().memberId(),
				memberId -> canWrite(course, memberId));
			if (!writable) {
				continue;
			}
			changed |= applyEntry(stop, entry);
		}
		if (changed) {
			courseVersionBumper.bump(courseId);
		}
		return handled;
	}

	private boolean canWrite(Course course, Long memberId) {
		return courseAccessPolicy.canEdit(course, memberId) && !memberRestrictionReader.isWriteRestricted(memberId);
	}

	private boolean applyEntry(CourseStore stop, DraftEntry entry) {
		Long memberId = entry.field().memberId();
		Optional<CourseStoreMemo> existing = courseStoreMemoRepository
			.findByIdCourseStoreIdAndIdMemberId(stop.getId(), memberId);
		if (existing.isPresent()) {
			return existing.get().applyIfNewer(entry.content(), entry.seq());
		}
		if (entry.isDeletion()) {
			return false;
		}
		Member author = memberRepository.getReferenceById(memberId);
		courseStoreMemoRepository.save(CourseStoreMemo.of(stop, author, entry.content(), entry.seq()));
		return true;
	}
}
