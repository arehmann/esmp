package com.esmp.incremental.bulk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface BulkRepo10 extends JpaRepository<BulkEntity10, Long> {
    @Query("SELECT e FROM BulkEntity10 e WHERE e.name = :name")
    List<BulkEntity10> findByName(String name);
}
