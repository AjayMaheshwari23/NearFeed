# NearFeed — Background Sync Flow (line-by-line)

Caveman-style walk-through of how the background discovery → connect → sync pipeline works.

---

## 1. App boot → profile exists

**`ui/MainViewModel.kt:70-81`** — `init{}` runs once at app start. Launches coroutine that watches profile:
```
72: observeCurrentUser().collect { user ->    ← watch DB users table
74:   if (user != null) {                      ← profile created? go live
76:     container.gattServer.start()           ← open GATT server (accept incoming)
77:     container.peerDiscovery.startAdvertising()  ← start broadcasting so others find us
78:     startBackgroundSync()                  ← kick off infinite loop
```
Three things fired once. Then app idle.

---

## 2. Background loop

**`ui/MainViewModel.kt:114-144`** — `startBackgroundSync()`:
```
118: if (backgroundJob != null) return        ← already running? skip (no double loop)
119: _backgroundRunning.value = true          ← UI shows "Background sync RUNNING"
120: backgroundJob = viewModelScope.launch {
121:   while (true) {                           ← forever
122:     if (!BlePermissions.isReady(application)) {   ← BT off or perms missing?
123-128:   log "BG cycle: WAITING ..."          ← tell user why
129:       delay(5s); continue                 ← wait, retry. no silent fail
132:     _scanning.value = true
133:     container.peerDiscovery.startDiscovery()  ← scan for peers NOW
134:     log "scanning 20s"
135:     delay(20s)                             ← scan window
136:     container.peerDiscovery.stopDiscovery()← pause scanning (advertising stays)
138:     log "window closed, reconciling"
139:     reconcileConnections()                 ← ← THE CONNECT+SYNC STEP
140:     delay(10s)                             ← idle gap
```
Loop repeats: scan 20s → reconcile → idle 10s → scan...

---

## 3. Discovery: hear a peer

**`ble/BlePeerDiscovery.kt:101-?`** `startDiscovery()` → `startScan(emptyList, ...)`. Radio listens.

**`ble/BlePeerDiscovery.kt:60-88`** — `onScanResult` fires each time we hear another phone's advertisement:
```
66: payload = scanRecord.manufacturerSpecificData[MANUFACTURER_ID]  ← grab their packet
67: if (payload == null || payload.size != 17) return   ← not our app? ignore
69-71: read version byte + peer UUID out of 17-byte packet
73: log "FOUND PEER ... peerId=..."
77: devicesByAddress[address] = result.device  ← keep real BluetoothDevice (address type!)
78: _peers.tryEmit(PeerCandidate(...))         ← push into flow
```

Emit flows to **`ui/MainViewModel.kt:88-89`**:
```
88: discoveredPeers.collect { candidate ->
89:   _peers.value = candidateTracker.onCandidate(candidate)  ← dedupe by peer UUID, show in UI
```

---

## 4. Reconcile: pick who to connect

**`ui/MainViewModel.kt:191`** → `reconcileConnections()`:
```
load peerStates + pendingSync from Room → build TopologyContext(selfId, ...)
→ container.connectionCoordinator.reconcileConnections(candidates, context)
```

**`connection/ConnectionCoordinator.kt:48-73`**:
```
56: activePeerIds = active links' UUIDs
57-59: drop candidates already connected (dedup)
61: slots = maxActiveSyncs - active - connecting  ← free connection slots
62: if slots==0 → return                          ← full, skip
72: selected = topologyPolicy.selectPeers(candidates, context, slots)  ← TOP-K CHOICE
```

`topology/TopologyPolicy.kt` — P0 (pending) > P1 (never synced) > P2 (last synced), random ties.

---

## 5. Collision: who initiates?

**`connection/ConnectionCoordinator.kt:75-83`** — per selected peer:
```
78-79: if (!bleConnector.shouldInitiate(candidate)) {  ← am I the initiator?
80-82:   log "Passive ...; peer will initiate"; continue ← no → wait, peer connects to us
```

`ble/BleGattConnector.kt:38-42` — `shouldInitiate()`: `local peer UUID > remote peer UUID`. Higher UUID initiates. Both phones agree → one connects, one accepts. No deadlock.

---

## 6. Connect + handoff

