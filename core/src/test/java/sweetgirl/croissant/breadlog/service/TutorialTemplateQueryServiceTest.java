package sweetgirl.croissant.breadlog.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.breadlog.TutorialTemplateFixture;
import sweetgirl.croissant.breadlog.entity.TutorialTemplateCatalog;
import sweetgirl.croissant.breadlog.entity.TutorialTemplateRevision;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.breadlog.repository.TutorialTemplateCatalogRepository;
import sweetgirl.croissant.breadlog.repository.TutorialTemplateRevisionRepository;

class TutorialTemplateQueryServiceTest {
	private final TutorialTemplateRevisionRepository revisions = mock(TutorialTemplateRevisionRepository.class);
	private final TutorialTemplateCatalogRepository catalog = mock(TutorialTemplateCatalogRepository.class);
	private final ObjectMapper mapper = new ObjectMapper();
	private final TutorialTemplateQueryService service = new TutorialTemplateQueryService(revisions, catalog, mapper);

	@Test
	void inactiveCatalogDoesNotExposeAnInventedDefault() {
		assertThat(service.active()).isEmpty();
		assertThatThrownBy(() -> service.published("unknown")).isInstanceOf(BreadLogException.class);
	}

	@Test
	void draftsAreListedButCannotBeCompletedUntilPublished() throws Exception {
		var revision = TutorialTemplateRevision.draft(mapper.writeValueAsString(TutorialTemplateFixture.content()));
		when(revisions.findById(revision.getCode())).thenReturn(Optional.of(revision));
		when(revisions.findAllByOrderByCreatedAtDesc(any())).thenReturn(List.of(revision));
		assertThat(service.list()).hasSize(1);
		assertThatThrownBy(() -> service.published(revision.getCode())).isInstanceOf(BreadLogException.class);
		revision.publish();
		assertThat(service.published(revision.getCode())).isEqualTo(TutorialTemplateFixture.content());
		TutorialTemplateCatalog current = org.springframework.beans.BeanUtils
			.instantiateClass(TutorialTemplateCatalog.class);
		current.activate(revision.getCode());
		when(catalog.findById(1L)).thenReturn(Optional.of(current));
		assertThat(service.active()).get().extracting(response -> response.code()).isEqualTo(revision.getCode());
		current.stop();
		assertThat(service.active()).isEmpty();
		assertThat(service.published(revision.getCode())).isNotNull();
	}
}
