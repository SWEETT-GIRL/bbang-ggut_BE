package sweetgirl.croissant.review.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.review.enums.TextureTag;

@Entity
@Table(name = "review")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Review extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "menu_id", nullable = false)
	private Menu menu;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@Column(nullable = false, precision = 2, scale = 1)
	private BigDecimal rating;

	@Column(nullable = false, length = 50)
	private String repurchaseIntent;

	@Column(nullable = false, length = 50)
	private String sweetness;

	@Column(nullable = false, length = 50)
	private String fillingAmount;

	@ElementCollection(fetch = FetchType.LAZY)
	@CollectionTable(name = "review_texture_tag", joinColumns = @JoinColumn(name = "review_id"))
	@Column(name = "texture_tag", nullable = false, length = 50)
	@Convert(converter = TextureTagConverter.class)
	private List<TextureTag> textureTags = new ArrayList<>();

	@Column(nullable = false, length = 1000)
	private String content;

	@Column(nullable = false)
	private int recommendCount;

	@Column(nullable = false)
	private int commentCount;

	@Column
	private LocalDateTime deletedAt;

	@Column
	private LocalDateTime hiddenAt;

	@Column(length = 500)
	private String hiddenReason;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "hidden_by")
	private Member hiddenBy;

	@Builder
	public Review(
			Menu menu,
			Member member,
			BigDecimal rating,
			String repurchaseIntent,
			String sweetness,
			String fillingAmount,
			List<TextureTag> textureTags,
			String content) {
		this.menu = menu;
		this.member = member;
		this.rating = rating;
		this.repurchaseIntent = repurchaseIntent;
		this.sweetness = sweetness;
		this.fillingAmount = fillingAmount;
		this.textureTags = textureTags == null ? new ArrayList<>() : new ArrayList<>(textureTags);
		this.content = content;
		this.recommendCount = 0;
		this.commentCount = 0;
	}

	public void update(
			BigDecimal rating,
			String repurchaseIntent,
			String sweetness,
			String fillingAmount,
			List<TextureTag> textureTags,
			String content) {
		this.rating = rating;
		this.repurchaseIntent = repurchaseIntent;
		this.sweetness = sweetness;
		this.fillingAmount = fillingAmount;
		this.textureTags.clear();
		this.textureTags.addAll(textureTags);
		this.content = content;
	}

	public void softDelete() {
		this.deletedAt = LocalDateTime.now();
	}

	public void hide(String reason, Member hiddenBy) {
		this.hiddenAt = LocalDateTime.now();
		this.hiddenReason = reason;
		this.hiddenBy = hiddenBy;
	}

	public void restoreModeration() {
		this.deletedAt = null;
		restoreHidden();
	}

	public void restoreHidden() {
		this.hiddenAt = null;
		this.hiddenReason = null;
		this.hiddenBy = null;
	}
}
