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
	 * 어드민 편지함 목록 — 사용자가 말을 건 대화만, 최근 대화순.
	 *
	 * <p>가입 인사와 일괄 발신 공지는 뺀다. 그것도 대화방을 만들고 맨 위로 올리는데, 우리가
	 * 보낸 말이라 답할 일이 없다 — 회원이 늘수록 그런 줄이 실제 문의를 목록 밖으로 밀어낸다.
	 * 사용자가 한 번이라도 편지를 쓰면 그때 올라오고, 해결로 닫아도 내려가지 않는다.
	 *
	 * <p>member를 같이 끌어온다. 목록에 닉네임이 들어가는데 LAZY로 두면 20줄에 20번 더 나간다.
	 * 총 개수는 세지 않는다(무한스크롤엔 hasNext면 충분).
	 */
	@Query("""
			SELECT t FROM LetterThread t
			JOIN FETCH t.member
			WHERE t.lastMemberMessageAt IS NOT NULL
			ORDER BY t.lastMessageAt DESC
			""")
	Slice<LetterThread> findAllForAdmin(Pageable pageable);
}
