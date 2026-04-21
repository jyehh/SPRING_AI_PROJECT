package com.example.demo.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "pending_bad_word")
@Getter
@Setter
@NoArgsConstructor
public class PendingBadWord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pending_word", unique = true, nullable = false)
    private String pendingWord;

    @Column(name = "bad_word")
    private String badWord;

    private Double similarity;

    @Column(name = "update_count")
    private Integer updateCount = 1;

    private String status;

    @Column(name = "insert_id")
    private String insertId;

    @Column(name = "insert_dtm")
    private LocalDateTime insertDtm;

    @Column(name = "update_id")
    private String updateId;

    @Column(name = "update_dtm")
    private LocalDateTime updateDtm;

    @PrePersist
    protected void onCreate() {
        this.insertDtm = LocalDateTime.now();
        this.updateDtm = LocalDateTime.now();
        if (this.updateCount == null) {
            this.updateCount = 1;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updateDtm = LocalDateTime.now();
    }
}
