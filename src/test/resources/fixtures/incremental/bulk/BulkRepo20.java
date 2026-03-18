package com.esmp.incremental.bulk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface BulkRepo20 extends JpaRepository<BulkEntity20, Long> {
    @Query("SELECT e FROM BulkEntity20 e WHERE e.name = :name")
    List<BulkEntity20> findByName(String name);
}
