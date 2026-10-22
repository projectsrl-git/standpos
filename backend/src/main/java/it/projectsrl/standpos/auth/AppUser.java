package it.projectsrl.standpos.auth;

import it.projectsrl.standpos.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;

/**
 * A person, global across tenants. Owners and managers have an email and password; cashiers may have only a name
 * and a PIN. A tenant sees a user only through a membership (RLS on {@code app_user}).
 */
@Entity
@Table(name = "app_user")
public class AppUser extends BaseEntity {

    public enum Status {
        ACTIVE,
        DISABLED
    }

    private String email;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "pin_hash")
    private String pinHash;

    @Column(name = "platform_admin", nullable = false)
    private boolean platformAdmin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "pin_failed_attempts", nullable = false)
    private int pinFailedAttempts;

    @Column(name = "pin_locked_until")
    private Instant pinLockedUntil;

    protected AppUser() {}

    public AppUser(String email, String displayName) {
        this.email = email == null ? null : email.trim().toLowerCase();
        this.displayName = displayName;
    }

    public void rename(String displayName) {
        this.displayName = displayName;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void setPinHash(String pinHash) {
        this.pinHash = pinHash;
        this.pinFailedAttempts = 0;
        this.pinLockedUntil = null;
    }

    public boolean isPinLocked(Instant now) {
        return pinLockedUntil != null && pinLockedUntil.isAfter(now);
    }

    /** Records a wrong PIN; returns true when this failure locked the PIN. */
    public boolean recordPinFailure(Instant now, int maxAttempts, Duration lock) {
        pinFailedAttempts++;
        if (pinFailedAttempts >= maxAttempts) {
            pinFailedAttempts = 0;
            pinLockedUntil = now.plus(lock);
            return true;
        }
        return false;
    }

    public void recordPinSuccess() {
        pinFailedAttempts = 0;
        pinLockedUntil = null;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getPinHash() {
        return pinHash;
    }

    public boolean hasPin() {
        return pinHash != null;
    }

    public boolean isPlatformAdmin() {
        return platformAdmin;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getPinLockedUntil() {
        return pinLockedUntil;
    }
}
