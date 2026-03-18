package com.esmp.incremental.bulk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface BulkRepo14 extends JpaRepository<BulkEntity14, Long> {
    @Query("SELECT e FROM BulkEntity14 e WHERE e.name = :name")
    List<BulkEntity14> findByName(String name);
}
