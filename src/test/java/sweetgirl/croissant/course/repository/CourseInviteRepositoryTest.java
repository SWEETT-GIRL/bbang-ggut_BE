package sweetgirl.croissant.course.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.hibernate.Hibernate;
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
import sweetgirl.croissant.course.entity.CourseInvite;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.member.entity.Member;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:course-invite-test;MODE=PostgreSQL;"
		+ "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class CourseInviteRepositoryTest {

	@MockitoBean
	private S3Service s3Service;

	@Autowired
	private CourseInviteRepository courseInviteRepository;

	@Autowired
	private EntityManager entityManager;

	private Member owner;
	private Course course;

	@BeforeEach
	void setUp() {
		owner = persistMember("방장");
		course = Course.createCustom("코스", owner, false);
		entityManager.persist(course);
	}

	private Member persistMember(String nickname) {
		Member member = new Member(nickname);
		entityManager.persist(member);
		return member;
	}

	private CourseInvite persistInvite(Member createdBy, String token) {
		CourseInvite invite = CourseInvite.of(course, createdBy, token, LocalDateTime.now().plusDays(7));
		entityManager.persist(invite);
		entityManager.flush();
		return invite;
	}

	@Test
	@DisplayName("무효화된 토큰은 토큰으로도 코스로도 찾지 않는다")
	void revokedInvite_isInvisible() throws InterruptedException {
		CourseInvite old = persistInvite(owner, "old-token");
		Thread.sleep(2);
		CourseInvite latest = persistInvite(owner, "new-token");
		old.revoke();
		entityManager.flush();

		assertThat(courseInviteRepository.findByTokenAndDeletedAtIsNull("old-token")).isEmpty();
		assertThat(courseInviteRepository.findByTokenAndDeletedAtIsNull("new-token")).isPresent();
		assertThat(courseInviteRepository.findFirstByCourseIdAndDeletedAtIsNullOrderByCreatedAtDesc(course.getId()))
			.get()
			.extracting(CourseInvite::getId)
			.isEqualTo(latest.getId());
	}

	@Test
	@DisplayName("미리보기 조회는 코스와 방장을 같이 읽는다 — 제목·방장 닉네임·탈퇴 여부를 보느라 SELECT 가 따로 나가지 않는다")
	void findWithCourseOwner_fetchesCourseAndOwner() {
		persistInvite(owner, "preview-token");
		// 영속성 컨텍스트에 남아 있으면 프록시 없이 그대로 돌아오므로 비우고 다시 읽는다
		entityManager.clear();

		CourseInvite found = courseInviteRepository
			.findWithCourseOwnerByTokenAndDeletedAtIsNull("preview-token")
			.orElseThrow();

		assertThat(Hibernate.isInitialized(found.getCourse())).isTrue();
		assertThat(Hibernate.isInitialized(found.getCourse().getMember())).isTrue();
		assertThat(found.getCourse().getMember().getNickname()).isEqualTo("방장");
		assertThat(courseInviteRepository.findWithCourseOwnerByTokenAndDeletedAtIsNull("unknown")).isEmpty();
	}

	@Test
	@DisplayName("게스트 이관: 게스트가 만든 토큰의 created_by 가 회원으로 바뀐다")
	void moveOwner_reassignsCreatedBy() {
		Member guest = persistMember("게스트");
		Member target = persistMember("회원");
		persistInvite(guest, "guest-token");
		persistInvite(owner, "owner-token");

		int moved = courseInviteRepository.moveOwner(guest.getId(), target.getId());
		entityManager.clear();

		assertThat(moved).isEqualTo(1);
		assertThat(courseInviteRepository.findByTokenAndDeletedAtIsNull("guest-token"))
			.get()
			.extracting(invite -> invite.getCreatedBy().getId())
			.isEqualTo(target.getId());
		assertThat(courseInviteRepository.findByTokenAndDeletedAtIsNull("owner-token"))
			.get()
			.extracting(invite -> invite.getCreatedBy().getId())
			.isEqualTo(owner.getId());
	}
}
