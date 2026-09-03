package sweetgirl.croissant.course.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
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
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.member.entity.Member;

/**
 * 파생 쿼리는 이름이 곧 조건이라 한 번은 실제 DB 로 돌려 본다.
 * 게스트 이관 네이티브 쿼리는 PK 충돌 방어가 핵심이라 실제로 충돌 상황을 만든다.
 */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:course-member-test;MODE=PostgreSQL;"
		+ "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class CourseMemberRepositoryTest {

	@MockitoBean
	private S3Service s3Service;

	@Autowired
	private CourseMemberRepository courseMemberRepository;

	@Autowired
	private EntityManager entityManager;

	private Member owner;
	private Member first;
	private Member second;
	private Course course;

	@BeforeEach
	void setUp() {
		owner = persistMember("방장");
		first = persistMember("첫째");
		second = persistMember("둘째");
		course = persistCourse(owner, "성수 소금빵 투어");
	}

	private Member persistMember(String nickname) {
		Member member = new Member(nickname);
		entityManager.persist(member);
		return member;
	}

	private Course persistCourse(Member courseOwner, String title) {
		Course created = Course.createCustom(title, courseOwner, false);
		entityManager.persist(created);
		return created;
	}

	private CourseMember persistMemberRow(Course target, Member member, String displayName) {
		CourseMember row = CourseMember.of(target, member, displayName);
		entityManager.persist(row);
		entityManager.flush();
		return row;
	}

	@Test
	@DisplayName("활성 멤버 판정과 정원 카운트는 나간 사람을 세지 않는다")
	void activeQueries_ignoreLeftMembers() {
		persistMemberRow(course, owner, "방장");
		CourseMember left = persistMemberRow(course, first, "첫째");
		left.leave();
		entityManager.flush();

		assertThat(courseMemberRepository.existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(
			course.getId(), owner.getId())).isTrue();
		assertThat(courseMemberRepository.existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(
			course.getId(), first.getId())).isFalse();
		assertThat(courseMemberRepository.countByIdCourseIdAndDeletedAtIsNull(course.getId())).isEqualTo(1);
		// 재참여용 조회는 나간 행도 같은 PK 로 찾아야 한다
		assertThat(courseMemberRepository.findByIdCourseIdAndIdMemberId(course.getId(), first.getId()))
			.isPresent();
		assertThat(courseMemberRepository.findAllByIdMemberIdAndDeletedAtIsNull(first.getId())).isEmpty();
	}

	@Test
	@DisplayName("참여자 목록은 참여 시각 오름차순이다")
	void findAllActive_orderedByJoinedAt() throws InterruptedException {
		persistMemberRow(course, owner, "방장");
		Thread.sleep(2);
		persistMemberRow(course, second, "둘째");
		Thread.sleep(2);
		persistMemberRow(course, first, "첫째");

		List<CourseMember> members = courseMemberRepository
			.findAllByIdCourseIdAndDeletedAtIsNullOrderByJoinedAtAsc(course.getId());

		assertThat(members).extracting(CourseMember::getDisplayName)
			.containsExactly("방장", "둘째", "첫째");
	}

	@Test
	@DisplayName("게스트 이관: 회원이 이미 같은 코스의 행을 가지면 게스트 행을 버리고, 나머지는 회원 id 로 옮긴다")
	void guestMigration_removesDuplicatesThenMoves() {
		Member guest = persistMember("게스트");
		Member target = persistMember("회원");
		Course otherCourse = persistCourse(guest, "게스트가 만든 코스");
		persistMemberRow(course, guest, "게스트");
		persistMemberRow(course, target, "회원"); // 같은 코스에 둘 다 있다 → 충돌
		persistMemberRow(otherCourse, guest, "게스트"); // 게스트만 있다 → 이관

		int removed = courseMemberRepository.deleteGuestDuplicates(guest.getId(), target.getId());
		int moved = courseMemberRepository.moveOwner(guest.getId(), target.getId());

		assertThat(removed).isEqualTo(1);
		assertThat(moved).isEqualTo(1);
		assertThat(courseMemberRepository.findAllByIdMemberIdAndDeletedAtIsNull(guest.getId())).isEmpty();
		assertThat(courseMemberRepository.findAllByIdMemberIdAndDeletedAtIsNull(target.getId()))
			.extracting(row -> row.getId().getCourseId())
			.containsExactlyInAnyOrder(course.getId(), otherCourse.getId());
	}
}
