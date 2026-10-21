package sweetgirl.croissant.breadlog.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "tutorial_template_revision")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TutorialTemplateRevision {
	@Id
	@Column(length = 60)
	private String code;
	@Column(nullable = false, columnDefinition = "text")
	private String content;
	@Column(nullable = false)
	private boolean published;
	@Column(nullable = false)
	private LocalDateTime createdAt;

	public static TutorialTemplateRevision draft(String content) {
		TutorialTemplateRevision revision = new TutorialTemplateRevision();
		revision.code = UUID.randomUUID().toString();
		revision.content = content;
		revision.createdAt = LocalDateTime.now();
		return revision;
	}

	public void publish() {
		this.published = true;
	}
}