**`connection/ConnectionCoordinator.kt:84-91`**:
```
84: connecting += candidateId                     ← mark busy
86: connection = connector.connect(candidate)     ← ← GATT connect (client)
87: active[candidateId] = connection              ← track live link
88: log "Connected to ..."
90: cooldownUntil.remove(knownPeerId)             ← clear old failure
91: onConnected?.invoke(connection)               ← ← HANDOFF to sync
```

`ble/BleGattConnector.kt` `connect()` → `BlePeerConnection`:
`connectGatt → requestMtu → discoverServices → find SERVICE/RX/TX → enable TX notify → READY`.

**`AppContainer.kt:72-77`** — `onConnected` handler:
```
appScope.launch {
  SyncSession(localId, connection, posts, pendingSync, peerStates).start()
}
```
Sync runs in its own coroutine. Coordinator freed → next candidate.

---

## 7. Sync: exchange posts

**`sync/SyncSession.kt:48-73`** — `start()` (both sides run this):
```
53: connection.send(Hello(version, myPeerId))
54: connection.send(Inventory(sessionId, myPostIds))     ← "here's what I have"
62-68: re-inventory loop (every 30s) — re-send Inventory so new posts still flow
70-72: incomingMessages.collect { handle(it) }           ← listen + react, forever
```

**`sync/SyncSession.kt:75-100`** — `handle()` routes messages:
```
77: Hello → ignore (identity already set)
78: Inventory → onInventory()
79-92: RequestPosts → load posts → send PostBatch
93: PostBatch → onPostBatch()
94: Ack → ignore
95-98: SyncComplete → mark complete
```

**`sync/SyncSession.kt:102-129`** — `onInventory()` (the diff):
```
107: local = my active post ids
108: missing = theirIds - myIds               ← what I don't have
109-114: missing empty → send SyncComplete
117-125: persist PendingSyncItem per missing id (survive reconnect)
126: send RequestPosts(sessionId, missing)
```

**`sync/SyncSession.kt:131-145`** — `onPostBatch()`:
```
133: posts.insertAll(batch.posts)            ← save to Room (idempotent, unique post_id)
134-136: remove pending items
137: send Ack
141-144: pending empty → send SyncComplete
```

**`sync/SyncSession.kt:154-166`** — both SyncComplete exchanged → `PeerState SUCCESS` in Room (160-165).

---

## 8. Back in loop

Re-inventory timer re-sends Inventory every 30s (`SyncSession.kt` start block) so posts created after connect still flow. Loop scans again next cycle, skips already-connected peer (dedup), picks new ones.

---

## Flow map (file → job)

| Step | File:line |
|---|---|
| Boot, profile check | `MainViewModel.kt:70-81` |
| Infinite loop | `MainViewModel.kt:114-144` |
| Scan + hear peer | `BlePeerDiscovery.kt:101` / `:60-88` |
| Collect to UI | `MainViewModel.kt:87-91` |
| Top-K pick | `ConnectionCoordinator.kt:72` |
| Collision rule | `BleGattConnector.kt:38-42` |
| GATT connect | `BleGattConnector.kt` → `BlePeerConnection.kt` |
| Handoff to sync | `ConnectionCoordinator.kt:91` → `AppContainer.kt:72` |
| Hello/Inventory/diff/PostBatch/Ack | `SyncSession.kt:53/54/108/126/137` |
| PeerState SUCCESS | `SyncSession.kt:154-166` |

---

# Simulation of sync session (Phase 1 → Phase 8)

Side-by-side execution trace. **D1 = higher UUID** (`0dffc10f`, client/initiator), **D2 = lower UUID** (`01c622ee`, server/passive). D1 owns `[p1,p2]`, D2 owns `[p3,p4]`. Both apps booted, background loops running.

---

## Phase 1 — scan + hear peer

| D1 | D2 |
|---|---|
| `:133` startDiscovery() → scan | `:133` startDiscovery() → scan |
| 📡 D2 advertises `[01c622ee]` | 📡 D1 advertises `[0dffc10f]` |
| `BlePeerDiscovery.kt:62` onScanResult | `BlePeerDiscovery.kt:62` onScanResult |
| `:67` payload 17 bytes ✓ | `:67` payload 17 bytes ✓ |
| `:71` peerId = 01c622ee | `:71` peerId = 0dffc10f |
| `:78` tryEmit(PeerCandidate) | `:78` tryEmit(PeerCandidate) |
| `MainViewModel.kt:88-89` collect → UI | `MainViewModel.kt:88-89` collect → UI |

Same both. Both see each other.

