package sweetgirl.croissant.personalization.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.personalization.entity.UserTasteProfile;

public interface UserTasteProfileRepository extends JpaRepository<UserTasteProfile, Long> {

}
