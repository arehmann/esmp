package com.esmp.incremental.bulk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface BulkRepo01 extends JpaRepository<BulkEntity01, Long> {
    @Query("SELECT e FROM BulkEntity01 e WHERE e.name = :name")
    List<BulkEntity01> findByName(String name);
}
