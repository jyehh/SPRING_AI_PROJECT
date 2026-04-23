package com.example.demo.repository;

import com.example.demo.domain.BadWord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;

public interface BadWordRepository extends JpaRepository<BadWord, Long> {

    // SELECT * FROM bad_word WHERE word = :word 쿼리를 자동으로 생성합니다.
    Optional<BadWord> findByWord(String word);

//    List<String> findAllWords();
    // 조건 없이 전체 단어 리스트만 SELECT
    @Query("SELECT b.word FROM BadWord b")
    HashSet<String> findAllWords();
}