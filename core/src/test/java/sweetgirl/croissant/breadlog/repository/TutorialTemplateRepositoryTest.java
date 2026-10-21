package sweetgirl.croissant.breadlog.repository;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.persistence.EntityManager;
import sweetgirl.croissant.breadlog.entity.TutorialTemplateRevision;

@DataJpaTest
class TutorialTemplateRepositoryTest {
	@Autowired
	TutorialTemplateRevisionRepository revisions;
	@Autowired
	TutorialTemplateCatalogRepository catalog;
	@Autowired
	EntityManager entityManager;
	@Autowired
	JdbcTemplate jdbc;

	@Test
	void locksSingletonAndPersistsPublishedRevisionWithoutMutatingOldSnapshot() {
		jdbc.update("insert into tutorial_template_catalog (id, active_code) values (1, null)");
		var first = revisions.saveAndFlush(TutorialTemplateRevision.draft("{\"memo\":\"first\"}"));
		first.publish();
		catalog.lockCatalog().orElseThrow().activate(first.getCode());
		entityManager.flush();
		entityManager.clear();
		var next = revisions.saveAndFlush(TutorialTemplateRevision.draft("{\"memo\":\"next\"}"));
		next.publish();
		catalog.lockCatalog().orElseThrow().activate(next.getCode());
		entityManager.flush();
		entityManager.clear();
		assertThat(catalog.findById(1L).orElseThrow().getActiveCode()).isEqualTo(next.getCode());
		assertThat(revisions.findById(first.getCode()).orElseThrow().getContent()).contains("first");
		assertThat(revisions.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 1))).hasSize(1);
		catalog.lockCatalog().orElseThrow().stop();
		entityManager.flush();
		entityManager.clear();
		assertThat(catalog.findById(1L).orElseThrow().getActiveCode()).isNull();
		assertThat(revisions.findById(first.getCode()).orElseThrow().isPublished()).isTrue();
	}
}
