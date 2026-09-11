package sweetgirl.croissant.recommendation.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.recommendation.entity.UserStoreInteraction;

public interface UserStoreInteractionRepository extends JpaRepository<UserStoreInteraction, Long> {

}
