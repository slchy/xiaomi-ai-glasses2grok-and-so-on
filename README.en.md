# xiaomi-ai-glasses2grok-and-so-on

[中文](README.md)

Connect Xiaomi AI Glasses to your own voice agent. This repository currently publishes only the A5A5 frame protocol implementation. The application agent lives in a private implementation. The product design is at the end.

The frame protocol is in [docs/a5a5.en.md](docs/a5a5.en.md). Tested properties are in [docs/FORMAL.en.md](docs/FORMAL.en.md). The implementation is `python/a5a5.py`.

```bash
python tests/test_frame.py
```

```python
from a5a5 import build, parse

frame = build(b"hi", ftype=3, seq=0)
frames, bad_crc, resync, dropped = parse(frame)
```

Put `python/` on `PYTHONPATH` before the call above.

License: [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/).

## Product design

Settled on 2026-11-05.

Two decisions:

1. Call each vendor's AI agent CLI directly. One generic invocation covers them.
2. Pace the playback. Chinese speech rate moves between 3.4 and 4.8 characters per second with the backlog, and playback does not stop.

### Architecture

```text
glasses ⇄ SPP ⇄ phone (thin client) ⇄ WebSocket ⇄ cloud harness ⇄ AI agent CLI
                                                  │
                                          STT / TTS (external)
```

The cloud side only moves data and schedules work. Audio coding stays on the phone.

| Layer | Who | Work |
|---|---|---|
| Glasses | Unchanged | Wake word in firmware, mSBC, JPEG, SPP |
| Phone app | To build | SPP and reliable delivery, session setup, MASS demux, mSBC, photo trigger, audio forwarding |
| Cloud harness | To build (scripts) | Audio pipe, STT, CLI call, TTS, rate control |

Three flows.

Dialog:

```text
speech → glasses mic → mSBC → SPP channel 3 → phone decodes PCM16 at 16 kHz
      → WebSocket → harness → STT → text → CLI → streaming text → TTS → PCM
      → WebSocket → phone encodes mSBC → SPP channel 3 → glasses play it
```

Photo question:

```text
phone sends WearPacket type 14, id 4 → glasses take a photo
→ MassType 3 then MassType 4 → phone joins them into a JPEG
→ WebSocket upload → multimodal CLI → text → TTS → glasses
```

Wake:

```text
say the wake word stored on the glasses → glasses start recording
→ a wake event arrives on SPP → the phone starts forwarding audio
```

The wake word lives in the glasses firmware. The phone receives the event.

### Pacing

The rate follows how fast text arrives. Faster when text is ahead, slower when it is not, and playback keeps going.

| Language | Comfortable | Fast | Still intelligible |
|---|---|---|---|
| Chinese | 200–240 characters/min, about 3.5–4 per second | 300/min, 5 per second | above 400/min |
| English | 150 wpm, 2.5 words/second | 180–200 wpm | 275 wpm |

Chinese is about four characters a second.

| Step | Scale | Chinese rate | When |
|---|---|---|---|
| Fast | 1.15x | 4.6 /s | supply > 6 s |
| Normal | 1.00x | 4.0 /s | default |
| Slow | 0.90x | 3.6 /s | supply < 2 s |
| Very slow | 0.85x | 3.4 /s | supply < 1 s |

0.85x is the floor. Slower than that sounds like spelling the sentence out.

`supply` is how long playback can continue:

```text
supply = seconds of audio already synthesized and not yet played
       + characters already received and not yet synthesized / current rate
```

| supply | Action |
|---|---|
| < 1.0 s | drop to 0.85x |
| 1.0–2.0 s | drop to 0.90x |
| 2.0–4.0 s | stay at 1.00x |
| > 4.0 s, and the current scale is at most 1.0x | return to 1.00x |
| > 6.0 s | rise to 1.15x |

Three constraints:

1. Change speed only on a sentence boundary. One TTS request, one speed.
2. After a drop, wait until supply is above 3 seconds before rising again.
3. At 0.85x, insert a filler instead of going slower. For example “嗯……”, “让我想想”, “这个我查一下”.

| Measure | Target |
|---|---|
| Time from end of speech to the first syllable | < 800 ms |
| Underrun | 0 |

Buffer 1.5–2 seconds of audio (about 6–8 Chinese characters) before speaking, then keep the rate matched to the text.

```python
def pick_speed(supply_sec: float, last_speed: float) -> float:
    if supply_sec < 1.0:
        return 0.85
    if supply_sec < 2.0:
        return 0.90
    if supply_sec > 6.0:
        return 1.15
    if supply_sec > 4.0 and last_speed <= 1.0:
        return 1.00
    return last_speed
```

