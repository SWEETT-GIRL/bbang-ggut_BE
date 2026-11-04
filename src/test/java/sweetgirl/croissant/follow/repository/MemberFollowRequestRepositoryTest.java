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
import sweetgirl.croissant.follow.entity.MemberFollowRequest;
import sweetgirl.croissant.member.entity.Member;

/** 네이티브 insert 의 충돌 방어와 받은 요청 목록의 탈퇴 제외는 이름만으로 보이지 않아 실제 DB 로 돌려 본다. */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:member-follow-request-test;MODE=PostgreSQL;"
		+ "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class MemberFollowRequestRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 4, 12, 0);

	@MockitoBean
	private S3Service s3Service;

	@Autowired
	private MemberFollowRequestRepository memberFollowRequestRepository;

	@Autowired
	private EntityManager entityManager;

	private Member me;
	private Member first;
	private Member second;

	@BeforeEach
	void setUp() {
		me = persistMember("나");
		first = persistMember("첫째");
		second = persistMember("둘째");
	}

	private Member persistMember(String nickname) {
		Member member = new Member(nickname);
		entityManager.persist(member);
		return member;
	}

	@Test
	@DisplayName("insertIfAbsent 는 처음만 1 이고 다시 넣으면 0 이다")
	void insertIfAbsent_isIdempotent() {
		assertThat(memberFollowRequestRepository.insertIfAbsent(first.getId(), me.getId(), NOW)).isEqualTo(1);
		assertThat(memberFollowRequestRepository.insertIfAbsent(first.getId(), me.getId(), NOW)).isZero();
		assertThat(memberFollowRequestRepository.existsByIdRequesterIdAndIdTargetId(first.getId(), me.getId()))
			.isTrue();
	}

	@Test
	@DisplayName("받은 요청은 최신순이고 탈퇴한 사람의 요청은 빠진다")
	void findReceived_excludesWithdrawn() {
		memberFollowRequestRepository.insertIfAbsent(first.getId(), me.getId(), NOW.minusHours(1));
		memberFollowRequestRepository.insertIfAbsent(second.getId(), me.getId(), NOW);
		entityManager.flush();
		entityManager.clear();

		Slice<MemberFollowRequest> received = memberFollowRequestRepository.findReceived(me.getId(),
			PageRequest.of(0, 10));
		assertThat(received.getContent()).extracting(request -> request.getRequester().getId())
			.containsExactly(second.getId(), first.getId());
		assertThat(memberFollowRequestRepository.countByIdTargetId(me.getId())).isEqualTo(2);

		entityManager.find(Member.class, first.getId()).softDelete();
		entityManager.flush();
		entityManager.clear();

		assertThat(memberFollowRequestRepository.findReceived(me.getId(), PageRequest.of(0, 10)).getContent())
			.extracting(request -> request.getRequester().getId())
			.containsExactly(second.getId());
	}

	@Test
	@DisplayName("deleteByPair · deleteBetween · deleteAllOf · deleteAllByTargetId")
	void deletes() {
		memberFollowRequestRepository.insertIfAbsent(first.getId(), me.getId(), NOW);
		memberFollowRequestRepository.insertIfAbsent(me.getId(), second.getId(), NOW);
		memberFollowRequestRepository.insertIfAbsent(second.getId(), me.getId(), NOW);

		assertThat(memberFollowRequestRepository.deleteByPair(first.getId(), me.getId())).isEqualTo(1);
		assertThat(memberFollowRequestRepository.deleteByPair(first.getId(), me.getId())).isZero();

		assertThat(memberFollowRequestRepository.deleteBetween(me.getId(), second.getId())).isEqualTo(2);

		memberFollowRequestRepository.insertIfAbsent(first.getId(), me.getId(), NOW);
		memberFollowRequestRepository.insertIfAbsent(second.getId(), me.getId(), NOW);
		assertThat(memberFollowRequestRepository.findRequesterIdsByTargetId(me.getId()))
			.containsExactlyInAnyOrder(first.getId(), second.getId());
		assertThat(memberFollowRequestRepository.deleteAllByTargetId(me.getId())).isEqualTo(2);

		memberFollowRequestRepository.insertIfAbsent(me.getId(), first.getId(), NOW);
		memberFollowRequestRepository.insertIfAbsent(second.getId(), me.getId(), NOW);
		assertThat(memberFollowRequestRepository.findTargetIdsIn(me.getId(), List.of(first.getId(), second.getId())))
			.containsExactly(first.getId());
		assertThat(memberFollowRequestRepository.deleteAllOf(me.getId())).isEqualTo(2);
	}
}
