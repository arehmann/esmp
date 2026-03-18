package com.esmp.incremental.bulk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface BulkRepo17 extends JpaRepository<BulkEntity17, Long> {
    @Query("SELECT e FROM BulkEntity17 e WHERE e.name = :name")
    List<BulkEntity17> findByName(String name);
}
