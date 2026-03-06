package com.esmp.pilot;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

/**
 * Service managing customer accounts, roles, and associated invoices and payments.
 * Central orchestration service with high fan-out dependency on repository and other services.
 */
@Service
@Transactional
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final InvoiceService invoiceService;
    private final PaymentService paymentService;

    public CustomerService(
            CustomerRepository customerRepository,
            InvoiceService invoiceService,
            PaymentService paymentService) {
        this.customerRepository = customerRepository;
        this.invoiceService = invoiceService;
        this.paymentService = paymentService;
    }

    public CustomerEntity createCustomer(String name, String email, CustomerRole role) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Customer name must not be blank");
        }
        if (email == null || !email.contains("@")) {
            throw new IllegalArgumentException("Customer email must be valid");
        }
        CustomerEntity customer = new CustomerEntity();
        customer.setName(name);
        customer.setEmail(email);
        customer.setRole(role != null ? role : CustomerRole.USER);
        return customerRepository.save(customer);
    }

    public Optional<CustomerEntity> findById(Long id) {
        return customerRepository.findById(id);
    }

    public List<CustomerEntity> findByRole(CustomerRole role) {
        return customerRepository.findByRole(role);
    }

    public CustomerEntity updateRole(Long customerId, CustomerRole newRole) {
        CustomerEntity customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new RuntimeException("Customer not found: " + customerId));
        customer.setRole(newRole);
        return customerRepository.save(customer);
    }

    public List<InvoiceEntity> getCustomerInvoices(Long customerId) {
        CustomerEntity customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new RuntimeException("Customer not found: " + customerId));
        return invoiceService.findByStatus(InvoiceStatusEnum.SENT);
    }

    public void deleteCustomer(Long customerId) {
        CustomerEntity customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new RuntimeException("Customer not found: " + customerId));
        customerRepository.delete(customer);
    }
}
