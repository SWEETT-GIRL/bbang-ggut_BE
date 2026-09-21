package sweetgirl.croissant.world.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 포즈별 그림. 캐릭터마다 가진 포즈가 달라서 JSON 한 칸이 아니라 행으로 둔다.
 *
 * <p>{@code imageKey} 에는 버전을 넣는다({@code front_v2.png}) — 같은 키를 덮으면 앱과 CDN 이
 * 옛 그림을 계속 쓴다. DB 는 키만 갖고, URL 은 서버가 CDN 도메인을 붙여 조립한다.
 */
@Entity
@Table(name = "world_character_assets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorldCharacterAsset {

	@EmbeddedId
	private WorldCharacterAssetId id;

	@Column(name = "image_key", nullable = false, length = 255)
	private String imageKey;

	private Integer width;

	private Integer height;

	public static WorldCharacterAsset create(
			String characterCode,
			String pose,
			String imageKey,
			Integer width,
			Integer height) {
		WorldCharacterAsset asset = new WorldCharacterAsset();
		asset.id = new WorldCharacterAssetId(characterCode, pose);
		asset.imageKey = imageKey;
		asset.width = width;
		asset.height = height;
		return asset;
	}

	public void replace(String imageKey, Integer width, Integer height) {
		this.imageKey = imageKey;
		this.width = width;
		this.height = height;
	}

	public String characterCode() {
		return id.getCharacterCode();
	}

	public String pose() {
		return id.getPose();
	}
}
