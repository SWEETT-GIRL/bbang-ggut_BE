package sweetgirl.croissant.breadlog.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.breadlog.entity.TutorialTemplateRevision;

public interface TutorialTemplateRevisionRepository extends JpaRepository<TutorialTemplateRevision, String> {
	List<TutorialTemplateRevision> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
