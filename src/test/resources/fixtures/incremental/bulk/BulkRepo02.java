package com.esmp.incremental.bulk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface BulkRepo02 extends JpaRepository<BulkEntity02, Long> {
    @Query("SELECT e FROM BulkEntity02 e WHERE e.name = :name")
    List<BulkEntity02> findByName(String name);
}
