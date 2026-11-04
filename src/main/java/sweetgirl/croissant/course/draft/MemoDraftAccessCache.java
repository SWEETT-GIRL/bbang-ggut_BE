package sweetgirl.croissant.course.draft;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.service.CourseAccessPolicy;
import sweetgirl.croissant.member.service.MemberRestrictionReader;

/**
 * PUT memo-draft 의 "이 사람이 이 코스에 메모를 써도 되는가" — 편집 권한(방장·활성 멤버, 큐레이션 제외) 과 쓰기 제한(밴).
 *
 * <p>0.5초마다 오는 요청마다 DB 를 읽지 않도록 통과한 판정만 10초 들고 있다. 거절은 캐시하지 않는다 — 초대를 막 수락한 사람이
 * 10초 동안 막히면 안 되고, 거절을 반복하는 요청은 레이트리밋이 막는다. 틀리는 방향은 하나다: 나간 사람·방금 밴된 사람의 초안이
 * 최대 10초 Redis 에 머문다. 플러셔가 반영 직전에 다시 판정해 버린다.
 */
@Component
@ConditionalOnExpression(CourseDraftConfig.REDIS_CONFIGURED)
public class MemoDraftAccessCache {

	private final CourseRepository courseRepository;
	private final CourseAccessPolicy courseAccessPolicy;
	private final MemberRestrictionReader memberRestrictionReader;
	private final Cache<String, Boolean> allowed;

	/** 생성자가 둘(테스트용 Ticker 주입)이라 Spring 이 쓸 쪽을 가리킨다 — 없으면 기본 생성자를 찾다 죽는다. */
	@Autowired
	public MemoDraftAccessCache(CourseRepository courseRepository, CourseAccessPolicy courseAccessPolicy,
			MemberRestrictionReader memberRestrictionReader, CourseDraftProperties properties) {
		this(courseRepository, courseAccessPolicy, memberRestrictionReader, properties, Ticker.systemTicker());
	}

	MemoDraftAccessCache(CourseRepository courseRepository, CourseAccessPolicy courseAccessPolicy,
			MemberRestrictionReader memberRestrictionReader, CourseDraftProperties properties, Ticker ticker) {
		this.courseRepository = courseRepository;
		this.courseAccessPolicy = courseAccessPolicy;
		this.memberRestrictionReader = memberRestrictionReader;
		this.allowed = Caffeine.newBuilder()
			.expireAfterWrite(properties.getAccessCacheTtl())
			.maximumSize(100_000)
			.ticker(ticker)
			.build();
	}

	/** 못 쓰면 던진다 — 코스 없음 404, 권한 없음 403, 쓰기 제한 403. */
	public void requireEditable(Long courseId, Long memberId) {
		String key = courseId + ":" + memberId;
		if (Boolean.TRUE.equals(allowed.getIfPresent(key))) {
			return;
		}
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		courseAccessPolicy.requireEditable(course, memberId);
		// 초안은 DRAFT 신호로 일행에게 바로 보이는 글이다 — PATCH 의 메모 저장과 같은 문을 지난다
		memberRestrictionReader.validateCanWrite(memberId);
		allowed.put(key, Boolean.TRUE);
	}
}
