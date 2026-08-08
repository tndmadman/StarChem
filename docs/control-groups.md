# Fleet control groups

StarChem supports client-local RTS control groups for friendly ships.

- `Ctrl+1` through `Ctrl+0` assigns the current friendly selection to that group. Assigning an empty selection clears the group.
- `Shift+number` adds the current friendly selection to a group.
- `Alt+number` removes the current friendly selection from a group.
- `number` recalls the living portion of the group in the currently viewed system.
- Double-tapping a group number centers the camera on that group. If the largest living portion is in another known system, StarChem requests that authoritative system view first and completes the camera recall after the view snapshot arrives.

Control-group membership and remembered formation are local UI state and are never sent to the server. Multiplayer servers send only a bounded owner-scoped mapping of the authenticated player's live unit keys to their current system IDs so clients can reconcile destroyed ships and split-system groups without learning hidden enemy state.

The owner-fleet location projection advances multiplayer protocol compatibility to version 14.
