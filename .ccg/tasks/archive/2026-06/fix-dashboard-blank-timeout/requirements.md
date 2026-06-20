# Requirements

Fix dashboard blank pages caused by RequestBus timeouts under heavy post-processing/logging load.

Observed from xhrec (3).log:
- 63 `/dashboard` failures from `GetRooms` timeout.
- 5 `/mask/status` failures from `GetMaskStatus` timeout.
- Therefore the root issue is RequestBus control-plane reliability, not only RoomComponent.

Scope:
- Keep CommandEnvelope/CommandAck delivery reliable under high-volume EventBus load.
- Do not change recording/re-arm behavior in this task.
