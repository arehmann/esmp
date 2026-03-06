package com.esmp.pilot;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.List;

/**
 * Service for processing financial payments and managing payment lifecycle.
 * Handles payment authorization, capture, and refund operations.
 */
@Service
@Transactional
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final InvoiceService invoiceService;

    public PaymentService(PaymentRepository paymentRepository, InvoiceService invoiceService) {
        this.paymentRepository = paymentRepository;
        this.invoiceService = invoiceService;
    }

    /**
     * Processes a payment for the given invoice. Validates amount and applies authorization.
     */
    public PaymentEntity processPayment(Long invoiceId, BigDecimal amount, String currency) {
        try {
            InvoiceEntity invoice = invoiceService.findById(invoiceId)
                    .orElseThrow(() -> new RuntimeException("Invoice not found: " + invoiceId));

            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Payment amount must be positive");
            }

            PaymentEntity payment = new PaymentEntity();
            payment.setInvoiceId(invoiceId);
            payment.setAmount(amount.doubleValue());
            payment.setCurrency(currency != null ? currency : "USD");
            payment.setStatus(PaymentStatusEnum.PENDING);

            PaymentEntity saved = paymentRepository.save(payment);
            authorizePayment(saved);
            return saved;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Payment processing failed", e);
        }
    }

    private void authorizePayment(PaymentEntity payment) {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt == 0) {
                payment.setStatus(PaymentStatusEnum.COMPLETED);
                paymentRepository.save(payment);
                return;
            }
        }
        payment.setStatus(PaymentStatusEnum.FAILED);
        paymentRepository.save(payment);
    }

    public List<PaymentEntity> findByStatus(PaymentStatusEnum status) {
        return paymentRepository.findByStatus(status);
    }

    public void refundPayment(Long paymentId) {
        PaymentEntity payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new RuntimeException("Payment not found: " + paymentId));
        if (payment.getStatus() == PaymentStatusEnum.COMPLETED) {
            payment.setStatus(PaymentStatusEnum.REFUNDED);
            paymentRepository.save(payment);
        } else {
            throw new IllegalStateException("Cannot refund payment in status: " + payment.getStatus());
        }
    }
}
