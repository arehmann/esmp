package com.esmp.pilot;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

/**
 * JPA repository for invoice persistence operations.
 */
@Repository
public interface InvoiceRepository extends JpaRepository<InvoiceEntity, Long> {

    @Query("SELECT i FROM InvoiceEntity i WHERE i.status = :status")
    List<InvoiceEntity> findByStatus(@Param("status") InvoiceStatusEnum status);

    @Query("SELECT i FROM InvoiceEntity i WHERE i.customerId = :customerId ORDER BY i.id DESC")
    List<InvoiceEntity> findByCustomerId(@Param("customerId") String customerId);

    @Query("SELECT i FROM InvoiceEntity i WHERE i.amount >= :minAmount AND i.amount <= :maxAmount")
    List<InvoiceEntity> findByAmountRange(@Param("minAmount") double minAmount, @Param("maxAmount") double maxAmount);
}
