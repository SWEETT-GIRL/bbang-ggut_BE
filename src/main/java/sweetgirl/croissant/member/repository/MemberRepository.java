package sweetgirl.croissant.member.repository;

import java.util.Optional; // Optional로 null-safe하게 결과를 반환하기 위해 import

import org.springframework.data.jpa.repository.JpaRepository; // Spring Data JPA 기본 CRUD 제공

import sweetgirl.croissant.member.entity.Member; // 조회 대상 엔티티

public interface MemberRepository extends JpaRepository<Member, Long> { // Member 엔티티의 기본 JPA Repository, PK 타입은 Long

	// id로 회원을 조회하되, 탈퇴한 회원(deletedAt이 null이 아닌 경우)은 조회 결과에서 제외
	// 탈퇴 회원에게 서비스 접근을 막기 위해 기존 findById 대신 이 메서드를 사용
	Optional<Member> findByIdAndDeletedAtIsNull(Long id);
}
