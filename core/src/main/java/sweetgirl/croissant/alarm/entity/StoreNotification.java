package sweetgirl.croissant.alarm.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
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
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.store.entity.Store;

@Entity
@Table(name = "store_notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreNotification {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "store_id")
	private Store store;

	@Column(nullable = false, length = 200)
	private String message;

	@Column
	private LocalDateTime readAt;

	@Column(nullable = false, updatable = false)
	private LocalDateTime sentAt;

	@Builder
	public StoreNotification(Member member, Store store, String message, LocalDateTime sentAt) {
		this.member = member;
		this.store = store;
		this.message = message;
		this.sentAt = sentAt;
	}

	public void markAsRead() {
		this.readAt = LocalDateTime.now();
	}

	public static StoreNotification of(Member member, Store store, String message) {
		return StoreNotification.builder()
			.member(member)
			.store(store)
			.message(message)
			.sentAt(LocalDateTime.now())
			.build();
	}
}
