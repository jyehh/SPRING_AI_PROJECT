package com.example.demo.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "bad_word")
@Getter
@Setter
@NoArgsConstructor
public class BadWord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String word;

    @Column(name = "insert_id")
    private String insertId;

    @Column(name = "insert_dtm")
    private LocalDateTime insertDtm;

    @Column(name = "update_id")
    private String updateId;

    @Column(name = "update_dtm")
    private LocalDateTime updateDtm;
}
