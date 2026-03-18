package com.esmp.incremental.bulk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface BulkRepo07 extends JpaRepository<BulkEntity07, Long> {
    @Query("SELECT e FROM BulkEntity07 e WHERE e.name = :name")
    List<BulkEntity07> findByName(String name);
}
