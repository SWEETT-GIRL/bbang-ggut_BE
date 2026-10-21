package sweetgirl.croissant.letter.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.letter.entity.LetterMessage;
import sweetgirl.croissant.letter.enums.LetterSender;

public interface LetterMessageRepository extends JpaRepository<LetterMessage, Long> {

	/** 대화 내용 — 최신순으로 잘라 오고 화면에서 뒤집는다. */
	Slice<LetterMessage> findByThreadIdOrderByCreatedAtDesc(Long threadId, Pageable pageable);

	/** 상대가 보낸 편지 중 안 읽은 것의 수. sender가 곧 "내가 아닌 쪽"이다. */
	long countByThreadIdAndSenderAndReadAtIsNull(Long threadId, LetterSender sender);

	long countByThreadMemberIdAndSenderAndReadAtIsNull(Long memberId, LetterSender sender);

	/**
	 * 전체 읽음. 한 통씩 불러 고치면 대화가 길수록 UPDATE가 그만큼 나간다.
	 * 벌크 UPDATE는 영속성 컨텍스트를 안 거치므로 호출부에서 이후 조회를 하지 않는다.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			UPDATE LetterMessage m
			SET m.readAt = :now
			WHERE m.thread.id = :threadId AND m.sender = :sender AND m.readAt IS NULL
			""")
	int markAllAsRead(@Param("threadId") Long threadId,
			@Param("sender") LetterSender sender,
			@Param("now") LocalDateTime now);

	/**
	 * 스레드별 마지막 편지 — 어드민 목록의 미리보기용.
	 *
	 * <p>스레드마다 한 번씩 물으면 20줄에 20번이라 한 번에 받는다. 시각이 아니라 id의 최댓값을
	 * 쓰는 건 같은 초에 두 통이 들어와도 한 줄만 나오게 하려는 것이다.
	 */
	@Query("""
			SELECT m FROM LetterMessage m
			WHERE m.id IN (
				SELECT MAX(m2.id) FROM LetterMessage m2
				WHERE m2.thread.id IN :threadIds
				GROUP BY m2.thread.id
			)
			""")
	List<LetterMessage> findLatestPerThread(@Param("threadIds") Collection<Long> threadIds);

	/** 대화의 마지막 편지 하나 — 사용자 화면에서 대화가 닫혔는지 볼 때 쓴다. */
	Optional<LetterMessage> findFirstByThreadIdOrderByIdDesc(Long threadId);

	/** 발송 한 번에서 사람마다 꽂힌 편지들. 대화방과 회원까지 한 번에 올린다. */
	@Query("""
			SELECT m FROM LetterMessage m
			JOIN FETCH m.thread t
			JOIN FETCH t.member
			WHERE m.notificationId IN :notificationIds
			""")
	List<LetterMessage> findAllBySendIds(@Param("notificationIds") Collection<Long> notificationIds);

	/** 대화방들에서 어느 시각 뒤로 오간 편지 — 발송 뒤 답장을 가르는 데 쓴다. 오래된 것부터. */
	@Query("""
			SELECT m FROM LetterMessage m
			WHERE m.thread.id IN :threadIds AND m.createdAt >= :since
			ORDER BY m.createdAt ASC, m.id ASC
			""")
	List<LetterMessage> findInThreadsSince(@Param("threadIds") Collection<Long> threadIds,
			@Param("since") LocalDateTime since);

	/**
	 * 스레드별 안 읽은 편지 수 — 어드민 목록의 뱃지용. 위와 같은 이유로 한 번에 받는다.
	 * 반환은 {@code [threadId, count]} 배열이다.
	 */
	@Query("""
			SELECT m.thread.id, COUNT(m) FROM LetterMessage m
			WHERE m.thread.id IN :threadIds AND m.sender = :sender AND m.readAt IS NULL
			GROUP BY m.thread.id
			""")
	List<Object[]> countUnreadPerThread(@Param("threadIds") Collection<Long> threadIds,
			@Param("sender") LetterSender sender);

	/**
	 * 도배 방지 — 이 회원이 최근 얼마 사이에 몇 통을 보냈는지.
	 *
	 * <p>"직전 한 통으로부터 N초"가 아니라 창 안의 개수를 세는 이유: 문의 유형을 고르면 그것도
	 * 한 통으로 나가고 곧바로 본문이 뒤따르는데, 간격으로 막으면 정상적인 흐름이 걸린다.
	 */
	long countByThreadMemberIdAndSenderAndCreatedAtAfter(Long memberId, LetterSender sender,
			LocalDateTime after);

	/**
	 * 스레드별 가장 최근 사용자 메시지 — 어드민 목록의 유형 뱃지용.
	 *
	 * <p>유형이 붙은 것만 본다. 유형 없이 이어 쓴 메시지가 맨 뒤에 있어도 그 건이 무슨 건이었는지는
	 * 유형을 고른 그 메시지가 알고 있다.
	 */
	@Query("""
			SELECT m FROM LetterMessage m
			WHERE m.id IN (
				SELECT MAX(m2.id) FROM LetterMessage m2
				WHERE m2.thread.id IN :threadIds AND m2.category IS NOT NULL
				GROUP BY m2.thread.id
			)
			""")
	List<LetterMessage> findLatestCategorizedPerThread(@Param("threadIds") Collection<Long> threadIds);
}
