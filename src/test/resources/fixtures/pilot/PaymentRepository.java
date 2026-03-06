package com.esmp.pilot;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

/**
 * JPA repository for payment persistence with write operations.
 */
@Repository
public interface PaymentRepository extends JpaRepository<PaymentEntity, Long> {

    List<PaymentEntity> findByStatus(PaymentStatusEnum status);

    List<PaymentEntity> findByInvoiceId(Long invoiceId);

    @Modifying
    @Query("UPDATE PaymentEntity p SET p.status = :status WHERE p.invoiceId = :invoiceId")
    int updateStatusByInvoiceId(@Param("invoiceId") Long invoiceId, @Param("status") PaymentStatusEnum status);

    @Query("SELECT p FROM PaymentEntity p WHERE p.currency = :currency AND p.status = :status")
    List<PaymentEntity> findByCurrencyAndStatus(@Param("currency") String currency, @Param("status") PaymentStatusEnum status);
}
