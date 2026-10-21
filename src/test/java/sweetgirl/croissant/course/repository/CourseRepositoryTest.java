package sweetgirl.croissant.course.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseMember;
import sweetgirl.croissant.course.enums.CourseVisibility;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.member.entity.Member;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:course-repo-test;MODE=PostgreSQL;"
		+ "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class CourseRepositoryTest {

	@MockitoBean
	private S3Service s3Service;

	@Autowired
	private CourseRepository courseRepository;

	@Autowired
	private EntityManager entityManager;

	@Test
	@DisplayName("락 조회는 살아있는 코스만 돌려주고, 지운 코스는 비어 있다")
	void findWithLock_returnsOnlyLiveCourse() {
		Member owner = new Member("방장");
		entityManager.persist(owner);
		Course live = Course.createCustom("살아있는 코스", owner, false);
		Course deleted = Course.createCustom("지운 코스", owner, false);
		deleted.softDelete();
		entityManager.persist(live);
		entityManager.persist(deleted);
		entityManager.flush();

		assertThat(courseRepository.findWithLockByIdAndDeletedAtIsNull(live.getId()))
			.get()
			.extracting(Course::getTitle)
			.isEqualTo("살아있는 코스");
		assertThat(courseRepository.findWithLockByIdAndDeletedAtIsNull(deleted.getId())).isEmpty();
	}

	@Test
	@DisplayName("공유 토큰으로 살아있는 코스를 찾는다 — 지운 코스·모르는 토큰은 비어 있다")
	void findByShareToken_returnsOnlyLiveCourse() {
		Member owner = new Member("방장");
		entityManager.persist(owner);
		Course live = Course.createCustom("링크 공개", owner, false);
		live.updateVisibility(CourseVisibility.UNLISTED);
		live.issueShareToken("live-token");
		Course deleted = Course.createCustom("지운 코스", owner, false);
		deleted.issueShareToken("deleted-token");
		deleted.softDelete();
		entityManager.persist(live);
		entityManager.persist(deleted);
		entityManager.flush();

		assertThat(courseRepository.findByShareTokenAndDeletedAtIsNull("live-token"))
			.get()
			.extracting(Course::getTitle)
			.isEqualTo("링크 공개");
		assertThat(courseRepository.findByShareTokenAndDeletedAtIsNull("deleted-token")).isEmpty();
		assertThat(courseRepository.findByShareTokenAndDeletedAtIsNull("unknown")).isEmpty();
	}

	@Test
	@DisplayName("bumpVersion 은 부를 때마다 1씩 올리고, 엔티티는 refresh 해야 새 값을 본다")
	void bumpVersion_incrementsByOne() {
		Member owner = new Member("방장");
		entityManager.persist(owner);
		Course course = Course.createCustom("코스", owner, false);
		entityManager.persist(course);
		entityManager.flush();
		entityManager.refresh(course);
		assertThat(course.getVersion()).isZero();

		assertThat(courseRepository.bumpVersion(course.getId())).isEqualTo(1);
		assertThat(courseRepository.bumpVersion(course.getId())).isEqualTo(1);
		entityManager.refresh(course);

		assertThat(course.getVersion()).isEqualTo(2);
	}

	@Test
	@DisplayName("bumpVersion 은 updated_at 도 올린다 — 내 목록의 최근 수정순이 가게·메모·멤버 변경을 본다")
	void bumpVersion_touchesUpdatedAt() {
		Member owner = new Member("방장");
		entityManager.persist(owner);
		Course course = Course.createCustom("코스", owner, false);
		entityManager.persist(course);
		entityManager.flush();
		// 같은 순간에 돌면 갱신이 안 보이므로 과거로 밀어둔다
		LocalDateTime past = LocalDateTime.of(2026, 1, 1, 0, 0);
		entityManager.createNativeQuery("UPDATE courses SET updated_at = :past WHERE id = :id")
			.setParameter("past", past)
			.setParameter("id", course.getId())
			.executeUpdate();

		courseRepository.bumpVersion(course.getId());
		entityManager.refresh(course);

		assertThat(course.getUpdatedAt()).isAfter(past);
	}

	@Test
	@DisplayName("엔티티를 고쳐 flush 해도 version 은 되돌아가지 않는다 — JPA 쪽에선 읽기 전용 컬럼이다")
	void entityFlush_doesNotOverwriteVersion() {
		Member owner = new Member("방장");
		entityManager.persist(owner);
		Course course = Course.createCustom("코스", owner, false);
		entityManager.persist(course);
		entityManager.flush();

		courseRepository.bumpVersion(course.getId());
		course.updateTitle("고친 제목"); // 메모리의 version 은 아직 0
		entityManager.flush();
		entityManager.refresh(course);

		assertThat(course.getTitle()).isEqualTo("고친 제목");
		assertThat(course.getVersion()).isEqualTo(1);
	}

	@Test
	@DisplayName("기간에 걸친 내 빵투어 — 내가 만든 것과 일행인 것만, 날짜 없는 것·남의 것·지운 것은 빠지고 시작일 순이다")
	void findMyDatedCourses_ownAndJoinedWithinRange() {
		LocalDate today = LocalDate.of(2026, 10, 14);
		Member me = new Member("나");
		Member friend = new Member("친구");
		entityManager.persist(me);
		entityManager.persist(friend);
		Course soon = dated("모레 빵투어", me, today.plusDays(2), null);
		Course ongoing = dated("어제부터 내일까지", me, today.minusDays(1), today.plusDays(1));
		Course joined = dated("친구가 만든 오늘 빵투어", friend, today, null);
		entityManager.persist(CourseMember.of(joined, me));
		dated("끝난 빵투어", me, today.minusDays(5), today.minusDays(3));
		dated("한참 뒤 빵투어", me, today.plusDays(20), null);
		dated("친구만의 빵투어", friend, today, null);
		entityManager.persist(Course.createCustom("날짜 없는 빵투어", me, false));
		Course deleted = dated("지운 빵투어", me, today, null);
		deleted.softDelete();
		entityManager.flush();

		assertThat(courseRepository.findMyDatedCourses(me.getId(), today, today.plusDays(7)))
			.extracting(Course::getTitle)
			.containsExactly(ongoing.getTitle(), joined.getTitle(), soon.getTitle());
	}

	private Course dated(String title, Member owner, LocalDate startDate, LocalDate endDate) {
		Course course = Course.createCustom(title, owner, false);
		course.updateDates(startDate, endDate);
		entityManager.persist(course);
		return course;
	}
}
