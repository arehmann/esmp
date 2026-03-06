package com.esmp.pilot;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

/**
 * Service for managing invoices in the billing module.
 * Handles invoice creation, retrieval, and status transitions for the pilot migration.
 */
@Service
@Transactional
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;

    public InvoiceService(InvoiceRepository invoiceRepository) {
        this.invoiceRepository = invoiceRepository;
    }

    /**
     * Creates a new invoice after validating the amount and customer.
     */
    public InvoiceEntity createInvoice(String customerId, double amount, String description) {
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("Customer ID must not be blank");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("Invoice amount must be positive");
        }
        InvoiceEntity invoice = new InvoiceEntity();
        invoice.setCustomerId(customerId);
        invoice.setAmount(amount);
        invoice.setDescription(description);
        invoice.setStatus(InvoiceStatusEnum.DRAFT);
        return invoiceRepository.save(invoice);
    }

    public Optional<InvoiceEntity> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return invoiceRepository.findById(id);
    }

    public List<InvoiceEntity> findByStatus(InvoiceStatusEnum status) {
        if (status == null) {
            return List.of();
        }
        return invoiceRepository.findByStatus(status);
    }

    public InvoiceEntity markAsSent(Long id) {
        InvoiceEntity invoice = invoiceRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Invoice not found: " + id));
        if (invoice.getStatus() == InvoiceStatusEnum.DRAFT) {
            invoice.setStatus(InvoiceStatusEnum.SENT);
            return invoiceRepository.save(invoice);
        } else if (invoice.getStatus() == InvoiceStatusEnum.OVERDUE) {
            invoice.setStatus(InvoiceStatusEnum.SENT);
            return invoiceRepository.save(invoice);
        } else {
            throw new IllegalStateException("Cannot send invoice in status: " + invoice.getStatus());
        }
    }

    public void deleteInvoice(Long id) {
        invoiceRepository.deleteById(id);
    }
}
