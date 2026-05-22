package sweetgirl.croissant.store.entity;

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
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.store.enums.StoreCategory;
import sweetgirl.croissant.store.enums.StoreSource;
import sweetgirl.croissant.store.enums.StoreStatus;

@Entity
@Table(name = "store")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Store extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(nullable = false, length = 255)
	private String address;

	@Column(name = "region_1st", length = 50)
	private String region1st;

	@Column(name = "region_2nd", length = 50)
	private String region2nd;

	@Column(name = "region_3rd", length = 50)
	private String region3rd;

	@Column(nullable = false, columnDefinition = "geography(Point, 4326)")
	private Point location;

	@Column(length = 20)
	private String phone;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 50)
	private StoreStatus status;

	@Column(name = "kakao_place_id", length = 50, unique = true)
	private String kakaoPlaceId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 50)
	private StoreSource source;

	@Enumerated(EnumType.STRING)
	@Column(length = 20)
	private StoreCategory category;

	@Builder
	public Store(String name, String address, String region1st, String region2nd, String region3rd,
			Point location, String phone, String kakaoPlaceId, StoreSource source, StoreCategory category) {
		this.name = name;
		this.address = address;
		this.region1st = region1st;
		this.region2nd = region2nd;
		this.region3rd = region3rd;
		this.location = location;
		this.phone = phone;
		this.kakaoPlaceId = kakaoPlaceId;
		this.source = source != null ? source : StoreSource.ADMIN_REGISTERED;
		this.status = StoreStatus.ACTIVE;
		this.category = category;
	}

	public void update(String name, String address, Point location, String region1st, String region2nd,
			String region3rd, String phone, StoreStatus status, StoreCategory category) {
		if (name != null) {
			this.name = name;
		}
		if (address != null) {
			this.address = address;
		}
		if (location != null) {
			this.location = location;
		}
		if (region1st != null) {
			this.region1st = region1st;
		}
		if (region2nd != null) {
			this.region2nd = region2nd;
		}
		if (region3rd != null) {
			this.region3rd = region3rd;
		}
		if (phone != null) {
			this.phone = phone;
		}
		if (status != null) {
			this.status = status;
		}
		if (category != null) {
			this.category = category;
		}
	}
}
