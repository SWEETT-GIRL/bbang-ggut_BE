package sweetgirl.croissant.dessert.entity;

import org.locationtech.jts.geom.Point;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.dessert.enums.ConvenienceBrand;

/**
 * 편의점 지점. 빵긋 가게(store)와 섞지 않으려고 따로 둔 표다.
 *
 * <p>전국 5만 곳을 미리 넣지 않고, 지도를 연 지역만 카카오 편의점 카테고리에서 가져와 쌓는
 * 캐시로 쓴다. 그래서 어떤 지역이 비어 있는 건 오류가 아니다.
 */
@Entity
@Table(name = "convenience_store")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConvenienceStore extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ConvenienceBrand brand;

	@Column(name = "branch_name", nullable = false, length = 100)
	private String branchName;

	@Column(nullable = false, length = 255)
	private String address;

	@Column(nullable = false, columnDefinition = "geography(Point, 4326)")
	private Point location;

	@Column(name = "kakao_place_id", nullable = false, length = 50, unique = true)
	private String kakaoPlaceId;
}
