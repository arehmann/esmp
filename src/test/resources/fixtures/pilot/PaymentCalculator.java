package com.esmp.pilot;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Calculator for financial payment totals, taxes, and discounts.
 * Handles multi-currency rounding and tax computation rules.
 */
public class PaymentCalculator {

    private static final double DEFAULT_TAX_RATE = 0.19;
    private static final double DISCOUNT_THRESHOLD = 1000.0;
    private static final double BULK_DISCOUNT_RATE = 0.05;

    /**
     * Calculates the total amount including tax and applicable discounts.
     * Uses switch statement for currency-specific rounding rules.
     */
    public BigDecimal calculateTotal(double baseAmount, String currency, boolean applyDiscount) {
        double discount = 0.0;

        if (applyDiscount && baseAmount >= DISCOUNT_THRESHOLD) {
            discount = baseAmount * BULK_DISCOUNT_RATE;
        }

        double afterDiscount = baseAmount - discount;
        double taxRate = getTaxRateForCurrency(currency);
        double tax = afterDiscount * taxRate;
        double total = afterDiscount + tax;

        int scale;
        switch (currency) {
            case "JPY":
            case "KRW":
                scale = 0;
                break;
            case "USD":
            case "EUR":
            case "GBP":
                scale = 2;
                break;
            default:
                scale = 4;
                break;
        }

        return BigDecimal.valueOf(total).setScale(scale, RoundingMode.HALF_UP);
    }

    private double getTaxRateForCurrency(String currency) {
        if (currency == null) {
            return DEFAULT_TAX_RATE;
        }
        switch (currency) {
            case "USD": return 0.10;
            case "EUR": return 0.20;
            case "GBP": return 0.20;
            case "JPY": return 0.10;
            default: return DEFAULT_TAX_RATE;
        }
    }

    public BigDecimal computeLateFee(InvoiceEntity invoice, int daysOverdue) {
        if (daysOverdue <= 0) {
            return BigDecimal.ZERO;
        }
        double rate = daysOverdue <= 30 ? 0.02 : 0.05;
        double fee = invoice.getAmount() * rate;
        return BigDecimal.valueOf(fee).setScale(2, RoundingMode.HALF_UP);
    }
}
