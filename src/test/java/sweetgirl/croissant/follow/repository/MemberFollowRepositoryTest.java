package sweetgirl.croissant.follow.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.follow.entity.MemberFollow;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.MemberBlock;
import sweetgirl.croissant.member.repository.MemberBlockRepository;

/**
 * 네이티브 insert 의 충돌 방어와, 목록 JPQL 의 차단 · 탈퇴 제외는 이름만으로 보이지 않아 실제 DB 로 돌려 본다.
 */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:member-follow-test;MODE=PostgreSQL;"
		+ "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class MemberFollowRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 12, 0);

	@MockitoBean
	private S3Service s3Service;

	@Autowired
	private MemberFollowRepository memberFollowRepository;

	@Autowired
	private MemberBlockRepository memberBlockRepository;

	@Autowired
	private EntityManager entityManager;

	private Member me;
	private Member first;
	private Member second;
	private Member third;

	@BeforeEach
	void setUp() {
		me = persistMember("나");
		first = persistMember("첫째");
		second = persistMember("둘째");
		third = persistMember("셋째");
	}

	private Member persistMember(String nickname) {
		Member member = new Member(nickname);
		entityManager.persist(member);
		return member;
	}

	@Test
	@DisplayName("insertIfAbsent 는 처음만 1 이고 다시 넣으면 0 이다 — PK 충돌로 죽지 않는다")
	void insertIfAbsent_isIdempotent() {
		assertThat(memberFollowRepository.insertIfAbsent(me.getId(), first.getId(), NOW)).isEqualTo(1);
		assertThat(memberFollowRepository.insertIfAbsent(me.getId(), first.getId(), NOW)).isZero();
		assertThat(memberFollowRepository.existsByIdFollowerIdAndIdFolloweeId(me.getId(), first.getId())).isTrue();
	}

	@Test
	@DisplayName("팔로워 목록은 최신순이고 보는 사람과 차단 관계인 사람 · 탈퇴한 사람은 빠진다")
	void findFollowers_excludesBlockedAndWithdrawn() {
		memberFollowRepository.insertIfAbsent(first.getId(), me.getId(), NOW.minusHours(3));
		memberFollowRepository.insertIfAbsent(second.getId(), me.getId(), NOW.minusHours(2));
		memberFollowRepository.insertIfAbsent(third.getId(), me.getId(), NOW.minusHours(1));
		// third 가 보는 사람(me)을 차단했다 — 반대 방향도 걸러져야 한다
		memberBlockRepository.save(MemberBlock.builder().blocker(third).blockedMember(me).build());
		second.softDelete();
		entityManager.flush();
		entityManager.clear();

		Slice<MemberFollow> followers = memberFollowRepository.findFollowers(me.getId(), me.getId(), null,
			PageRequest.of(0, 10));

		assertThat(followers.getContent()).extracting(row -> row.getFollower().getNickname())
			.containsExactly("첫째");
		assertThat(followers.hasNext()).isFalse();
	}

	@Test
	@DisplayName("팔로잉 목록도 같은 규칙이고 hasNext 는 size 를 넘을 때만 참이다")
	void findFollowings_pagesWithHasNext() {
		memberFollowRepository.insertIfAbsent(me.getId(), first.getId(), NOW.minusHours(3));
		memberFollowRepository.insertIfAbsent(me.getId(), second.getId(), NOW.minusHours(2));
		memberFollowRepository.insertIfAbsent(me.getId(), third.getId(), NOW.minusHours(1));
		entityManager.flush();
		entityManager.clear();

		Slice<MemberFollow> firstPage = memberFollowRepository.findFollowings(me.getId(), me.getId(), null,
			PageRequest.of(0, 2));

		assertThat(firstPage.getContent()).extracting(row -> row.getFollowee().getNickname())
			.containsExactly("셋째", "둘째");
		assertThat(firstPage.hasNext()).isTrue();

		// q 는 닉네임 앞글자 — "둘" 이면 둘째만
		Slice<MemberFollow> filtered = memberFollowRepository.findFollowings(me.getId(), me.getId(), "둘",
			PageRequest.of(0, 10));
		assertThat(filtered.getContent()).extracting(row -> row.getFollowee().getNickname()).containsExactly("둘째");
	}

	@Test
	@DisplayName("deleteBetween 은 양방향을 지우고, 줄별 팔로우 여부 조회는 id 묶음으로 답한다")
	void deleteBetween_andIdLookups() {
		memberFollowRepository.insertIfAbsent(me.getId(), first.getId(), NOW);
		memberFollowRepository.insertIfAbsent(first.getId(), me.getId(), NOW);
		memberFollowRepository.insertIfAbsent(me.getId(), second.getId(), NOW);
		entityManager.flush();

		List<Long> ids = List.of(first.getId(), second.getId(), third.getId());
		assertThat(memberFollowRepository.findFolloweeIdsIn(me.getId(), ids))
			.containsExactlyInAnyOrder(first.getId(), second.getId());
		assertThat(memberFollowRepository.findFollowerIdsIn(me.getId(), ids)).containsExactly(first.getId());

		assertThat(memberFollowRepository.deleteBetween(me.getId(), first.getId())).isEqualTo(2);
		assertThat(memberFollowRepository.countByIdFollowerId(me.getId())).isEqualTo(1);
		assertThat(memberFollowRepository.countByIdFolloweeId(me.getId())).isZero();
	}
}
