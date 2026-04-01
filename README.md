# MeshNet — Offline Secure Mesh Messenger

A real Android app for offline, encrypted, peer-to-peer messaging
using Bluetooth and WiFi Direct. No internet. No SIM. No server. Ever.

---

## What This App Does

- Generates a **Curve25519 cryptographic identity** on first launch (no account needed)
- Sends **end-to-end encrypted messages** using libsodium AES-256
- Signs every message with **Ed25519** so recipients can verify authenticity
- Routes messages across a **Bluetooth + WiFi Direct mesh** network
- **Forwards messages** for other users (each phone is a relay node)
- **Store & forward** — queues messages when no path exists, delivers when path appears
- **QR code contact exchange** — add contacts offline by scanning their screen
- Uses **gRPC (Protocol Buffers)** as the typed communication protocol between nodes
- Runs as a **foreground service** — always on, even when screen is off
- **Restarts automatically** after phone reboot

---

## Architecture

```
┌─────────────────────────────────────────────────┐
│              Flutter / Android UI                │
│    SetupActivity  MainActivity  ChatActivity     │
└───────────────────┬─────────────────────────────┘
                    │
┌───────────────────▼─────────────────────────────┐
│            MessageRepository                     │
│   Business logic — send, receive, decrypt        │
└──────┬──────────────────────────┬────────────────┘
       │                          │
┌──────▼──────┐          ┌────────▼────────┐
│  gRPC Layer │          │   CryptoManager  │
│  mesh.proto │          │  Curve25519      │
│  ChatMessage│          │  Ed25519 signing │
│  MeshService│          │  AES-256 encrypt │
└──────┬──────┘          └─────────────────┘
       │
┌──────▼──────────────────────────────────────────┐
│                  MeshRouter                      │
│   TTL · Deduplication · Routing table            │
│   Store & Forward queue · Flooding               │
└──────┬─────────────────────┬────────────────────┘
       │                     │
┌──────▼──────┐     ┌────────▼──────────┐
│  Bluetooth  │     │   WiFi Direct      │
│  RFCOMM     │     │   TCP port 8988    │
│  ~100m      │     │   ~300m            │
│  +mesh hops │     │   +mesh hops       │
└─────────────┘     └───────────────────┘
       │                     │
┌──────▼─────────────────────▼────────────────────┐
│              Room SQLite Database                │
│   contacts · messages · message queue            │
└──────────────────────────────────────────────────┘
```

---

## Project Structure

```
MeshNet/
├── app/
│   ├── src/main/
│   │   ├── proto/
│   │   │   └── mesh.proto              ← gRPC service + message definitions
│   │   ├── kotlin/com/meshnet/
│   │   │   ├── MeshNetApp.kt           ← Application class, singletons
│   │   │   ├── crypto/
│   │   │   │   └── CryptoManager.kt   ← All crypto: keygen, encrypt, sign
│   │   │   ├── model/
│   │   │   │   └── Models.kt          ← Contact, Message, Peer data classes
│   │   │   ├── db/
│   │   │   │   └── Database.kt        ← Room database, DAOs
│   │   │   ├── mesh/
│   │   │   │   ├── MeshRouter.kt      ← TTL, routing, store & forward
│   │   │   │   ├── BluetoothMeshService.kt  ← BT RFCOMM server/client
│   │   │   │   ├── WifiDirectService.kt     ← WiFi P2P + TCP
│   │   │   │   └── MessageRepository.kt    ← UI ↔ mesh bridge
│   │   │   ├── grpc/
│   │   │   │   └── MeshGrpcServiceImpl.kt  ← gRPC service implementation
│   │   │   └── ui/
│   │   │       ├── SetupActivity.kt    ← First launch, identity creation
│   │   │       ├── MainActivity.kt     ← Contact list
│   │   │       ├── ChatActivity.kt     ← Message thread
│   │   │       ├── IdentityActivity.kt ← QR code display
│   │   │       ├── AddContactActivity.kt ← QR scan + manual add
│   │   │       └── Adapters.kt         ← RecyclerView adapters
│   │   ├── res/                        ← Layouts, colors, drawables
│   │   └── AndroidManifest.xml
│   └── build.gradle                   ← All dependencies
├── build.gradle
├── settings.gradle
└── gradle.properties
```

---

## Setup Instructions (Step by Step)

### Step 1 — Install Android Studio

Download from: https://developer.android.com/studio
Install it. Open it. Let it download the Android SDK automatically.

### Step 2 — Open the Project

1. In Android Studio: **File → Open**
2. Navigate to the **MeshNet** folder
3. Click **OK**
4. Wait for Gradle to sync (2–5 minutes first time)
5. If it asks to upgrade Gradle — click **OK**

### Step 3 — Fix local.properties

