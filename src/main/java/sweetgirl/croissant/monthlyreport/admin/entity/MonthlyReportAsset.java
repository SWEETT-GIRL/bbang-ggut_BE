package sweetgirl.croissant.monthlyreport.admin.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "monthly_report_asset")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MonthlyReportAsset {
	@Id
	@Column(length = 250)
	private String id;
	@Column(name = "asset_version", nullable = false, length = 60)
	private String assetVersion;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private Kind kind;
	@Column(name = "character_code", nullable = false, length = 30)
	private String characterCode;
	@Column(name = "planet_code", nullable = false, length = 30)
	private String planetCode;
	@Column(nullable = false, length = 10)
	private String variant;
	@Column(name = "image_key", nullable = false, length = 500)
	private String imageKey;

	public enum Kind {
		COVER, TYPE
	}

	public static String identity(String version, Kind kind, String character, String planet, String variant) {
		return version + "/" + kind + "/" + character + "/" + planet + "/" + variant;
	}
}
