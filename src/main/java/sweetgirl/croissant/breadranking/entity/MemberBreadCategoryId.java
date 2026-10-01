package sweetgirl.croissant.breadranking.entity;

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
public class MemberBreadCategoryId implements Serializable {

	@Column(name = "member_id")
	private Long memberId;

	@Column(name = "category_id")
	private Long categoryId;
}
