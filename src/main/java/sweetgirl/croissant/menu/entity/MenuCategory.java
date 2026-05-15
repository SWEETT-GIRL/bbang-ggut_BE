package sweetgirl.croissant.menu.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.menu.enums.MenuKind;

@Entity
@Table(name = "menu_category")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MenuCategory {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 50)
	private MenuKind name;

	@Builder
	public MenuCategory(MenuKind name) {
		this.name = name;
	}
}
