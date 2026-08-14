package com.acme.billing.web;

import java.security.Principal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {

    private final InvoiceRepository invoices;
    private final AccountDirectory accounts;

    public InvoiceController(InvoiceRepository invoices, AccountDirectory accounts) {
        this.invoices = invoices;
        this.accounts = accounts;
    }

    @GetMapping("/{invoiceId}/receipt")
    public byte[] downloadReceipt(Principal principal, @PathVariable long invoiceId) {
        accounts.accountIdFor(principal.getName());
        return invoices.findReceiptById(invoiceId)
                .orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
    }
}