TTS has to stream one sentence at a time. Otherwise the speed cannot change.

PCM16, 16 kHz, mono is 32 KB/s. Twenty Chinese characters are about 5 seconds, about 160 KB. Streaming TTS has to keep delivering at least 32 KB/s.

### Asynchronous tasks

During a long agent task the glasses can drop the link because of a timeout, distance, or sleep. The result stays until it can be played.

| State | Action |
|---|---|
| Finished, and A2DP is up | Play it now |
| Finished, and A2DP is down | Queue it, and play after the system reconnects |
| Still disconnected after 5 minutes | Post a system notification, and keep the result for the next session |
| The user is speaking, or audio is already playing | Queue it at the priority below |

A long task gets a short confirmation first, then the result:

```text
User: check tomorrow's weather
After about 300 ms: okay, I'll look it up.
When the agent finishes: tomorrow is clear, -2 to 11 degrees.
```

That result plays over A2DP. SPP may already be down. Uplink speech stays on SPP channel 3.

### States

The state names match the voice state machines in LiveKit Agents and Pipecat.

```text
Agent:  idle → listening → thinking → speaking → listening
                                    ↓
                        stay in thinking while a tool is still running
User:   speaking | listening | away
```

A question or an approval request is one ordinary utterance. When it finishes, the agent returns to listening.

| Rule | Why |
|---|---|
| Stay in thinking while a tool runs | The away timer runs only when the agent is actually idle |
| 15 seconds of silence both ways enters away | Once the person has left, playback stays quiet |
| An interrupted message records interrupted and the cut time | The context keeps where the sentence stopped |

### Interruption

An interruption needs both enough words and enough time.

| Parameter | Value | Meaning |
|---|---|---|
| Minimum words | 2–3 Chinese characters | That many words during playback count as an interruption |
| Minimum duration | 300 ms | Coughs and noise stay out |
| Interruptions allowed | Set per utterance | An approval prompt plays through |
| Resume after a false interruption | On | After a false voice-activity hit, the original playback continues |

Local interruption words are handled on the phone, with no cloud round trip:

```text
算了 / 取消 / 停止 / 别查了 / 不用了 / 停一下 / 等等
```

A hit stops the current CLI and plays a confirmation immediately.

### Playback priority

There is one audio channel.

| Priority | Kind | Droppable | Deferrable |
|---|---|---|---|
| P0 | Interruption confirmation | No | No |
| P1 | Approval request | No | No |
| P2 | Agent question | No | No |
| P3 | Error | No | No |
| P4 | Task result | No | Yes |
| P5 | Progress and filler | Yes | Yes |

During a long task, insert 「嗯…让我想想」 automatically.

### Side effects and approval

| Operation | Gate |
|---|---|
| Read: query, search | Run it |
| Write: send a message, place an order, edit a file | P1 approval first |
| Irreversible: delete, transfer, publish | Two confirmations |

If an interruption arrives after a side effect has already happened, say what happened. A mail that has been sent stays sent.

### What the phone and the cloud each keep

```text
Phone: SPP coding, local queue, A2DP playback, reconnect
Cloud: task state machine, stored results, CLI calls, replay after reconnect
```

A task:

```jsonc
{
  "id": "task_01H...",
  "state": "queued | running | needs_input | needs_approval | done | failed | cancelled | orphaned",
  "kind": "chat | vision",
  "prompt": "...",
  "result": "...",
  "cliSessionId": "...",
  "createdAt": 0,
  "updatedAt": 0,
  "delivered": false
}
```

`cliSessionId` continues the same CLI session. `delivered` becomes true after the ack.

Reconnect:

```text
phone → cloud   {"type":"sync", "since": "<lastSeenTaskId>"}
cloud → phone   {"type":"tasks", "items":[...not yet delivered...]}
phone → cloud   {"type":"ack", "ids":["task_01H..."]}
```

Three rules:

1. Task state and `cliSessionId` live on the cloud.
2. Reconnect syncs undelivered results and unfinished tasks first.
3. A task disconnected for more than 10 minutes is marked orphaned. The next wake asks whether to continue it.

### Concurrency

One CLI process runs tasks one at a time, and it can be interrupted.

| The user says | Meaning | Action |
|---|---|---|
| 「算了」「取消」 | Interrupt | Stop the CLI and play 「已取消」 |
| 「好了吗」 | Status | Answer locally, and leave the CLI alone |
| 「不，改成后天」 | Revision | Queue it next, with the current context |
| 「顺便订个外卖」 | New task | Queue it next |

