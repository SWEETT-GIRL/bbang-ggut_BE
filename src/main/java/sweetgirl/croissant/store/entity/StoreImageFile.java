package sweetgirl.croissant.store.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import sweetgirl.croissant.common.enums.MimeType;

import java.time.LocalDateTime;

@Entity
@Table(name = "store_image_file")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class StoreImageFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private MimeType mimeType;

    @Column(nullable = false, length = 255)
    private String awsKey;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public StoreImageFile(Store store, String name, MimeType mimeType, String awsKey) {
        this.store = store;
        this.name = name;
        this.mimeType = mimeType;
        this.awsKey = awsKey;
    }
}
