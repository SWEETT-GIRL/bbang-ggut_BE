package sweetgirl.croissant.personalization.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.personalization.entity.UserInteractionIngestDay;

public interface UserInteractionIngestDayRepository extends JpaRepository<UserInteractionIngestDay, LocalDate> {
	@Query("SELECT d.ingestDate FROM UserInteractionIngestDay d WHERE d.ingestDate >= :from AND d.ingestDate < :until")
	List<LocalDate> findCompletedDates(@Param("from") LocalDate from, @Param("until") LocalDate until);

	long countByIngestDateGreaterThanEqualAndIngestDateLessThan(LocalDate from, LocalDate until);
}
