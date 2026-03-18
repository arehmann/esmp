package com.esmp.incremental;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

/**
 * Repository for BaseEntity used in incremental indexing tests.
 * Exercises Repository stereotype extraction and JPA @Query pattern detection.
 */
@Repository
public interface BaseRepository extends JpaRepository<BaseEntity, Long> {

    @Query("SELECT e FROM BaseEntity e WHERE e.name = :name")
    List<BaseEntity> findByName(String name);
}
