# Requirements

Analyze why `/dashboard` logs `RequestTimeoutException: Request GetRooms timed out after 5000ms` and why the web dashboard table becomes blank.

Scope:
- RequestBus request/ack flow
- HttpServerComponent dashboard route
- RoomComponent GetRooms handling
- relevant runtime logs around 2026-06-20 12:00
