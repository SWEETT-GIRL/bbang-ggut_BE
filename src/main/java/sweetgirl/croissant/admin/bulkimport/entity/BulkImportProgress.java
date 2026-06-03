package sweetgirl.croissant.admin.bulkimport.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.admin.bulkimport.enums.BulkImportStatus;
import sweetgirl.croissant.common.entity.BaseTimeEntity;

@Entity
@Table(name = "bulk_import_progress", uniqueConstraints = @UniqueConstraint(name = "uk_bulk_region", columnNames = {
	"region_1st", "region_2nd",
	"region_3rd"}), indexes = @Index(name = "idx_bulk_import_status", columnList = "status"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BulkImportProgress extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "region_1st", nullable = false, length = 50)
	private String region1st;

	@Column(name = "region_2nd", nullable = false, length = 50)
	private String region2nd;

	@Column(name = "region_3rd", nullable = false, length = 50)
	private String region3rd;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private BulkImportStatus status;

	@Column(name = "found_count", nullable = false)
	private int foundCount;

	@Column(name = "inserted_count", nullable = false)
	private int insertedCount;

	@Column(name = "error_message", columnDefinition = "TEXT")
	private String errorMessage;

	@Column(name = "last_attempted_at")
	private LocalDateTime lastAttemptedAt;

	private BulkImportProgress(String region1st, String region2nd, String region3rd) {
		this.region1st = region1st;
		this.region2nd = region2nd;
		this.region3rd = region3rd;
		this.status = BulkImportStatus.PENDING;
		this.foundCount = 0;
		this.insertedCount = 0;
	}

	public static BulkImportProgress of(String region1st, String region2nd, String region3rd) {
		return new BulkImportProgress(region1st, region2nd, region3rd);
	}

	public void markInProgress() {
		this.status = BulkImportStatus.IN_PROGRESS;
		this.lastAttemptedAt = LocalDateTime.now();
	}

	public void markCompleted(int foundCount, int insertedCount) {
		if (this.status != BulkImportStatus.IN_PROGRESS) {
			throw new IllegalStateException(
				"Cannot mark COMPLETED from status: " + this.status);
		}
		this.status = BulkImportStatus.COMPLETED;
		this.foundCount = foundCount;
		this.insertedCount = insertedCount;
		this.errorMessage = null;
	}

	public void markFailed(String errorMessage) {
		if (this.status != BulkImportStatus.IN_PROGRESS) {
			throw new IllegalStateException(
				"Cannot mark FAILED from status: " + this.status);
		}
		this.status = BulkImportStatus.FAILED;
		this.errorMessage = errorMessage;
	}

	public void resetToPending() {
		this.status = BulkImportStatus.PENDING;
		this.errorMessage = null;
	}
}
