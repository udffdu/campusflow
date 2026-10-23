package io.campusflow.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;

public final class Models {
    private Models() {}
    public record Page<T>(List<T> items, int page, int size, long total) {}
    public record Department(UUID id, String code, String name) {}
    public record DepartmentWrite(@NotBlank @Pattern(regexp="[A-Z0-9_-]{2,32}") String code,@NotBlank @Size(max=100) String name) {}
    public record Role(String code, String name, List<String> permissions) {}
    public record Permission(String code,String name,boolean globalOnly) {}
    public record RoleWrite(@NotBlank @Pattern(regexp="[A-Z][A-Z0-9_]{2,31}") String code,
                             @NotBlank @Size(max=64) String name, @NotEmpty @Size(max=32) Set<String> permissions) {}
    public record Identity(UUID id, String username, String displayName, UUID departmentId, boolean active,
                           List<String> roles, Set<String> permissions) {
        public boolean admin() { return roles.contains("ADMIN"); }
    }
    public record Login(@NotBlank @Size(max=48) String username, @NotBlank @Size(max=72) String password) {}
    public record PasswordChange(@NotBlank @Size(max=72) String currentPassword,
                                 @NotBlank @Size(min=12,max=72) String newPassword) {}
    public record UserCreate(@NotBlank @Pattern(regexp="[a-zA-Z0-9._-]{3,48}") String username,
                             @NotBlank @Size(max=64) String displayName, @NotNull UUID departmentId,
                             @NotEmpty @Size(max=16) Set<String> roles, @NotBlank @Size(min=12,max=72) String password) {}
    public record UserUpdate(@NotBlank @Size(max=64) String displayName, @NotNull UUID departmentId,
                             @NotEmpty @Size(max=16) Set<String> roles, boolean active) {}
    public record OpeningHour(@Min(1) @Max(7) int day, @NotNull LocalTime start, @NotNull LocalTime end) {}
    public record Resource(UUID id, String type, UUID parentId, String code, String name, UUID departmentId,
                           int capacity, List<String> features, List<OpeningHour> openingHours,
                           boolean active, boolean effectiveActive, long version, Instant updatedAt) {}
    public record ResourceCreate(@NotBlank @Pattern(regexp="CAMPUS|BUILDING|ROOM|SEAT") String type,
                                  UUID parentId, @NotBlank @Pattern(regexp="[A-Za-z0-9._-]{1,48}") String code,
                                  @NotBlank @Size(max=100) String name, UUID departmentId,
                                  @Min(0) @Max(10000) int capacity, @NotNull @Size(max=20) List<@NotBlank @Size(max=40) String> features,
                                  @NotNull @Size(max=28) List<@Valid OpeningHour> openingHours, boolean active) {}
    public record ResourceUpdate(@NotBlank @Size(max=100) String name, @Min(0) @Max(10000) int capacity,
                                  @NotNull @Size(max=20) List<@NotBlank @Size(max=40) String> features,
                                  @NotNull @Size(max=28) List<@Valid OpeningHour> openingHours, boolean active, @Min(1) long expectedVersion) {}
    public record NotificationEvent(@NotBlank @Pattern(regexp="[A-Za-z0-9._:-]{1,100}") String eventId,
                                     @NotNull UUID recipientId, @NotBlank @Size(max=100) String title,
                                     @NotBlank @Size(max=2000) String body,
                                     @NotBlank @Pattern(regexp="[A-Z_]{1,48}") String objectType,
                                     @NotBlank @Size(max=128) String objectId) {}
    public record Notice(UUID id, String source, String title, String body, String objectType, String objectId,
                         Instant receivedAt, Instant readAt) {}
    public record Delivery(UUID id, String source, String eventId, UUID recipientId, String title, String status,
                           int attempts, Instant nextAttemptAt, String lastError, Instant createdAt) {}
    public record AuditEvent(@NotBlank @Pattern(regexp="[A-Za-z0-9._:-]{1,100}") String eventId,
                              @NotNull UUID actorId, @NotNull UUID departmentId,
                              @NotBlank @Pattern(regexp="[A-Z0-9_.]{1,64}") String action,
                              @NotBlank @Pattern(regexp="[A-Z_]{1,48}") String objectType,
                              @NotBlank @Size(max=128) String objectId,
                              @NotBlank @Pattern(regexp="SUCCESS|FAILURE") String outcome,
                              @Pattern(regexp="[A-Z0-9_]{1,64}") String reasonCode, @NotNull Instant occurredAt) {}
    public record Audit(UUID id, String source, UUID actorId, String actorName, UUID departmentId, String action,
                         String objectType, String objectId, String outcome, String reasonCode, String requestId, Instant occurredAt) {}
    public record Authorization(@NotBlank @Size(max=128) String sessionToken,
                                @NotBlank @Pattern(regexp="[a-z.]{1,64}") String permission, UUID departmentId) {}
}
