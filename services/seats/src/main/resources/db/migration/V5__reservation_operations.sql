CREATE INDEX reservations_operations_time
    ON m1.reservations (start_at DESC,id DESC)
    INCLUDE (end_at,room_id,seat_id,status,created_at,updated_at);

CREATE INDEX reservations_operations_room_time
    ON m1.reservations (room_id,start_at DESC,id DESC)
    INCLUDE (end_at,seat_id,status);

CREATE INDEX reservations_operations_seat_time
    ON m1.reservations (seat_id,start_at DESC,id DESC)
    INCLUDE (end_at,room_id,status);
