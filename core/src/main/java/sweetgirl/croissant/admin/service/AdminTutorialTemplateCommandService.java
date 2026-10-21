package sweetgirl.croissant.admin.service;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.dto.TutorialTemplateContent;
import sweetgirl.croissant.breadlog.entity.TutorialTemplateCatalog;
import sweetgirl.croissant.breadlog.entity.TutorialTemplateRevision;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.breadlog.repository.TutorialTemplateCatalogRepository;
import sweetgirl.croissant.breadlog.repository.TutorialTemplateRevisionRepository;
import sweetgirl.croissant.external.aws.S3Service;

@Service
@RequiredArgsConstructor
public class AdminTutorialTemplateCommandService {
	private final TutorialTemplateRevisionRepository revisions;
	private final TutorialTemplateCatalogRepository catalog;
	private final S3Service storage;
	private final ObjectMapper mapper;
	private final Validator validator;

	public record Upload(String imageKey, String uploadUrl, Map<String, String> headers) {
	}

	public Upload upload(String contentType) {
		String extension = switch (contentType) {
			case "image/png" -> "png";
			case "image/webp" -> "webp";
			case "image/jpeg" -> "jpg";
			default -> throw new BreadLogException(BreadLogErrorCode.INVALID_TUTORIAL);
		};
		String key = "onboarding/tutorials/" + UUID.randomUUID() + "." + extension;
		return new Upload(key, storage.generateImmutableUploadUrl(key, contentType),
			Map.of("Content-Type", contentType, "If-None-Match", "*"));
	}

	@Transactional
	public String saveDraft(TutorialTemplateContent content) {
		validate(content);
		try {
			return revisions.save(TutorialTemplateRevision.draft(mapper.writeValueAsString(content))).getCode();
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("튜토리얼 템플릿을 저장할 수 없습니다.", exception);
		}
	}

	@Transactional
	public void publish(String code, String expectedActiveCode) {
		TutorialTemplateCatalog current = locked(expectedActiveCode);
		TutorialTemplateRevision revision = revisions.findById(code)
			.orElseThrow(() -> new BreadLogException(BreadLogErrorCode.INVALID_TUTORIAL));
		try {
			TutorialTemplateContent content = mapper.readValue(revision.getContent(), TutorialTemplateContent.class);
			validate(content);
			var object = storage.headObject(content.imageKey());
			if (!content.contentType().equals(object.contentType()) || object.contentLength() == null
				|| object.contentLength() <= 0 || object.contentLength() > 10 * 1024 * 1024) {
				throw new BreadLogException(BreadLogErrorCode.INVALID_TUTORIAL);
			}
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("튜토리얼 템플릿을 읽을 수 없습니다.", exception);
		}
		revision.publish();
		current.activate(code);
	}

	@Transactional
	public void stop(String expectedActiveCode) {
		locked(expectedActiveCode).stop();
	}

	private TutorialTemplateCatalog locked(String expectedActiveCode) {
		TutorialTemplateCatalog current = catalog.lockCatalog()
			.orElseThrow(() -> new BreadLogException(BreadLogErrorCode.TUTORIAL_UNAVAILABLE));
		if (!Objects.equals(current.getActiveCode(), expectedActiveCode)) {
			throw new BreadLogException(BreadLogErrorCode.TUTORIAL_CATALOG_CONFLICT);
		}
		return current;
	}

	private void validate(TutorialTemplateContent content) {
		if (content == null || !validator.validate(content).isEmpty()
			|| !Double.isFinite(content.x()) || !Double.isFinite(content.y())
			|| content.rating().again() == null || content.rating().textures() == null
			|| content.rating().tastes() == null || content.rating().memo() == null
			|| content.rating().memo().isBlank()) {
			throw new BreadLogException(BreadLogErrorCode.INVALID_TUTORIAL);
		}
		String extension = content.contentType().equals("image/jpeg") ? "jpg" : content.contentType().substring(6);
		if (!content.imageKey().endsWith("." + extension)) {
			throw new BreadLogException(BreadLogErrorCode.INVALID_TUTORIAL);
		}
	}
}
