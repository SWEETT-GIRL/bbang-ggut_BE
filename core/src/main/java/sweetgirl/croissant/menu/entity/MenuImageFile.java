package sweetgirl.croissant.menu.entity;

import org.hibernate.annotations.SQLRestriction;

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
@Table(name = "menu_image_file")
@SQLRestriction("processing_status = 'DONE'")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MenuImageFile extends ImageFile {

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "menu_id", nullable = false)
	private Menu menu;

	@Column(nullable = false)
	private int displayOrder;

	// 외부 크롤러가 다운로드한 원본 URL — idempotency 키. 어드민 웹 업로드는 null.
	@Column(length = 500)
	private String sourceUrl;

	@Builder
	public MenuImageFile(Menu menu, String name, MimeType mimeType, String awsKey,
			int displayOrder, String sourceUrl) {
		super(name, mimeType, awsKey);
		this.menu = menu;
		this.displayOrder = displayOrder;
		this.sourceUrl = sourceUrl;
	}

	public void updateDisplayOrder(int displayOrder) {
		this.displayOrder = displayOrder;
	}
}
