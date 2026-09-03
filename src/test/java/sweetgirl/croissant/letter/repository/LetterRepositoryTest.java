package sweetgirl.croissant.letter.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

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
import sweetgirl.croissant.letter.entity.LetterMessage;
import sweetgirl.croissant.letter.entity.LetterThread;
import sweetgirl.croissant.letter.enums.LetterCategory;
import sweetgirl.croissant.letter.enums.LetterSender;
import sweetgirl.croissant.member.entity.Member;

/**
 * 편지함의 커스텀 쿼리를 실제 DB에 돌려본다.
 *
 * <p>Mockito 단위 테스트는 리포지토리를 스텁으로 대신하므로 JPQL이 맞는지는 못 본다.
 * 여기서 보려는 건 세 가지다 — 스레드별 마지막 편지(서브쿼리 MAX), 스레드별 미읽음 수
 * (GROUP BY, Object[] 반환), 전체 읽음(벌크 UPDATE).
 */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:letter-repository-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class LetterRepositoryTest {

	@MockitoBean
	private S3Service s3Service;

	@Autowired
	private LetterThreadRepository letterThreadRepository;

	@Autowired
	private LetterMessageRepository letterMessageRepository;

	@Autowired
	private EntityManager entityManager;

	private Member member(String nickname) {
		Member member = Member.builder().nickname(nickname).build();
		entityManager.persist(member);
		return member;
	}

	private LetterThread thread(Member member, LocalDateTime lastMessageAt) {
		return letterThreadRepository.save(LetterThread.create(member, lastMessageAt));
	}

	private LetterMessage message(LetterThread thread, LetterSender sender, String body) {
		return letterMessageRepository.save(LetterMessage.create(thread, sender, body));
	}

	@Test
	@DisplayName("스레드별 마지막 편지를 한 번에 가져온다")
	void findLatestPerThread_returnsOnePerThread() {
		LetterThread first = thread(member("포실한 까눌레"), LocalDateTime.now());
		LetterThread second = thread(member("바삭한 크루아상"), LocalDateTime.now());
		message(first, LetterSender.ADMIN, "인터뷰 가능하실까요?");
		message(first, LetterSender.MEMBER, "네 좋아요!");
		message(second, LetterSender.ADMIN, "안녕하세요");
		entityManager.flush();

		List<LetterMessage> latest = letterMessageRepository
			.findLatestPerThread(List.of(first.getId(), second.getId()));

		assertThat(latest).hasSize(2);
		assertThat(latest).extracting(LetterMessage::getBody)
			.containsExactlyInAnyOrder("네 좋아요!", "안녕하세요");
	}

	@Test
	@DisplayName("스레드별 안 읽은 편지 수를 [threadId, count]로 묶어 준다")
	void countUnreadPerThread_groupsByThread() {
		LetterThread first = thread(member("포실한 까눌레"), LocalDateTime.now());
		LetterThread second = thread(member("바삭한 크루아상"), LocalDateTime.now());
		message(first, LetterSender.MEMBER, "질문 하나만요");
		message(first, LetterSender.MEMBER, "아 그리고 또");
		message(first, LetterSender.ADMIN, "네 말씀하세요");
		message(second, LetterSender.MEMBER, "안녕하세요");
		entityManager.flush();

		List<Object[]> counts = letterMessageRepository
			.countUnreadPerThread(List.of(first.getId(), second.getId()), LetterSender.MEMBER);

		assertThat(counts).hasSize(2);
		assertThat(counts).anySatisfy(row -> {
			assertThat(row[0]).isEqualTo(first.getId());
			assertThat(row[1]).isEqualTo(2L);
		});
		assertThat(counts).anySatisfy(row -> {
			assertThat(row[0]).isEqualTo(second.getId());
			assertThat(row[1]).isEqualTo(1L);
		});
	}

	@Test
	@DisplayName("전체 읽음은 상대가 보낸 편지만 건드린다")
	void markAllAsRead_onlyTouchesTheOtherSide() {
		LetterThread thread = thread(member("포실한 까눌레"), LocalDateTime.now());
		message(thread, LetterSender.ADMIN, "인터뷰 가능하실까요?");
		message(thread, LetterSender.ADMIN, "시간 되실 때 답장 주세요");
		message(thread, LetterSender.MEMBER, "네 좋아요!");
		entityManager.flush();

		int updated = letterMessageRepository.markAllAsRead(
			thread.getId(), LetterSender.ADMIN, LocalDateTime.now());

		assertThat(updated).isEqualTo(2);
		assertThat(letterMessageRepository
			.countByThreadIdAndSenderAndReadAtIsNull(thread.getId(), LetterSender.ADMIN)).isZero();
		// 내가 보낸 편지는 그대로 — 상대가 읽어야 읽음이 된다
		assertThat(letterMessageRepository
			.countByThreadIdAndSenderAndReadAtIsNull(thread.getId(), LetterSender.MEMBER)).isEqualTo(1);
	}

	@Test
	@DisplayName("미읽음 개수는 회원 기준으로도 센다 — 벨 배지가 쓰는 길")
	void countByThreadMember_countsForBellBadge() {
		Member owner = member("포실한 까눌레");
		LetterThread thread = thread(owner, LocalDateTime.now());
		message(thread, LetterSender.ADMIN, "인터뷰 가능하실까요?");
		message(thread, LetterSender.MEMBER, "네 좋아요!");
		entityManager.flush();

		assertThat(letterMessageRepository.countByThreadMemberIdAndSenderAndReadAtIsNull(
			owner.getId(), LetterSender.ADMIN)).isEqualTo(1);
	}

	@Test
	@DisplayName("도배 판정 — 최근 1분에 보낸 통수를 센다")
	void countRecentMemberLetters_countsWithinWindow() {
		Member owner = member("포실한 까눌레");
		LetterThread thread = thread(owner, LocalDateTime.now());
		message(thread, LetterSender.MEMBER, "오류가 있어요");
		message(thread, LetterSender.MEMBER, "이런 상황에서 앱이 꺼져요");
		entityManager.flush();

		assertThat(letterMessageRepository.countByThreadMemberIdAndSenderAndCreatedAtAfter(
			owner.getId(), LetterSender.MEMBER, LocalDateTime.now().minusMinutes(1))).isEqualTo(2);
		// 창을 미래로 옮기면 그 안엔 아무 것도 없다
		assertThat(letterMessageRepository.countByThreadMemberIdAndSenderAndCreatedAtAfter(
			owner.getId(), LetterSender.MEMBER, LocalDateTime.now().plusMinutes(1))).isZero();
	}

	@Test
	@DisplayName("스레드별 최근 유형은 유형이 붙은 메시지에서만 가져온다")
	void findLatestCategorizedPerThread_ignoresUncategorized() {
		LetterThread thread = thread(member("포실한 까눌레"), LocalDateTime.now());
		letterMessageRepository.save(
			LetterMessage.create(thread, LetterSender.MEMBER, "오류가 있어요", LetterCategory.BUG));
		// 유형 없이 이어 쓴 메시지가 뒤에 와도, 그 건이 무슨 건이었는지는 위가 알고 있다
		message(thread, LetterSender.MEMBER, "아 그리고 하나 더요");
		entityManager.flush();

		List<LetterMessage> latest = letterMessageRepository
			.findLatestCategorizedPerThread(List.of(thread.getId()));

		assertThat(latest).hasSize(1);
		assertThat(latest.get(0).getCategory()).isEqualTo(LetterCategory.BUG);
	}

	@Test
	@DisplayName("어드민 목록은 최근 대화순이고 회원을 같이 끌어온다")
	void findAllForAdmin_ordersByLastMessage() {
		thread(member("오래된 대화"), LocalDateTime.now().minusDays(3));
		thread(member("방금 온 대화"), LocalDateTime.now());
		entityManager.flush();

		Slice<LetterThread> slice = letterThreadRepository.findAllForAdmin(PageRequest.of(0, 20));

		assertThat(slice.getContent()).hasSize(2);
		// JOIN FETCH가 걸려 있어 트랜잭션 밖에서도 닉네임을 읽을 수 있어야 한다
		assertThat(slice.getContent().get(0).getMember().getNickname()).isEqualTo("방금 온 대화");
	}

	@Test
	@DisplayName("회원당 대화방은 하나 — memberId로 바로 찾는다")
	void findByMemberId_returnsOwnThread() {
		Member owner = member("포실한 까눌레");
		LetterThread saved = thread(owner, LocalDateTime.now());
		entityManager.flush();

		assertThat(letterThreadRepository.findByMemberId(owner.getId()))
			.get()
			.extracting(LetterThread::getId)
			.isEqualTo(saved.getId());
	}
}
