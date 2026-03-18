package com.esmp.incremental.bulk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface BulkRepo06 extends JpaRepository<BulkEntity06, Long> {
    @Query("SELECT e FROM BulkEntity06 e WHERE e.name = :name")
    List<BulkEntity06> findByName(String name);
}
