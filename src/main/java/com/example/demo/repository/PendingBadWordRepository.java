package com.example.demo.repository;

import com.example.demo.domain.PendingBadWord;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PendingBadWordRepository extends JpaRepository<PendingBadWord, Long> {

    @Modifying
    @Transactional
    @Query(value = "INSERT INTO pending_bad_word " +
            "(pending_word, bad_word, similarity, insert_id, update_id, check_type) " +
            "VALUES (:pendingWord, :badWord, :similarity, :userId, :userId, :checkType) " +
            "ON CONFLICT (pending_word) " +
            "DO UPDATE SET " +
            "update_count = pending_bad_word.update_count + 1, " +
            "similarity = EXCLUDED.similarity, " +
            "update_dtm = CURRENT_TIMESTAMP, " +
            "update_id = EXCLUDED.update_id", nativeQuery = true)
    void upsertPendingWord(@Param("pendingWord") String pendingWord,
                           @Param("badWord") String badWord,
                           @Param("similarity") Double similarity,
                           @Param("userId") String userId,
                           @Param("checkType") String checkType);


    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE PendingBadWord p SET p.response = :response WHERE p.pendingWord = :pendingWord")
    void updatePendingWord(@Param("response") String response, @Param("pendingWord") String pendingWord);



}