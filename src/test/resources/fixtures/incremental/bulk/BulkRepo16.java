package com.esmp.incremental.bulk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface BulkRepo16 extends JpaRepository<BulkEntity16, Long> {
    @Query("SELECT e FROM BulkEntity16 e WHERE e.name = :name")
    List<BulkEntity16> findByName(String name);
}
