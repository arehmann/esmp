package com.esmp.incremental.bulk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface BulkRepo12 extends JpaRepository<BulkEntity12, Long> {
    @Query("SELECT e FROM BulkEntity12 e WHERE e.name = :name")
    List<BulkEntity12> findByName(String name);
}
