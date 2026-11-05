package sweetgirl.croissant.moderation.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.moderation.code.ModerationErrorCode;
import sweetgirl.croissant.moderation.dto.request.ForbiddenWordCreateRequest;
import sweetgirl.croissant.moderation.dto.response.ForbiddenWordResponse;
import sweetgirl.croissant.moderation.entity.ForbiddenWord;
import sweetgirl.croissant.moderation.exception.ModerationException;
import sweetgirl.croissant.moderation.repository.ForbiddenWordRepository;

@Service
@RequiredArgsConstructor
@Transactional
public class ForbiddenWordCommandService {

	private final ForbiddenWordRepository forbiddenWordRepository;
	private final ForbiddenWordFilter forbiddenWordFilter;

	public ForbiddenWordResponse create(ForbiddenWordCreateRequest request) {
		String word = request.word().strip();
		if (forbiddenWordRepository.existsByWordAndKind(word, request.kind())) {
			throw new ModerationException(ModerationErrorCode.FORBIDDEN_WORD_DUPLICATE);
		}
		ForbiddenWord saved = forbiddenWordRepository.save(ForbiddenWord.of(word, request.kind()));
		forbiddenWordFilter.reload();
		return ForbiddenWordResponse.from(saved);
	}

	public void delete(Long forbiddenWordId) {
		ForbiddenWord forbiddenWord = forbiddenWordRepository.findById(forbiddenWordId)
			.orElseThrow(() -> new ModerationException(ModerationErrorCode.FORBIDDEN_WORD_NOT_FOUND));
		forbiddenWordRepository.delete(forbiddenWord);
		forbiddenWordFilter.reload();
	}
}
