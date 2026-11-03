package io.campusflow.rooms;

import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

public final class Models {
    private Models() {}
    // D may add profile fields (e.g. email) without changing M2's authorization contract.
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record Identity(UUID id, String username, String displayName, UUID departmentId,
                           boolean active, List<String> roles, Set<String> permissions) {
        public boolean admin() { return roles.contains("ADMIN"); }
        public boolean can(String permission) { return permissions.contains(permission); }
        public boolean scope(UUID department) { return admin() || departmentId.equals(department); }
    }
    public record Authorization(boolean allowed, Identity identity) {}
    public record OpeningHour(int day, LocalTime start, LocalTime end) {}
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record Resource(UUID id,String type,String name,UUID departmentId,int capacity,
                           boolean effectiveActive,long version,List<OpeningHour> openingHours) {}
    public record Step(String name,String permission) {}
    public record Create(@NotNull UUID requestKey,@NotNull UUID roomId,
                         @NotBlank @Size(max=100) String title,@NotBlank @Size(max=2000) String purpose,
                         @Min(1) @Max(10000) int attendees,@NotNull Instant startAt,@NotNull Instant endAt) {}
    public record Decision(@Min(1) long expectedVersion,@Min(0) int step,
                           @Pattern(regexp="APPROVE|REJECT") @NotNull String decision,
                           @NotBlank @Size(max=1000) String comment) {}
    public record Change(@Min(1) long expectedVersion,@NotBlank @Size(max=1000) String comment) {}
    public record Application(UUID id,UUID applicantId,String applicantName,UUID departmentId,UUID roomId,
                              String roomName,long resourceVersion,String title,String purpose,int attendees,
                              Instant startAt,Instant endAt,Instant deadline,List<Step> steps,int currentStep,
                              String status,long version,long operationVersion,int attempts,Instant nextAttemptAt,
                              String lastError,boolean needsAttention,Instant createdAt,Instant updatedAt) {}
    public record History(UUID id,long version,String action,UUID actorId,String actorName,Integer step,String comment,Instant occurredAt) {}
    public record Detail(Application application,List<History> history,List<EventStatus> deliveries) {}
    public record EventStatus(UUID id,String kind,String status,int attempts,String lastError) {}
    public record Page<T>(List<T> items,int page,int size,long total) {}
    public record OccupancyWrite(UUID roomId,Instant startAt,Instant endAt,String desiredState,long operationVersion,String traceId) {}
    public record Occupancy(UUID applicationId,UUID roomId,Instant startAt,Instant endAt,long operationVersion,String state,String reasonCode) {}
}
