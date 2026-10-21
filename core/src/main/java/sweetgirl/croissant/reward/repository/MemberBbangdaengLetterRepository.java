package sweetgirl.croissant.reward.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.reward.entity.MemberBbangdaengLetter;

public interface MemberBbangdaengLetterRepository extends JpaRepository<MemberBbangdaengLetter, Long> {

	boolean existsByMemberIdAndWeekStart(Long memberId, LocalDate weekStart);

	List<MemberBbangdaengLetter> findAllByMemberIdOrderByWeekStartAsc(Long memberId);

	Optional<MemberBbangdaengLetter> findByIdAndMemberId(Long id, Long memberId);

	/** 지금 보이는 편지 — 최근 것부터. */
	List<MemberBbangdaengLetter> findByMemberIdAndVisibleFromLessThanEqualOrderByWeekStartDesc(
			Long memberId, LocalDate today, Pageable pageable);

	/** 지금 보이는데 아직 안 연 편지 — 최근 것부터. */
	List<MemberBbangdaengLetter> findByMemberIdAndOpenedAtIsNullAndVisibleFromLessThanEqualOrderByWeekStartDesc(
			Long memberId, LocalDate today);

	/**
	 * 편지를 연다. 열었으면 1, 이미 열었거나 아직 안 보이는 편지면 0 이다.
	 *
	 * <p>읽어 보고 고치는 식이면 두 번 눌렀을 때 둘 다 "안 열림"을 보고 둘 다 보상을 준다.
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
			UPDATE MemberBbangdaengLetter letter SET letter.openedAt = :now
			WHERE letter.id = :id AND letter.memberId = :memberId
			AND letter.openedAt IS NULL AND letter.visibleFrom <= :today
			""")
	int open(
			@Param("id") Long id,
			@Param("memberId") Long memberId,
			@Param("today") LocalDate today,
			@Param("now") LocalDateTime now);
}
