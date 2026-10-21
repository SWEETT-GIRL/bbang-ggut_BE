package sweetgirl.croissant.monthlyreport.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 같은 그림의 전달 형식만 교체하고 발행된 통계 스냅샷은 유지한다. */
@Entity
@Table(name = "monthly_report_asset_replacement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MonthlyReportAssetReplacement {
	@Id
	@Column(name = "original_image_key", length = 500)
	private String originalImageKey;
	@Column(name = "image_key", nullable = false, length = 500)
	private String imageKey;

	public static MonthlyReportAssetReplacement replace(String originalImageKey, String imageKey) {
		MonthlyReportAssetReplacement replacement = new MonthlyReportAssetReplacement();
		replacement.originalImageKey = originalImageKey;
		replacement.imageKey = imageKey;
		return replacement;
	}
}
