package com.esmp.incremental.bulk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface BulkRepo18 extends JpaRepository<BulkEntity18, Long> {
    @Query("SELECT e FROM BulkEntity18 e WHERE e.name = :name")
    List<BulkEntity18> findByName(String name);
}
