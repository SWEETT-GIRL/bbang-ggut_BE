package sweetgirl.croissant.menu.entity;

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
@Table(name = "requirement_menu_image_file")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RequirementMenuImageFile extends ImageFile {

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "requirement_menu_id", nullable = false)
	private RequirementMenu requirementMenu;

	@Builder
	public RequirementMenuImageFile(RequirementMenu requirementMenu, String name, MimeType mimeType,
			String awsKey) {
		super(name, mimeType, awsKey);
		this.requirementMenu = requirementMenu;
	}
}
