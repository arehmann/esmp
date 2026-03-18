package com.esmp.incremental.bulk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface BulkRepo19 extends JpaRepository<BulkEntity19, Long> {
    @Query("SELECT e FROM BulkEntity19 e WHERE e.name = :name")
    List<BulkEntity19> findByName(String name);
}
