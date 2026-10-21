package sweetgirl.croissant.member.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.OAuth;
import sweetgirl.croissant.member.enums.OAuthProvider;

public interface OAuthRepository extends JpaRepository<OAuth, Long> {

	// 카카오 로그인 시 기존 가입 여부를 확인하는 데 사용
	// - 결과 없음 → 신규 유저 → Member + OAuth 새로 저장
	// - 결과 있음 → 기존 유저 → 연결된 Member 반환
	Optional<OAuth> findByProviderAndProviderId(OAuthProvider provider, String providerId);

	boolean existsByProviderAndProviderId(OAuthProvider provider, String providerId);

	List<OAuth> findAllByMember(Member member);

	/** 보관 기한이 지난 탈퇴 회원에 아직 묶인 이메일을 지운다. 재가입으로 새 회원에 옮겨 간 OAuth 는 대상이 아니다. */
	@Modifying
	@Query("UPDATE OAuth o SET o.email = NULL WHERE o.email IS NOT NULL "
		+ "AND o.member.id IN (SELECT m.id FROM Member m WHERE m.deletedAt < :cutoff)")
	int clearEmailOfMembersDeletedBefore(@Param("cutoff") LocalDateTime cutoff);
}
