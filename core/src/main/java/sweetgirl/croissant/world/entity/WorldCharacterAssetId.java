package sweetgirl.croissant.world.entity;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class WorldCharacterAssetId implements Serializable {

	@Column(name = "character_code", length = 30)
	private String characterCode;

	@Column(name = "pose", length = 20)
	private String pose;
}
