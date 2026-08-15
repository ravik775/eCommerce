package org.bgm.userservice.model;

// ADR-0033 (as renamed for clarity): IAM_ADMIN was "ADMIN" and
// PLATFORM_ADMIN was "SUPER_ADMIN" — renamed because "ADMIN" alone
// misleadingly suggested broad/operational power when it actually only
// grants role assignment, while "SUPER_ADMIN" (the actually broad,
// combined role) sounded like a variant of the narrow one rather than
// the union it is.
public enum Role {
    CUSTOMER,
    PROVIDER,
    CATALOG_ADMIN,
    INVENTORY_ADMIN,
    IAM_ADMIN,
    PLATFORM_ADMIN,
    CAN_TRACE
}