A task past 3 minutes plays 「有点久，我先停一下」 and stops. A queue longer than 5 items accepts nothing new.

### Phone client

| On the phone | On the cloud |
|---|---|
| SPP connect, reliable delivery, session setup | STT, model, TTS |
| MASS demux (speech, image, OCR) | Conversation context |
| mSBC decode on the way up, encode on the way down | Rate control |
| Photo trigger | Multi-device management |
| Forward PCM over WebSocket | Application policy |
| Local queue, A2DP playback, reconnect | Task state and stored results |

mSBC coding is the heavy work on the phone.

Channel 3 carries more than one kind of payload. The plaintext MASS layout is in the frame document. Demux uses MassType. Length does not separate audio from images.

| MassType | Goes to |
|---|---|
| 1, 2, 6, 7 | Speech |
| 3, 4 | Vision image, both into one buffer |
| 5 | OCR |
| 9 | Conversation original image |
| 11 | HD image |
| 8, 10 | Thumbnail and telemetry; this design leaves them unused |

Four requirements:

1. Split on MassType. An image fragment can also be 582 bytes.
2. MassType 3 and 4 share one buffer. The total length is only in `[6:10]` of the first fragment. Later fragments carry 0.
3. Keep a short tail until the declared length arrives.
4. After the 12-byte header, dialog audio is a run of 57-byte mSBC frames.

The joined image bytes are a JPEG.

The photo command is WearPacket type 14, id 4. Id 9 is a different command. The image returns on channel 3 as MassType 3, then 4.

Channel 1 carries service calls from the glasses, and the phone dispatches them. Payment, factory mode, and interconnection are outside this design.

### Cloud harness

The model is an agent CLI already installed on the machine. It has to stream, and it has to continue the same session.

| CLI | Streaming | Continue |
|---|---|---|
| Cursor CLI `agent` | `--output-format stream-json`, `--stream-partial-output` | `--continue` or `--resume`. `--mode ask` is available |
| CodeBuddy | `--output-format stream-json`, `--include-partial-messages` | `-c` or `-r`. ACP is also available |

Usual form:

```bash
agent -p --output-format stream-json --stream-partial-output \
      --mode ask --continue "<this turn's user text>"
```

| Requirement | Why |
|---|---|
| Streaming output | Waiting for the full answer puts the first syllable at 5–15 s |
| Continue the same session | Each turn needs the previous context |
| Split on sentences | Rate changes per sentence |

The harness runs on an existing small machine. Speech recognition and synthesis are external services. The agent CLI runs next to the harness.

### Checked, and not checked yet

Checked:

| Item | Result |
|---|---|
| Frames, reliable delivery, L2 | Exchanged with this pair of glasses |
| mSBC decode | Produced 16 kHz PCM |
| Vision image | Joined bytes are a JPEG |
| Photo command | type 14, id 4 |
| Wake | A wake event arrives on SPP |

Not checked yet:

| Item | What it blocks |
|---|---|
| An SPP session opened by this client itself | The whole path |
| A new mSBC encoder | Audio sent back on SPP |
| Sentence-sized streaming TTS, and per-sentence speed | The pacing |
| First-token time of each CLI | The pacing |

### Cost and latency

The host is an existing machine. Model cost follows that CLI's subscription. Recognition and synthesis follow the chosen service. Time to the first syllable is one round trip from the phone to the harness, plus the CLI's first token.

### Build order

```text
0  Session skeleton: connect, and start receiving audio
1  Decode mSBC, write a WAV, and confirm it is speech
2  Encode mSBC, send it back, and confirm the glasses play it
3  Cloud harness: audio pipe and CLI, echo text first
4  Add recognition and synthesis, and complete one spoken turn
5  Adaptive rate
6  Photo: type 14 id 4, join the JPEG, hand it to a multimodal CLI
```

### Paths this design leaves aside

| Path | Why |
|---|---|
| Build recognition, the model, and synthesis in-house | That is the whole intelligence path |
| Carry the dialog on HFP | Audio after the wake word is on SPP |
| Open the system assistant after wake, then use the HFP microphone | That still depends on HFP, and the microphone may already be in use while recording |
| Take audio from inside the official process | The intelligence path is still in-house, and the attachment has to stay in that process |
| Use a remote callback for live dialog | That kind of callback fits asynchronous work |

Send, receive, photo, and wake stay on SPP. A finished long task plays over A2DP, including after SPP has dropped.
