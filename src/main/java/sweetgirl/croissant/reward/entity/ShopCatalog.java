package sweetgirl.croissant.reward.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 상점 품목이 바뀐 횟수를 담는 한 줄짜리 표. 앱은 이 버전만 비교해 품목을 다시 받을지 정한다. */
@Entity
@Table(name = "shop_catalog")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShopCatalog {

	public static final int SINGLE_ROW_ID = 1;

	@Id
	@JdbcTypeCode(SqlTypes.SMALLINT)
	private Integer id;

	@Column(nullable = false)
	private int version;

	@Column(nullable = false)
	private LocalDateTime updatedAt;

	private ShopCatalog(LocalDateTime updatedAt) {
		this.id = SINGLE_ROW_ID;
		this.version = 1;
		this.updatedAt = updatedAt;
	}

	public static ShopCatalog first(LocalDateTime updatedAt) {
		return new ShopCatalog(updatedAt);
	}

	public void raise(LocalDateTime updatedAt) {
		this.version++;
		this.updatedAt = updatedAt;
	}
}
