package com.esmp.incremental.bulk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface BulkRepo11 extends JpaRepository<BulkEntity11, Long> {
    @Query("SELECT e FROM BulkEntity11 e WHERE e.name = :name")
    List<BulkEntity11> findByName(String name);
}
