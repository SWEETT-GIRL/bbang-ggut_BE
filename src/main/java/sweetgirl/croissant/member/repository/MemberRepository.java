package sweetgirl.croissant.member.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.member.entity.Member;

public interface MemberRepository extends JpaRepository<Member, Long> {

	// id로 회원을 조회하되, 탈퇴한 회원(deletedAt이 null이 아닌 경우)은 조회 결과에서 제외
	// 탈퇴 회원에게 서비스 접근을 막기 위해 기존 findById 대신 이 메서드를 사용
	Optional<Member> findByIdAndDeletedAtIsNull(Long id);

	@Query("SELECT m.nickname FROM Member m WHERE m.deletedAt IS NULL")
	List<String> findAllNicknames();

	boolean existsByNicknameAndDeletedAtIsNull(String nickname);

	/**
	 * 어드민이 편지 받을 사람을 고를 때 쓰는 검색. 닉네임 부분일치이고, 검색어가 비면 최근 가입순이다.
	 *
	 * <p>빈 문자열을 넘겨도 되도록 LIKE '%%'로 떨어지게 뒀다 — 널 파라미터를 조건에서 분기하면
	 * 쿼리가 두 갈래가 된다.
	 */
	@Query("""
			SELECT m FROM Member m
			WHERE m.deletedAt IS NULL
				AND LOWER(m.nickname) LIKE LOWER(CONCAT('%', :keyword, '%'))
			ORDER BY m.createdAt DESC
			""")
	Slice<Member> searchForAdmin(@Param("keyword") String keyword, Pageable pageable);
}