---

## Phase 2 — window closes → reconcile → collision

| D1 | D2 |
|---|---|
| `:139` reconcileConnections() | `:139` reconcileConnections() |
| `ConnectionCoordinator.kt:72` selectPeers → [D2] | `ConnectionCoordinator.kt:72` selectPeers → [D1] |
| `:79` shouldInitiate(D2)? **0dffc10f > 01c622ee = TRUE** | `:79` shouldInitiate(D1)? **01c622ee > 0dffc10f = FALSE** |
| `:84` connecting += D2 | `:81` log "Passive for D1; peer will initiate" |
| `:86` **connector.connect(D2)** ← becomes CLIENT | `:82` **continue** ← stays SERVER |

**The only real branch in whole flow.**

---

## Phase 3 — GATT connect

| D1 | D2 |
|---|---|
| `BleGattConnector.kt:46` shouldInitiate TRUE → no throw | (idle, server listening) |
| `:54-56` device = D2's real BluetoothDevice | |
| `:57` BlePeerConnection(D2) | |
| `:61` connect() → connectGatt ────► | `BleGattServer.kt` accepts |
| `BlePeerConnection.kt` CONNECTED → requestMtu(247) | |
| `BlePeerConnection.kt` discoverServices → find SERVICE/RX/TX | |
| `BlePeerConnection.kt` enable TX notify → READY | |
| `:62` ready=TRUE → return connection | |
| `ConnectionCoordinator.kt:87` active[D2]=connection | |
| `:91` onConnected?.invoke(connection) | |

---

## Phase 4 — handoff to SyncSession

| D1 (client link) | D2 (server link) |
|---|---|
| `AppContainer.kt:72` onConnected fires | `BleGattServer.kt` onIncoming → RX bytes |
| `:76` SyncSession.start() | `AppContainer.kt:90` BleGattServerConnection |
| | `:95` SyncSession.start() |

Both run SyncSession now. Symmetric from here.

---

## Phase 5 — both send Hello + Inventory

| D1 | D2 |
|---|---|
| `SyncSession.kt:53` send Hello(1, 0dffc10f) ──► | `SyncSession.kt:53` send Hello(1, 01c622ee) ◄── |
| `:54` send Inventory([p1,p2]) ──► | `:54` send Inventory([p3,p4]) ◄── |
| `:70` collect incoming... | `:70` collect incoming... |

---

## Phase 6 — diff → request missing

| D1 | D2 |
|---|---|
| handle() ← Inventory[p3,p4] | handle() ← Inventory[p1,p2] |
| `:107` local = {p1,p2} | `:107` local = {p3,p4} |
| `:108` missing = {p3,p4}−{p1,p2} = **{p3,p4}** | `:108` missing = {p1,p2}−{p3,p4} = **{p1,p2}** |
| `:117-125` persist pending p3,p4 | `:117-125` persist pending p1,p2 |
| `:126` send RequestPosts([p3,p4]) ──► | `:126` send RequestPosts([p1,p2]) ◄── |

---

## Phase 7 — send PostBatch (full content)

| D1 | D2 |
|---|---|
| handle() ← RequestPosts[p1,p2] | handle() ← RequestPosts[p3,p4] |
| `:80` activePosts([p1,p2]) | `:80` activePosts([p3,p4]) |
| `:82-88` send PostBatch(p1,p2 content) ──► | `:82-88` send PostBatch(p3,p4 content) ◄── |

---

## Phase 8 — insert + ack + complete

| D1 | D2 |
|---|---|
| handle() ← PostBatch[p3,p4] | handle() ← PostBatch[p1,p2] |
| `:133` insertAll(p3,p4) | `:133` insertAll(p1,p2) |
| `:137` send Ack ◄── | `:137` send Ack ──► |
| `:141-143` pending empty → SyncComplete ◄── | `:141-143` pending empty → SyncComplete ──► |
| `:154-166` both complete → PeerState SUCCESS | `:154-166` both complete → PeerState SUCCESS |

---

## Result

Both DBs = `[p1,p2,p3,p4]`. Converged.

**Sole difference in whole flow:** `ConnectionCoordinator.kt:79` — D1 `shouldInitiate` TRUE (client, `:86` connects), D2 FALSE (server, `:82` waits). Phases 4-8 identical code on both, just over client link (`AppContainer.kt:72`) vs server link (`AppContainer.kt:89`).
