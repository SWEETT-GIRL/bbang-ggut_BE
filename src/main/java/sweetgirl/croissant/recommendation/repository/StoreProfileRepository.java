package sweetgirl.croissant.recommendation.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.recommendation.entity.StoreProfile;

public interface StoreProfileRepository extends JpaRepository<StoreProfile, Long> {

}
