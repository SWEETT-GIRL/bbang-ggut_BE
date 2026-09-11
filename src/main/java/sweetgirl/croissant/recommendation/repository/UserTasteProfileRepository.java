package sweetgirl.croissant.recommendation.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.recommendation.entity.UserTasteProfile;

public interface UserTasteProfileRepository extends JpaRepository<UserTasteProfile, Long> {

}
