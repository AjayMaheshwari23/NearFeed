# Next Steps — implementation checklist

## Milestone A — make the starter green

- [ ] Import/open project.
- [ ] Install SDK 37 if required by your selected Compose version.
- [ ] Use JDK 17.
- [ ] Build app.
- [ ] Run unit tests.
- [ ] Create local profile/post.
- [ ] Verify process restart persistence.
- [ ] Run interrupted/resumed sync demo.

## Milestone B — BLE permissions + discovery UI

- [ ] Add runtime request for `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` on API 31+.
- [ ] Request legacy location permission only on API <=30 when scanning.
- [ ] Instantiate `BlePeerDiscovery`.
- [ ] Time-bound scan windows instead of scanning forever.
- [ ] Show discovered candidates + RSSI in Debug UI.
- [ ] Test two physical Android phones.

## Milestone C — GATT server

- [ ] Create `BluetoothGattServer`.
- [ ] Add Mesh service UUID.
- [ ] Add RX characteristic: WRITE.
- [ ] Add TX characteristic: NOTIFY.
- [ ] Handle incoming write callback.
- [ ] Handle CCCD notification subscription.

## Milestone D — GATT client

- [ ] `BleGattConnector : PeerConnector`.
- [ ] `connectGatt()`.
- [ ] state: CONNECTING -> CONNECTED -> DISCOVERING_SERVICES.
- [ ] discover service + RX/TX.
- [ ] enable TX notifications.
- [ ] state -> READY.
- [ ] collision rule: only one side initiates duplicate peer pair.

## Milestone E — byte protocol

- [ ] `MessageCodec`.
- [ ] Add envelope: protocol version, message type, session ID.
- [ ] Add frame header: message ID, frame index, frame count.
- [ ] Add chunk reassembly.
- [ ] Add outgoing BLE operation queue.
- [ ] Add size limits and malformed-message validation.

## Milestone F — real sync

- [ ] `BlePeerConnection : PeerConnection`.
- [ ] Wire `SyncSession.start()`.
- [ ] Persist pending IDs before requesting them.
- [ ] Insert received post before clearing pending work / ACK.
- [ ] After resumed work, always perform fresh inventory.
- [ ] Two physical phones converge with Internet disabled.

## Milestone G — topology

- [ ] Start `MAX_ACTIVE_SYNCS=1`.
- [ ] Persist `lastSeen`, `lastAttempt`, `lastSuccessfulSync`.
- [ ] Pending peer priority.
- [ ] Never-synced priority.
- [ ] Least-recently synced priority.
- [ ] Random tie-breaking.
- [ ] Failure cooldown + jitter.
- [ ] Disconnect after reconciliation and rotate.
- [ ] Increase K experimentally only after instrumentation.
