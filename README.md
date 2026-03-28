# 𓁟 Thoth Tool — Raw TCP Socket

A plugin tool that provides raw TCP socket communication for Thoth agents, with session management and intelligent line collection.

## Features

- **Connect** — Open a raw TCP connection to any host:port
- **Send** — Send raw text exactly as provided (no transformations)
- **Receive** — Collect response lines with silence-based detection
- **Disconnect** — Close connection and clean up resources
- **Multi-session** — Manage multiple concurrent TCP sessions

## Requirements

- Java 25+ (JDK)
- Thoth 0.1.0+ (with plugin system)

### 1. Configure Thoth

Add the tool to your `thoth.conf`:

```json
{
  ...,
  "tool": [
    {
      "name": "tcp-socket",
      "version": "0.1.0-SNAPSHOT",
      "enabled": true
    },
    ...
  ]
}
```

### 2. Place the JAR

Copy `build/libs/raw-tcp-0.1.0-SNAPSHOT.jar` into Thoth's plugin directory `providers/`.

## Usage

Once installed and configured, Thoth will automatically discover and load the TCP Socket tool. You can verify it's active via Thoth's monitoring socket or logs.

## License

GNU General Public License v3.0 — see [LICENSE](LICENSE).
