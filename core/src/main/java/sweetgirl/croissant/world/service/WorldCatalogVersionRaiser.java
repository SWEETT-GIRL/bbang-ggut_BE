package sweetgirl.croissant.world.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.world.code.WorldErrorCode;
import sweetgirl.croissant.world.entity.WorldCatalogVersion;
import sweetgirl.croissant.world.exception.WorldException;
import sweetgirl.croissant.world.repository.WorldCatalogVersionRepository;

/**
 * 카탈로그가 바뀌었다고 알린다. 어드민이 행성 · 단계 · 캐릭터 · 에셋 중 무엇이든 저장하면 부른다.
 *
 * <p><b>부르는 걸 빠뜨리면 어드민이 무력해진다.</b> 앱은 이 번호가 그대로면 카탈로그를 다시 받지 않고,
 * 서버 캐시도 안 풀린다. 바꾼 내용이 아무 데도 안 나타난다.
 *
 * <p>{@code MANDATORY} 인 이유: 반드시 <b>바꾼 저장과 같은 트랜잭션</b>에서 올라가야 한다.
 * 따로 커밋되면 "번호는 올랐는데 내용은 롤백된" 상태가 되어 앱이 옛 카탈로그를 새 것이라 믿는다.
 */
@Component
@RequiredArgsConstructor
public class WorldCatalogVersionRaiser {

	private final WorldCatalogVersionRepository worldCatalogVersionRepository;

	@Transactional(propagation = Propagation.MANDATORY)
	public void raise() {
		worldCatalogVersionRepository.findById(WorldCatalogVersion.SINGLE_ROW_ID)
			.orElseThrow(() -> new WorldException(WorldErrorCode.WORLD_CATALOG_NOT_INITIALIZED))
			.raise(LocalDateTime.now());
	}
}
