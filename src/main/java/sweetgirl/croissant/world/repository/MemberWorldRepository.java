package sweetgirl.croissant.world.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.world.entity.MemberWorld;

public interface MemberWorldRepository extends JpaRepository<MemberWorld, Long> {

	/**
	 * 첫 조회에서 여정을 시작한다. 가입 경로가 넷이라 가입 훅 대신 여기서 만든다.
	 *
	 * <p>같은 회원의 첫 조회가 동시에 둘 들어오면 양쪽 다 "없다"를 보고 넣으려 든다.
	 * 뒤에 온 쪽이 PK 로 깨지지 않도록 넣기를 건너뛴다.
	 */
	@Modifying(clearAutomatically = true)
	@Query(value = """
			INSERT INTO member_world (member_id, selected_character, max_record_count, created_at)
			VALUES (:memberId, :characterCode, 0, CURRENT_TIMESTAMP)
			ON CONFLICT (member_id) DO NOTHING
			""", nativeQuery = true)
	int startIfAbsent(@Param("memberId") Long memberId, @Param("characterCode") String characterCode);
}
