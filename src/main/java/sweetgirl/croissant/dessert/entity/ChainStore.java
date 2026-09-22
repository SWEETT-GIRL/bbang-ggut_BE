package sweetgirl.croissant.dessert.entity;

import org.locationtech.jts.geom.Point;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;

/**
 * 체인 지점. 파리바게뜨처럼 브랜드 한정 메뉴를 파는 곳을 찾는 데 쓴다.
 *
 * <p>빵긋 가게(store)는 동네 빵집을 모은 표라 프랜차이즈가 거의 없다 — 파리바게뜨는 전국
 * 3천 곳이 넘는데 store 에는 여덟 곳뿐이다. 그래서 지점 목록을 따로 긁어 쌓는다.
 * convenience_store 와 같은 꼴이고, 브랜드만 자유 문자열이다(체인은 계속 늘어난다).
 */
@Entity
@Table(name = "chain_store")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChainStore extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 50)
	private String brand;

	@Column(name = "branch_name", nullable = false, length = 100)
	private String branchName;

	@Column(nullable = false, length = 255)
	private String address;

	@Column(nullable = false, columnDefinition = "geography(Point, 4326)")
	private Point location;

	@Column(name = "kakao_place_id", nullable = false, length = 50, unique = true)
	private String kakaoPlaceId;
}
