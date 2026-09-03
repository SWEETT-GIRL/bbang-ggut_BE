package sweetgirl.croissant.letter.repository;

import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import sweetgirl.croissant.letter.entity.LetterThread;

public interface LetterThreadRepository extends JpaRepository<LetterThread, Long> {

	Optional<LetterThread> findByMemberId(Long memberId);

	/**
	 * 어드민 편지함 목록 — 최근 대화순.
	 *
	 * <p>member를 같이 끌어온다. 목록에 닉네임이 들어가는데 LAZY로 두면 20줄에 20번 더 나간다.
	 * 총 개수는 세지 않는다(무한스크롤엔 hasNext면 충분).
	 */
	@Query("""
			SELECT t FROM LetterThread t
			JOIN FETCH t.member
			ORDER BY t.lastMessageAt DESC
			""")
	Slice<LetterThread> findAllForAdmin(Pageable pageable);
}
