package sweetgirl.croissant.admin.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.common.enums.MimeType;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.external.aws.dto.PresignedUrlResponse;
import sweetgirl.croissant.external.aws.enums.S3Folder;
import sweetgirl.croissant.world.code.WorldErrorCode;
import sweetgirl.croissant.world.dto.request.AdminWorldAssetRegisterRequest;
import sweetgirl.croissant.world.dto.request.AdminWorldAssetUploadRequest;
import sweetgirl.croissant.world.entity.WorldCharacterAsset;
import sweetgirl.croissant.world.entity.WorldCharacterAssetId;
import sweetgirl.croissant.world.exception.WorldException;
import sweetgirl.croissant.world.repository.WorldCharacterAssetRepository;
import sweetgirl.croissant.world.repository.WorldCharacterRepository;
import sweetgirl.croissant.world.service.WorldCatalogVersionRaiser;

/**
 * 캐릭터 포즈 그림을 올리고 카탈로그에 등록한다. DB 는 키만 갖고, URL 은 앱용 응답을 만들 때
 * 서버가 CDN 도메인을 붙여 조립한다 — 도메인이 바뀌어도 DB 를 안 고친다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AdminWorldAssetService {

	/** {@code world/characters/dog/front_v3.png} 에서 3 을 읽는다. */
	private static final Pattern VERSION = Pattern.compile("_v(\\d+)\\.[a-z0-9]+$");

	private final WorldCharacterRepository worldCharacterRepository;
	private final WorldCharacterAssetRepository worldCharacterAssetRepository;
	private final S3Service s3Service;
	private final WorldCatalogVersionRaiser worldCatalogVersionRaiser;

	@Transactional(readOnly = true)
	public PresignedUrlResponse createUploadUrl(
			String characterCode,
			AdminWorldAssetUploadRequest request) {
		requireCharacter(characterCode);

		String key = S3Folder.WORLD.prefix() + "characters/" + characterCode + "/"
			+ request.pose() + "_v" + nextVersion(characterCode, request.pose())
			+ "." + extensionOf(request.mimeType());
		return s3Service.generatePresignedUrlForKey(key, request.mimeType());
	}

	public void register(String characterCode, AdminWorldAssetRegisterRequest request) {
		requireCharacter(characterCode);
		requireOwnKey(characterCode, request.imageKey());

		WorldCharacterAssetId id = new WorldCharacterAssetId(characterCode, request.pose());
		worldCharacterAssetRepository.findById(id)
			.ifPresentOrElse(
				asset -> asset.replace(request.imageKey(), request.width(), request.height()),
				() -> worldCharacterAssetRepository.save(WorldCharacterAsset.create(
					characterCode, request.pose(), request.imageKey(),
					request.width(), request.height())));
		worldCatalogVersionRaiser.raise();
	}

	/**
	 * 카탈로그에서만 뺀다. S3 객체는 남겨 둔다 — CDN 이 아직 들고 있을 수 있고, 잘못 지웠을 때
	 * 키만 다시 등록하면 되돌아오기 때문이다.
	 */
	public void unregister(String characterCode, String pose) {
		WorldCharacterAsset asset = worldCharacterAssetRepository
			.findById(new WorldCharacterAssetId(characterCode, pose))
			.orElseThrow(() -> new WorldException(WorldErrorCode.WORLD_ASSET_NOT_FOUND));
		worldCharacterAssetRepository.delete(asset);
		worldCatalogVersionRaiser.raise();
	}

	/** 지금 등록된 키의 다음 번호. 없으면 1 부터. */
	private int nextVersion(String characterCode, String pose) {
		return worldCharacterAssetRepository
			.findById(new WorldCharacterAssetId(characterCode, pose))
			.map(asset -> {
				Matcher matcher = VERSION.matcher(asset.getImageKey());
				return matcher.find() ? Integer.parseInt(matcher.group(1)) + 1 : 2;
			})
			.orElse(1);
	}

	/** 남의 캐릭터 자리에 키를 심지 못하게. presign 으로 받은 키만 등록되어야 한다. */
	private void requireOwnKey(String characterCode, String imageKey) {
		String expectedPrefix = S3Folder.WORLD.prefix() + "characters/" + characterCode + "/";
		if (!imageKey.startsWith(expectedPrefix)) {
			throw new WorldException(WorldErrorCode.WORLD_ASSET_KEY_INVALID);
		}
	}

	private void requireCharacter(String characterCode) {
		if (!worldCharacterRepository.existsById(characterCode)) {
			throw new WorldException(WorldErrorCode.WORLD_CHARACTER_NOT_FOUND);
		}
	}

	private String extensionOf(String mimeType) {
		return switch (MimeType.fromString(mimeType)) {
			case IMAGE_PNG -> "png";
			case IMAGE_WEBP -> "webp";
			case IMAGE_AVIF -> "avif";
			case IMAGE_GIF -> "gif";
			case IMAGE_HEIC -> "heic";
			case IMAGE_JPEG -> "jpg";
		};
	}
}
