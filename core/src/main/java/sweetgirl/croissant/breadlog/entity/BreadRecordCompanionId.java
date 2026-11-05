package sweetgirl.croissant.breadlog.entity;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class BreadRecordCompanionId implements Serializable {

	@Column(name = "record_id")
	private Long recordId;

	@Column(name = "member_id")
	private Long memberId;
}
