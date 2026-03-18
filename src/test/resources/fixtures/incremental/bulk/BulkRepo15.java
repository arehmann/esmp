package com.esmp.incremental.bulk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface BulkRepo15 extends JpaRepository<BulkEntity15, Long> {
    @Query("SELECT e FROM BulkEntity15 e WHERE e.name = :name")
    List<BulkEntity15> findByName(String name);
}
