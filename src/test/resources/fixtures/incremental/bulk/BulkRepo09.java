package com.esmp.incremental.bulk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface BulkRepo09 extends JpaRepository<BulkEntity09, Long> {
    @Query("SELECT e FROM BulkEntity09 e WHERE e.name = :name")
    List<BulkEntity09> findByName(String name);
}
