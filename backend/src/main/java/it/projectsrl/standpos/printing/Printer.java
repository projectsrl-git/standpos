package it.projectsrl.standpos.printing;

import it.projectsrl.standpos.common.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A receipt printer, reached through a print agent: the Windows printer name the agent prints to, the paper width
 * and the ESC/POS code page. v1: the register's own USB POS80, driven by the agent on the same PC.
 */
@Entity
@Table(name = "printer")
public class Printer extends TenantOwnedEntity {

    @Column(name = "venue_id", nullable = false, updatable = false)
    private UUID venueId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(nullable = false)
    private String name;

    @Column(name = "windows_name", nullable = false)
    private String windowsName;

    @Column(name = "width_mm", nullable = false)
    private int widthMm = 80;

    @Column(name = "code_page", nullable = false)
    private String codePage = "CP858";

    @Column(nullable = false)
    private boolean active = true;

    protected Printer() {}

    public Printer(UUID venueId, UUID agentId, String name, String windowsName) {
        this.venueId = venueId;
        this.agentId = agentId;
        this.name = name;
        this.windowsName = windowsName;
    }

    public void update(UUID agentId, String name, String windowsName, int widthMm, String codePage, boolean active) {
        this.agentId = agentId;
        this.name = name;
        this.windowsName = windowsName;
        this.widthMm = widthMm;
        this.codePage = codePage;
        this.active = active;
    }

    /** Characters per line in the printer's standard font A: 48 on 80 mm paper, 32 on 58 mm. */
    public int columns() {
        return widthMm == 58 ? 32 : 48;
    }

    public UUID getVenueId() {
        return venueId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getName() {
        return name;
    }

    public String getWindowsName() {
        return windowsName;
    }

    public int getWidthMm() {
        return widthMm;
    }

    public String getCodePage() {
        return codePage;
    }

    public boolean isActive() {
        return active;
    }
}
