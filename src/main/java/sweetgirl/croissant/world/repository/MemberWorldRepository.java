package sweetgirl.croissant.world.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.world.entity.MemberWorld;

public interface MemberWorldRepository extends JpaRepository<MemberWorld, Long> {

	/**
	 * 빵탑의 최고 높이를 갱신한다. 기록을 지워도 행성이 안 밀리게 하는 값이라 <b>내려가지 않는다</b>.
	 *
	 * <p>아직 세계관 화면에 한 번도 안 들어온 회원은 행이 없어 0 행이 된다. 문제가 아니다 —
	 * 첫 조회에서 지금 기록 수로 높이를 다시 잡는다.
	 */
	@Modifying(clearAutomatically = true)
	@Query(value = """
			UPDATE member_world
			SET max_record_count = GREATEST(max_record_count, :recordCount),
				updated_at = CURRENT_TIMESTAMP
			WHERE member_id = :memberId
			""", nativeQuery = true)
	int raiseMaxRecordCount(@Param("memberId") Long memberId, @Param("recordCount") long recordCount);

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
