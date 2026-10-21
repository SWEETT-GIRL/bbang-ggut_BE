package sweetgirl.croissant.breadlog.service;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.dto.TutorialTemplateContent;
import sweetgirl.croissant.breadlog.dto.response.TutorialTemplateResponse;
import sweetgirl.croissant.breadlog.entity.TutorialTemplateRevision;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.breadlog.repository.TutorialTemplateCatalogRepository;
import sweetgirl.croissant.breadlog.repository.TutorialTemplateRevisionRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TutorialTemplateQueryService {
	private final TutorialTemplateRevisionRepository revisions;
	private final TutorialTemplateCatalogRepository catalog;
	private final ObjectMapper mapper;

	public Optional<TutorialTemplateResponse> active() {
		return catalog.findById(1L).map(value -> value.getActiveCode())
			.flatMap(revisions::findById).filter(TutorialTemplateRevision::isPublished).map(this::response);
	}

	public TutorialTemplateContent published(String code) {
		// 현재 활성본이 바뀌어도 이미 시작한 체험은 읽었던 발행 버전으로 저장한다.
		return revisions.findById(code).filter(TutorialTemplateRevision::isPublished)
			.map(this::response).map(TutorialTemplateResponse::content)
			.orElseThrow(() -> new BreadLogException(BreadLogErrorCode.INVALID_TUTORIAL));
	}

	public List<TutorialTemplateResponse> list() {
		return revisions.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 100)).stream().map(this::response).toList();
	}

	private TutorialTemplateResponse response(TutorialTemplateRevision revision) {
		try {
			return new TutorialTemplateResponse(revision.getCode(), revision.isPublished(), revision.getCreatedAt(),
				mapper.readValue(revision.getContent(), TutorialTemplateContent.class));
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("튜토리얼 템플릿을 읽을 수 없습니다.", exception);
		}
	}
}
