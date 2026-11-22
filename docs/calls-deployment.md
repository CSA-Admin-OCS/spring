# Calls Deployment Guide

Video calls need a LiveKit server next to Spring. LiveKit (Apache 2.0) forwards each person's video
to the others; Spring never carries media. This guide sets one up and turns calls on. How the
calls code works is in [calls-architecture.md](calls-architecture.md).

## 1. Make the server

On a small server of its own (a `c7g.large` on AWS fits a club or class pilot), run LiveKit's
config generator, which writes a Docker Compose setup with Caddy for TLS and Redis:

```bash
docker run --rm -it -v $PWD:/output livekit/generate
```

Pick two DNS names when it asks, for example `rtc.opencodingsociety.com` for LiveKit and
`turn.opencodingsociety.com` for its TURN server, and point both at the server. Then start it with
the command the generator prints.

Ports to open: 443 (HTTPS and TURN over TLS, which is what gets video through school networks),
7881/TCP, and the UDP range the generator picked. Keep LiveKit's Prometheus port closed to the
internet.

## 2. Point LiveKit's webhooks at Spring

In the generated `livekit.yaml`, add:

```yaml
webhook:
  api_key: <the API key from keys:>
  urls:
    - https://spring.opencodingsociety.com/api/calls/webhook
```

Restart LiveKit. Spring checks each webhook's signature with the same key pair.

## 3. Give Spring the key pair

In Spring's `.env`:

```text
LIVEKIT_URL=wss://rtc.opencodingsociety.com
LIVEKIT_API_KEY=<the API key from livekit.yaml>
LIVEKIT_API_SECRET=<its secret, at least 32 characters>
calls.enabled=true
```

Restart Spring. On boot it creates the `call_room` and `call_attendance` tables.

## 4. Try one call

Open a call from a group on the Pages site in two browsers: one signed in as a teacher, one as a
student. Both should see each other, and Spring's log should show no `LiveKit ... failed` lines.

## Turning calls off

Set `calls.enabled=false` in `.env` and restart Spring: every `/api/calls` route answers 404 and
nothing runs. The tables stay and are unused; drop them with `DROP TABLE call_room` and
`DROP TABLE call_attendance` if calls are removed for good.

## Local development

Install LiveKit (`brew install livekit` on a Mac) and run it with a key pair whose secret is at
least 32 characters. `livekit-server --dev` uses the secret `secret`, which jjwt refuses, so use a
small config file instead:

```yaml
port: 7880
bind_addresses:
  - 127.0.0.1
rtc:
  tcp_port: 7881
  port_range_start: 51000
  port_range_end: 51100
  use_external_ip: false
  node_ip: 127.0.0.1
keys:
  APIcallsLocal: <a local secret of 32 or more characters>
webhook:
  api_key: APIcallsLocal
  urls:
    - http://127.0.0.1:8585/api/calls/webhook
```

```bash
livekit-server --config livekit.yaml
```

Then set `LIVEKIT_URL=ws://127.0.0.1:7880`, the key pair and `calls.enabled=true` in Spring's
`.env`.
