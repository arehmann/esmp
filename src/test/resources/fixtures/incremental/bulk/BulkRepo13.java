package com.esmp.incremental.bulk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface BulkRepo13 extends JpaRepository<BulkEntity13, Long> {
    @Query("SELECT e FROM BulkEntity13 e WHERE e.name = :name")
    List<BulkEntity13> findByName(String name);
}
