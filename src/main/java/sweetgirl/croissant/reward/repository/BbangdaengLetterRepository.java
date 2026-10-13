package sweetgirl.croissant.reward.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import sweetgirl.croissant.reward.entity.BbangdaengLetter;

public interface BbangdaengLetterRepository extends JpaRepository<BbangdaengLetter, Long> {

	List<BbangdaengLetter> findAllByOrderBySeqAsc();

	List<BbangdaengLetter> findAllByActiveTrueOrderBySeqAsc();

	@Query("SELECT COALESCE(MAX(letter.seq), 0) FROM BbangdaengLetter letter")
	int findMaxSeq();
}
