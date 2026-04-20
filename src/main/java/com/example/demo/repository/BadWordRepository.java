package com.example.demo.repository;

import com.example.demo.domain.BadWord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface BadWordRepository extends JpaRepository<BadWord, Long> {

    // SELECT * FROM bad_word WHERE word = :word 쿼리를 자동으로 생성합니다.
    Optional<BadWord> findByWord(String word);
}