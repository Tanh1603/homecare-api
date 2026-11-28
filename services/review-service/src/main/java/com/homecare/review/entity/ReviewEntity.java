package com.homecare.review.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "reviews")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewEntity {

    @Id
    private UUID id;

    @Column(name = "author_profile_id", nullable = false, length = 255)
    private String authorProfileId;

    @Column(name = "author_name", length = 255)
    private String authorName;

    @Column(name = "target_type", nullable = false)
    private String targetType;

    @Column(name = "source_id", nullable = false, unique = true)
    private UUID sourceId;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(nullable = false)
    private int rating;

    @Column(nullable = false, length = 1500)
    private String comment;

    @Column(nullable = false)
    private String status;

    @Column(name = "edit_count", nullable = false)
    @Builder.Default
    private int editCount = 0;

    @Column(name = "edited_at")
    private Instant editedAt;

    @Column(name = "hidden_reason", length = 1000)
    private String hiddenReason;

    @Column(name = "hidden_by", length = 255)
    private String hiddenBy;

    @Column(name = "hidden_at")
    private Instant hiddenAt;

    @Column(name = "restored_by", length = 255)
    private String restoredBy;

    @Column(name = "restored_at")
    private Instant restoredAt;

    @Column(name = "restore_note", length = 1000)
    private String restoreNote;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
