package sweetgirl.croissant.moderation.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.moderation.dto.response.ForbiddenWordResponse;
import sweetgirl.croissant.moderation.repository.ForbiddenWordRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ForbiddenWordQueryService {

	private final ForbiddenWordRepository forbiddenWordRepository;

	public List<ForbiddenWordResponse> getAll() {
		return forbiddenWordRepository.findAllByOrderByKindAscWordAsc().stream()
			.map(ForbiddenWordResponse::from)
			.toList();
	}
}
