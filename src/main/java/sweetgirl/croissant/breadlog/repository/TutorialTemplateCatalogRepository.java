package sweetgirl.croissant.breadlog.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;
import sweetgirl.croissant.breadlog.entity.TutorialTemplateCatalog;

public interface TutorialTemplateCatalogRepository extends JpaRepository<TutorialTemplateCatalog, Long> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select catalog from TutorialTemplateCatalog catalog where catalog.id = 1")
	Optional<TutorialTemplateCatalog> lockCatalog();
}
