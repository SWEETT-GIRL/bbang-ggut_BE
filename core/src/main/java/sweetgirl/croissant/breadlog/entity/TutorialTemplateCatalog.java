package sweetgirl.croissant.breadlog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "tutorial_template_catalog")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TutorialTemplateCatalog {
	@Id
	private Long id;
	@Column(length = 60)
	private String activeCode;

	public void activate(String code) {
		this.activeCode = code;
	}

	public void stop() {
		this.activeCode = null;
	}
}
