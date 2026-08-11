# MeshSocialStarter

A Phase-1 starter for the offline-first social feed HLD/LLD we designed.

## What is already implemented

- Native Android / Kotlin / Jetpack Compose shell.
- Local UUID profile (`User`).
- Local posts (`Post`) with UUID `post_id`.
- Room persistence.
- Best-effort 20-post rolling 24-hour quota.
- 24-hour post TTL and periodic cleanup.
- Persisted `PeerState`.
- Persisted normalized `PendingSyncItem` (not a `List<PostId>` embedded in `PeerState`).
- Working in-memory pairwise anti-entropy synchronizer.
- Simulated connection interruption through a transfer budget.
- Resume pending work first, then perform a **fresh inventory diff**.
- Idempotent post insertion through unique `post_id`.
- `TopologyPolicy` implementing the whiteboard priority strategy.
- `ConnectionCoordinator`, `PeerDiscovery`, `PeerConnector`, and `PeerConnection` boundaries.
- BLE scan/advertise adapter (`BlePeerDiscovery`) and custom GATT UUID constants.
- Unit tests for interrupted/resumed convergence and topology priority.

## Deliberately NOT implemented yet

The BLE **GATT data channel** is intentionally the next milestone. Specifically:

1. `BluetoothGattServer` exposing `SERVICE/RX/TX`.
2. `BluetoothDevice.connectGatt()` client.
3. Service discovery + notification subscription.
4. `BlePeerConnection : PeerConnection`.
5. `MessageCodec` (`SyncMessage <-> ByteArray`).
6. Message framing/chunking and a send queue.
7. Wire `SyncSession` to `ConnectionCoordinator`.
8. Runtime permission UI for BLE (manifest declarations already exist).
9. Background/foreground-service behavior.

This ordering matters: first prove replica convergence independent of the radio, then make BLE only a transport.

---

## Open in Android Studio

This bundle contains the project Gradle files but intentionally does **not** ship a Gradle wrapper JAR.

### Recommended path (least brittle)

1. In your current Android Studio, create **New Project -> Empty Activity**.
2. Name it `MeshSocialStarter`.
3. Package: `com.example.meshsocial`.
4. Minimum SDK: **26**.
5. Close the generated project.
6. Copy/replace this bundle's `app/src/` into the generated project.
7. Merge the dependencies from this bundle's `app/build.gradle.kts` into the generated project's app Gradle file.
8. Add the Room/KSP configuration shown in this bundle.
9. Sync Gradle and run.

### Direct import

You can also open this directory directly. It is authored against:

- AGP `9.3.1`
- Gradle `9.5.0`
- Kotlin / Compose compiler plugin `2.3.21`
- KSP `2.3.9`
- Compose BOM `2026.06.01`
- Room `2.8.4`
- compile/target SDK `37`
- JDK `17`

If your installed Android Studio template generated slightly newer versions, prefer the versions Android Studio generated and keep the **source code** from this bundle.

---

## First run

1. Run the app on one phone/emulator.
2. Create a profile.
3. Create posts and restart the app: posts should survive.
4. Open **Debug**.
5. Press **Run interrupted/resumed sync demo**.
6. The log should show:
   - A and B start with different replicas.
   - Round 1 transfers only one post per direction and leaves pending work.
   - Round 2 resumes pending work.
   - A fresh inventory diff runs again.
   - both replicas converge.

Run unit tests too:

```bash
./gradlew test
```

(or use Android Studio's test gutter icons if you created a fresh project and do not yet have a wrapper).

---

## Code map back to your HLD/LLD

```text
HLD / LLD box                  Code
------------------------------------------------------------------
Storage Layer                  data/local + data/repository
User/Post                      domain/model/Models.kt
PeerState                      domain/model + Room entity/DAO
PendingSyncItem                domain/model + Room entity/DAO
Sync Engine                    sync/PairwiseAntiEntropySynchronizer
Sync protocol                  protocol/SyncMessage.kt
SyncSession                    sync/SyncSession.kt (Phase-2 skeleton)
Peer Discovery                 discovery/PeerDiscovery.kt
BLE discovery                  ble/BlePeerDiscovery.kt
Topology Coordinator/Policy    topology/TopologyPolicy.kt
Connection Coordinator         connection/ConnectionCoordinator.kt
Peer Connector                 connection/PeerConnector.kt
Peer Connection                connection/PeerConnection.kt
BLE GATT                       NEXT MILESTONE
```

---

## Important design invariant encoded in this starter

When A reconnects to B:

```text
1. Resume persisted pending work for B.
2. Exchange/fetch a FRESH inventory.
3. Compare B's CURRENT inventory against A's CURRENT Post table.
4. Add new missing IDs to PendingSyncItem.
5. Transfer.
6. Insert post durably.
7. Remove pending record.
8. Mark successful reconciliation only when pending work is empty.
```

We do **not** persist a giant `posts_synced[B]` list as the source of truth. It would become stale as A and B independently meet other devices.

---

## Next implementation milestone: BLE GATT

Implement these classes next:

```text
ble/BleGattServer
ble/BleGattConnector : PeerConnector
ble/BlePeerConnection : PeerConnection
protocol/MessageCodec
protocol/FrameCodec
```

Target runtime flow:

```text
BLE advertisement
      ↓
scan result
      ↓
PeerCandidate
      ↓
TopologyPolicy
      ↓
ConnectionCoordinator
      ↓
connectGatt()
      ↓
discover MeshSocial GATT service
      ↓
enable TX notifications
      ↓
HELLO peer UUID
      ↓
PeerConnection READY
      ↓
SyncSession
      ↓
Inventory / RequestPosts / PostBatch / Ack
```

Start with `MAX_ACTIVE_SYNCS = 1`. Increase to 2-3 only after the two-device path is reliable.

---

## Known V1 simplifications

- One app installation is treated as one user/peer for now.
- User profile replication is not implemented yet; remote posts can be rendered with short author UUIDs.
- Feed ordering uses local wall-clock `createdAt`; clock-skew handling/HLC is later work.
- Strict global 20-post quota is not possible in a disconnected multi-device identity model; V1 enforces locally.
- BLE advertisements currently identify the protocol service, while the full peer UUID is intended to be learned in the HELLO handshake. A compact stable discovery hint can be added later.
- No cryptographic authorship verification in V1.

## Suggested commits from here

1. `feat: run local profile and feed starter`
2. `test: prove interrupted anti-entropy sync resumes`
3. `feat: request nearby-device runtime permissions`
4. `feat: expose BLE GATT server service`
5. `feat: implement GATT client connection`
6. `feat: implement message codec and framing`
7. `feat: run SyncSession over BLE`
8. `feat: wire automatic topology rotation`
