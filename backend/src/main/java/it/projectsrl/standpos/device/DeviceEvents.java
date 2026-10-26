package it.projectsrl.standpos.device;

import java.util.UUID;

/** Device lifecycle events. */
public final class DeviceEvents {

    private DeviceEvents() {}

    /** A device was revoked: its open WebSocket connections are closed. */
    public record Revoked(UUID deviceId) {}
}
