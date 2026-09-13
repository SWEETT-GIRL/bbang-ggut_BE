package sweetgirl.croissant.personalization.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.personalization.entity.UserStoreInteraction;

public interface UserStoreInteractionRepository extends JpaRepository<UserStoreInteraction, Long> {

}
