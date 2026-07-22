# Security Policy

- Admin endpoints must verify the caller has the ADMIN role.
- Paid order shipping must verify the order payment status before shipping.
- User input must not be concatenated directly into SQL statements.
