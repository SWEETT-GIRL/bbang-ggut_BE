package sweetgirl.croissant.world.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.world.entity.WorldCharacterAsset;
import sweetgirl.croissant.world.entity.WorldCharacterAssetId;

public interface WorldCharacterAssetRepository
		extends JpaRepository<WorldCharacterAsset, WorldCharacterAssetId> {

	/**
	 * 카탈로그 응답은 ETag 가 버전에만 걸려 있다. 같은 버전이면 같은 바이트가 나와야 하므로
	 * 순서를 DB 가 정하게 두지 않고 못박는다.
	 */
	List<WorldCharacterAsset> findAllByOrderByIdCharacterCodeAscIdPoseAsc();
}
