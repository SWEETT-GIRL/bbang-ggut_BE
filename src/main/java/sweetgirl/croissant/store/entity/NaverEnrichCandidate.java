package sweetgirl.croissant.store.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;

// 네이버 매칭 실패 시 Python 이 POST 한 후보 목록. 어드민이 직접 선택해 매칭 가능.
// 매칭 성공 시(updateNaverPlaceId) 해당 store 의 모든 후보는 삭제됨.
@Entity
@Table(name = "naver_enrich_candidate", uniqueConstraints = @UniqueConstraint(columnNames = {"store_id",
	"naver_place_id"}), indexes = @Index(columnList = "store_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NaverEnrichCandidate extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "store_id", nullable = false)
	private Long storeId;

	@Column(name = "naver_place_id", nullable = false, length = 50)
	private String naverPlaceId;

	@Column(nullable = false, length = 200)
	private String name;

	@Column(length = 500)
	private String address;

	private Double lat;

	private Double lng;

	@Column(name = "display_order", nullable = false)
	private int displayOrder;

	@Builder
	public NaverEnrichCandidate(Long storeId, String naverPlaceId, String name, String address,
			Double lat, Double lng, int displayOrder) {
		this.storeId = storeId;
		this.naverPlaceId = naverPlaceId;
		this.name = name;
		this.address = address;
		this.lat = lat;
		this.lng = lng;
		this.displayOrder = displayOrder;
	}
}
