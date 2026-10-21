package sweetgirl.croissant.breadlog.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.ImageFile;
import sweetgirl.croissant.common.enums.MimeType;

@Entity
@Table(name = "bread_record_photo")
// 다른 이미지와 달리 처리 상태로 거르지 않는다 — 내 기록 사진은 리사이즈가 늦거나
// 실패해도 원본(awsKey)으로나마 보여야 한다. 숨기면 유저에겐 기록이 사라진 것처럼
// 보인다. 리사이즈 키가 없으면 클라이언트가 원본으로 폴백한다.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BreadRecordPhoto extends ImageFile {

	/** 사진 행을 다시 만들거나 파일을 교체해도 이어지는 참조. 삭제 후 새 사진에는 새 값을 부여한다. */
	@Column(name = "reference_id", nullable = false, updatable = false, unique = true)
	private UUID referenceId = UUID.randomUUID();

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "record_id", nullable = false)
	private BreadRecord record;

	@Column(nullable = false)
	private int sortOrder;

	/** 원본 픽셀 크기 — 화면이 사진을 제 비율로 그리는 데 쓴다. 구버전 앱이 올린 사진은 null. */
	private Integer width;

	private Integer height;

	@Builder
	public BreadRecordPhoto(
			BreadRecord record,
			String name,
			MimeType mimeType,
			String awsKey,
			int sortOrder,
			Integer width,
			Integer height) {
		super(name, mimeType, awsKey);
		this.record = record;
		this.sortOrder = sortOrder;
		this.width = width;
		this.height = height;
	}

	public void retainReferenceFrom(BreadRecordPhoto original) {
		this.referenceId = original.getReferenceId();
	}
}
