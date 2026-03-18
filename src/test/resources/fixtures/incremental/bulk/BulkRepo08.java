package com.esmp.incremental.bulk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface BulkRepo08 extends JpaRepository<BulkEntity08, Long> {
    @Query("SELECT e FROM BulkEntity08 e WHERE e.name = :name")
    List<BulkEntity08> findByName(String name);
}
