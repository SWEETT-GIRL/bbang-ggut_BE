package sweetgirl.croissant.store.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.store.entity.SearchKeywordCorrection;

public interface SearchKeywordCorrectionRepository extends JpaRepository<SearchKeywordCorrection, Long> {

	boolean existsByNormalizedKeyword(String normalizedKeyword);
}
