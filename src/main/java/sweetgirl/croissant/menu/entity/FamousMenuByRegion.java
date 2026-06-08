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
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.menu.enums.MenuCategory;
import sweetgirl.croissant.store.enums.Region3rd;

@Entity
@Table(name = "famous_menu_by_region")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FamousMenuByRegion {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Region3rd region3rd;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private MenuCategory menuCategory;
}
