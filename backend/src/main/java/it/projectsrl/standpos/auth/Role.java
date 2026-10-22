package it.projectsrl.standpos.auth;

/**
 * Roles. OWNER is held tenant-wide; MANAGER, STOCK_CLERK, CASHIER and KITCHEN are held per venue. PLATFORM_ADMIN is
 * a flag on the global user, not a membership (see docs/adr/0004-roles-and-memberships.md).
 */
public enum Role {
    PLATFORM_ADMIN,
    OWNER,
    MANAGER,
    STOCK_CLERK,
    CASHIER,
    KITCHEN;

    public boolean isTenantWide() {
        return this == OWNER;
    }
}
