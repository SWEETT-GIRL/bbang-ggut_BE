package sweetgirl.croissant.member.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import sweetgirl.croissant.member.entity.Member;

public interface MemberRepository extends JpaRepository<Member, Long> {

	// id로 회원을 조회하되, 탈퇴한 회원(deletedAt이 null이 아닌 경우)은 조회 결과에서 제외
	// 탈퇴 회원에게 서비스 접근을 막기 위해 기존 findById 대신 이 메서드를 사용
	Optional<Member> findByIdAndDeletedAtIsNull(Long id);

	@Query("SELECT m.nickname FROM Member m WHERE m.deletedAt IS NULL")
	List<String> findAllNicknames();
}
