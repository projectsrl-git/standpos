package it.projectsrl.standpos.stock;

import it.projectsrl.standpos.common.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** A supplier of goods, shared by every venue of the tenant. */
@Entity
@Table(name = "supplier")
public class Supplier extends TenantOwnedEntity {

    @Column(nullable = false)
    private String name;

    @Column(name = "contact_name")
    private String contactName;

    private String phone;

    private String email;

    private String notes;

    @Column(nullable = false)
    private boolean active = true;

    protected Supplier() {}

    public Supplier(String name) {
        this.name = name;
    }

    public void update(String name, String contactName, String phone, String email, String notes, boolean active) {
        this.name = name;
        this.contactName = contactName;
        this.phone = phone;
        this.email = email;
        this.notes = notes;
        this.active = active;
    }

    public String getName() {
        return name;
    }

    public String getContactName() {
        return contactName;
    }

    public String getPhone() {
        return phone;
    }

    public String getEmail() {
        return email;
    }

    public String getNotes() {
        return notes;
    }

    public boolean isActive() {
        return active;
    }
}
