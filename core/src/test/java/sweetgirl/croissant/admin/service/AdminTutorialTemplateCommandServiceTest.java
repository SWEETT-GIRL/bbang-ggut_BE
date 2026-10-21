package sweetgirl.croissant.admin.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Validation;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import sweetgirl.croissant.breadlog.TutorialTemplateFixture;
import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.entity.TutorialTemplateCatalog;
import sweetgirl.croissant.breadlog.entity.TutorialTemplateRevision;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.breadlog.repository.TutorialTemplateCatalogRepository;
import sweetgirl.croissant.breadlog.repository.TutorialTemplateRevisionRepository;
import sweetgirl.croissant.external.aws.S3Service;

class AdminTutorialTemplateCommandServiceTest {
	private final TutorialTemplateRevisionRepository revisions = mock(TutorialTemplateRevisionRepository.class);
	private final TutorialTemplateCatalogRepository catalog = mock(TutorialTemplateCatalogRepository.class);
	private final S3Service storage = mock(S3Service.class);
	private final ObjectMapper mapper = new ObjectMapper();
	private AdminTutorialTemplateCommandService service;

	@BeforeEach
	void setup() {
		service = new AdminTutorialTemplateCommandService(revisions, catalog, storage, mapper,
			Validation.buildDefaultValidatorFactory().getValidator());
	}

	@Test
	void uploadsUseNewKeysAndSignedNoOverwriteHeaders() {
		when(storage.generateImmutableUploadUrl(anyString(), eq("image/png"))).thenReturn("signed");
		var first = service.upload("image/png");
		var second = service.upload("image/png");
		assertThat(first.imageKey()).startsWith("onboarding/tutorials/").endsWith(".png");
		assertThat(first.imageKey()).isNotEqualTo(second.imageKey());
		assertThat(first.headers()).containsEntry("If-None-Match", "*");
		assertThatThrownBy(() -> service.upload("text/html")).isInstanceOf(BreadLogException.class);
	}

	@Test
	void editsCreateNewUnpublishedRevisionRatherThanMutatingExistingContent() {
		when(revisions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		var first = service.saveDraft(TutorialTemplateFixture.content());
		var second = service.saveDraft(TutorialTemplateFixture.content());
		assertThat(first).isNotEqualTo(second);
		verify(revisions, times(2)).save(argThat(revision -> !revision.isPublished()));
		assertThatThrownBy(() -> service.saveDraft(null)).isInstanceOf(BreadLogException.class);
	}

	@Test
	void publishRequiresStoredImageAndPreservesOldRevision() throws Exception {
		TutorialTemplateCatalog current = org.springframework.beans.BeanUtils
			.instantiateClass(TutorialTemplateCatalog.class);
		current.activate("old");
		when(catalog.lockCatalog()).thenReturn(Optional.of(current));
		var revision = TutorialTemplateRevision.draft(mapper.writeValueAsString(TutorialTemplateFixture.content()));
		when(revisions.findById(revision.getCode())).thenReturn(Optional.of(revision));
		when(storage.headObject(anyString())).thenReturn(HeadObjectResponse.builder().contentType("image/png")
			.contentLength(100L).build());
		service.publish(revision.getCode(), "old");
		assertThat(current.getActiveCode()).isEqualTo(revision.getCode());
		assertThat(revision.isPublished()).isTrue();
		verify(revisions, never()).delete(any());
		service.stop(revision.getCode());
		assertThat(current.getActiveCode()).isNull();
		assertThat(revision.isPublished()).isTrue();
	}

	@Test
	void stalePublishAndStopCannotOverwriteAnotherAdminsChoice() {
		TutorialTemplateCatalog current = org.springframework.beans.BeanUtils
			.instantiateClass(TutorialTemplateCatalog.class);
		current.activate("new");
		when(catalog.lockCatalog()).thenReturn(Optional.of(current));
		assertThatThrownBy(() -> service.publish("draft", "old")).isInstanceOfSatisfying(BreadLogException.class,
			exception -> assertThat(exception.getErrorCode()).isEqualTo(BreadLogErrorCode.TUTORIAL_CATALOG_CONFLICT));
		assertThatThrownBy(() -> service.stop("old")).isInstanceOf(BreadLogException.class);
		verifyNoInteractions(storage, revisions);
	}

	@Test
	void wrongUploadedMimeDoesNotPublish() throws Exception {
		TutorialTemplateCatalog current = org.springframework.beans.BeanUtils
			.instantiateClass(TutorialTemplateCatalog.class);
		when(catalog.lockCatalog()).thenReturn(Optional.of(current));
		var revision = TutorialTemplateRevision.draft(mapper.writeValueAsString(TutorialTemplateFixture.content()));
		when(revisions.findById(revision.getCode())).thenReturn(Optional.of(revision));
		when(storage.headObject(anyString())).thenReturn(HeadObjectResponse.builder().contentType("text/html")
			.contentLength(100L).build());
		assertThatThrownBy(() -> service.publish(revision.getCode(), null)).isInstanceOf(BreadLogException.class);
		assertThat(revision.isPublished()).isFalse();
		assertThat(current.getActiveCode()).isNull();
	}
}
