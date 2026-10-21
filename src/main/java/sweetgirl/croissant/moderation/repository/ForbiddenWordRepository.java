package sweetgirl.croissant.moderation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.moderation.entity.ForbiddenWord;
import sweetgirl.croissant.moderation.enums.ForbiddenWordKind;

public interface ForbiddenWordRepository extends JpaRepository<ForbiddenWord, Long> {

	List<ForbiddenWord> findAllByOrderByKindAscWordAsc();

	boolean existsByWordAndKind(String word, ForbiddenWordKind kind);
}
