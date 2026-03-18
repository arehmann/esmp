package com.esmp.incremental.bulk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface BulkRepo04 extends JpaRepository<BulkEntity04, Long> {
    @Query("SELECT e FROM BulkEntity04 e WHERE e.name = :name")
    List<BulkEntity04> findByName(String name);
}
