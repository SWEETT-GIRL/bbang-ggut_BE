package sweetgirl.croissant.store.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.store.entity.SearchRegionRelation;
import sweetgirl.croissant.store.enums.SearchRegionRelationType;

public interface SearchRegionRelationRepository extends JpaRepository<SearchRegionRelation, Long> {

	List<SearchRegionRelation> findByFromRegionIdOrderByWeightDesc(Long fromRegionId);

	List<SearchRegionRelation> findByFromRegionIdAndRelationTypeOrderByWeightDesc(
			Long fromRegionId,
			SearchRegionRelationType relationType);
}
