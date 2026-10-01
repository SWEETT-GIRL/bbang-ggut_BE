package sweetgirl.croissant.personalization.repository;

import java.time.LocalDate;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.personalization.entity.UserInteractionIngestDay;

public interface UserInteractionIngestDayRepository extends JpaRepository<UserInteractionIngestDay, LocalDate> {
	long countByIngestDateGreaterThanEqualAndIngestDateLessThan(LocalDate from, LocalDate until);
}
