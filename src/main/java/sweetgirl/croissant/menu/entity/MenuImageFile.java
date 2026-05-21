package sweetgirl.croissant.menu.entity;

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
import sweetgirl.croissant.external.aws.enums.MimeType;

@Entity
@Table(name = "menu_image_file")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MenuImageFile extends ImageFile {

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "menu_id", nullable = false)
	private Menu menu;

	@Column(nullable = false)
	private int displayOrder;

	@Builder
	public MenuImageFile(Menu menu, String name, MimeType mimeType, String awsKey, int displayOrder) {
		super(name, mimeType, awsKey);
		this.menu = menu;
		this.displayOrder = displayOrder;
	}

	public void updateDisplayOrder(int displayOrder) {
		this.displayOrder = displayOrder;
	}
}
