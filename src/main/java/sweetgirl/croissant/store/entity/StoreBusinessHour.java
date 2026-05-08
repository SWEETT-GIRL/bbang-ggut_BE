package sweetgirl.croissant.store.entity;

import java.time.DayOfWeek;
import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

@Entity
@Table(name = "store_business_hour")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreBusinessHour {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "store_id")
	private Store store;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private DayOfWeek dayOfWeek; // java.time.DayOfWeek 사용 권장

	private LocalTime openTime;
	private LocalTime closeTime;

	@Column(nullable = false)
	private boolean isClosed; // 휴무 여부

	@Builder
	public StoreBusinessHour(Store store, DayOfWeek dayOfWeek, LocalTime openTime, LocalTime closeTime,
			boolean isClosed) {
		this.store = store;
		this.dayOfWeek = dayOfWeek;
		this.openTime = openTime;
		this.closeTime = closeTime;
		this.isClosed = isClosed;
	}
}
