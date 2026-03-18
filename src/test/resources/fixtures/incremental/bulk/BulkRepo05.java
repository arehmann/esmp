package com.esmp.incremental.bulk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface BulkRepo05 extends JpaRepository<BulkEntity05, Long> {
    @Query("SELECT e FROM BulkEntity05 e WHERE e.name = :name")
    List<BulkEntity05> findByName(String name);
}