Android Studio creates this automatically, but if not:
1. In project root create `local.properties`
2. Add one line: `sdk.dir=/path/to/your/android/sdk`
   - On Windows: `sdk.dir=C\:\\Users\\YourName\\AppData\\Local\\Android\\Sdk`
   - On Mac/Linux: `sdk.dir=/Users/YourName/Library/Android/sdk`

### Step 4 — Connect a Real Android Phone

**You need two real Android phones to test mesh — emulators have no Bluetooth.**

1. On your phone: **Settings → About Phone → tap Build Number 7 times**
2. Go to **Settings → Developer Options → Enable USB Debugging**
3. Connect phone via USB cable
4. Trust the computer when prompted on phone
5. Phone should appear in Android Studio top bar

### Step 5 — Grant Permissions on Phone

The app needs:
- **Bluetooth** — for mesh connections
- **Location** — required by Android for Bluetooth scanning
- **Nearby Devices** — Android 12+ Bluetooth permission
- **Camera** — for QR code scanning

These are requested on first launch. **Tap Allow on all of them.**

### Step 6 — Build and Run

1. Click the **green Run button** (▶) in Android Studio
2. Select your phone
3. App installs and opens automatically

### Step 7 — Test with Two Phones

Install on **Phone A** and **Phone B**:

```
Phone A:
1. Open app → enter name "Ahmed_Lahore" → tap Generate Identity
2. Go to Identity tab → show QR code

Phone B:
1. Open app → enter name "Sara_ISB" → tap Generate Identity
2. Tap + (Add Contact) → Scan QR → point at Phone A's screen
3. Ahmed_Lahore appears in contacts
4. Tap Ahmed_Lahore → type message → Send

Phone A:
5. Message appears in chat — delivered via Bluetooth mesh!
```

---

## How Encryption Works in This App

```
When Ahmed sends "Are you safe?" to Sara:

1. App calls: crypto.encrypt("Are you safe?", Sara.publicKey)
   → Uses Sara's Curve25519 public key + Ahmed's private key
   → Produces: ciphertext bytes (unreadable to anyone else)
   → Produces: nonce (random, used once)

2. App calls: crypto.sign(messageId + ciphertext)
   → Uses Ahmed's Ed25519 private key
   → Produces: signature (proves message is from Ahmed)

3. Message travels across mesh as:
   { ciphertext: [unreadable bytes], nonce: [...], signature: [...] }
   Every relay phone sees ONLY encrypted bytes — cannot read it

4. Sara's phone calls: crypto.decrypt(ciphertext, nonce, Ahmed.publicKey)
   → Uses Ahmed's public key + Sara's private key
   → Decrypts back to: "Are you safe?"

5. Sara's app verifies the signature
   → Confirms message really came from Ahmed

Nobody on the mesh can read the message. Only Sara can.
```

---

## Adding LoRa Later (When Ready)

When you want to extend range to city-to-city:

1. Add LoRa hardware (ESP32 + SX1276) at community nodes
2. Create `LoRaMeshService.kt` (same pattern as `BluetoothMeshService.kt`)
3. Register `router.onSendViaLora` callback
4. Add `LORA` to `TransportType` enum in `Models.kt`
5. **Zero changes needed to gRPC layer, crypto, or UI**

This is exactly why gRPC was used — the transport is swappable.

---

## Known Limitations (Current Version)

| Limitation | Explanation |
|---|---|
| Range ~100-300m | Bluetooth/WiFi Direct only — add LoRa for km range |
| Android only | iOS has restricted Bluetooth mesh APIs |
| Requires app on all relay phones | No relay = message may not reach destination |
| Battery use | Background services use ~5-10% extra per day |
| Large messages slow | Bluetooth RFCOMM is ~1-3 Mbps |

---

## Permissions Explained

| Permission | Why Needed |
|---|---|
| BLUETOOTH_SCAN | Discover nearby devices |
| BLUETOOTH_CONNECT | Connect to and from peers |
| BLUETOOTH_ADVERTISE | Make this device discoverable |
| ACCESS_FINE_LOCATION | Required by Android for BT scanning |
| CHANGE_WIFI_STATE | Form WiFi Direct groups |
| NEARBY_WIFI_DEVICES | Android 13+ WiFi Direct |
| FOREGROUND_SERVICE | Keep mesh running in background |
| CAMERA | Scan QR codes to add contacts |
| RECEIVE_BOOT_COMPLETED | Restart mesh after reboot |

---

## Cost Summary

| Component | Cost |
|---|---|
| App development | Your time only |
| Cryptographic identity | PKR 0 |
| Bluetooth mesh | PKR 0 |
| WiFi Direct mesh | PKR 0 |
| gRPC protocol | PKR 0 |
| Local database | PKR 0 |
| Server | PKR 0 (none exists) |
| Per message cost | PKR 0 forever |

---

Built with: Kotlin · Android SDK · gRPC · Protocol Buffers · libsodium · Room · ZXing
