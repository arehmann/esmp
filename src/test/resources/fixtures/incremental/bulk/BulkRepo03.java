package com.esmp.incremental.bulk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface BulkRepo03 extends JpaRepository<BulkEntity03, Long> {
    @Query("SELECT e FROM BulkEntity03 e WHERE e.name = :name")
    List<BulkEntity03> findByName(String name);
}
