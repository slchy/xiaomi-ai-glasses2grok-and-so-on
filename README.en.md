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

1. Understanding goes through dsh over ACP. The harness does not host a second chat or vision model. The earlier generic CLI call is no longer on this path.
2. The rate table is still a design. The running path submits each sentence to TTS at a fixed 1.0x and does not track supply.

### Architecture

```text
glasses ⇄ SPP ⇄ phone (frame forwarding only) ⇄ raw TCP (5-byte header) ⇄ cloud harness
                                                      │
                         streaming ASR / Smart Turn / dsh / streaming TTS
```

The phone strips the SPP, SAR, and MASS headers and forwards mSBC unchanged. The cloud turns mSBC into PCM16 at 16 kHz. Downlink is PCM16 at 24 kHz, played with `AudioTrack` over A2DP. Uplink is 16 kHz and downlink is 24 kHz. The player uses 24 kHz. The two directions do not share one sample-rate constant.

The phone and the cloud use raw TCP with a fixed 5-byte header:

```text
[0]    type    1 audio (uplink mSBC, downlink PCM16)
               2 image (uplink JPEG, downlink a take_photo control)
               3 wake context (transaction id and request id)
[1:5]  length  u32 little-endian
[5:]   payload
```

One frame is at most 32 MB. A bad length drops the whole receive buffer. A partial read has to be completed before the frame is parsed. Uplink writes go wake, then image, then speech.

| Layer | Who | Work |
|---|---|---|
| Glasses | Unchanged | Wake word in firmware, mSBC, echo cancellation, JPEG, SPP |
| Phone | To build | SPP and reliable delivery, MASS demux, frame forwarding, photo trigger, A2DP playback |
| Cloud harness | To build | mSBC decode, energy gate, streaming recognition, end-of-turn, dsh, streaming TTS |

Three flows.

Dialog:

```text
speech → glasses mic → mSBC → SPP channel 3 → phone strips only the MASS header
      → TCP type 1 → cloud decodes PCM16 at 16 kHz → energy gate → streaming ASR
      → Smart Turn → dsh → streaming text → streaming TTS (PCM16 at 24 kHz)
      → TCP type 1 → phone AudioTrack → A2DP → glasses play it
```

mSBC sent back on channel 3 is acknowledged and produces no sound. That channel carries uplink only. Playback is A2DP.

The firmware cancels echo. During playback the residual is about −80 dBFS, and the estimated attenuation is at least 75 dB. Double-talk still works. The cloud does not run an echo canceller.

End of turn is a semantic probability, not a fixed silence timer. A pause inside a sentence scored about 0.19. A finished utterance scored about 0.96 to 0.99. The recognizer does not treat silence as speech activity. With no speech the session has to be closed, or the gateway idle-timeouts.

Photo question:

```text
dsh decides it needs a look → MCP tool take_photo
→ TCP type 2 → phone sends WearPacket type 14, id 4
→ reuse the transaction id and request id from this wake
→ MassType 3 then MassType 4 → phone joins them into a JPEG
→ TCP type 2 → the picture goes to dsh → text → TTS → A2DP
```

The photo happens when the model decides it needs to see. A transaction id invented on the phone gets no image back.

Wake:

```text
say the wake word stored in firmware → glasses start recording
→ WearPacket type 14, id 10 arrives on SPP
→ the phone keeps the transaction id and request id and sends them as TCP type 3
→ the phone starts forwarding audio
```

The wake word lives in firmware. The phone cannot change it. Recognition also transcribes that wake sentence, and the cloud drops it with a word list. It is not a turn. Only one SPP session exists at a time. A second connection fails.

### Pacing

The table below is the design. It is not on the running path. The running path submits each sentence at a fixed 1.0x. Resuming the session is about 1.45 s and the first TTS chunk is about 0.75 s. Those two already add up to about 2.2 s, before the recognizer's tail and playout, which is above the 800 ms target.

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

A hit stops the current dsh process and plays a confirmation immediately.

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
Cloud: task state machine, stored results, dsh calls, replay after reconnect
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

`cliSessionId` continues the same dsh session. `delivered` becomes true after the ack.

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

One dsh process runs tasks one at a time, and it can be interrupted.

| The user says | Meaning | Action |
|---|---|---|
| 「算了」「取消」 | Interrupt | Stop dsh and play 「已取消」 |
| 「好了吗」 | Status | Answer locally, and leave dsh alone |
| 「不，改成后天」 | Revision | Queue it next, with the current context |
| 「顺便订个外卖」 | New task | Queue it next |

A task past 3 minutes plays 「有点久，我先停一下」 and stops. A queue longer than 5 items accepts nothing new.

### Phone client

| On the phone | On the cloud |
|---|---|
| SPP connect, reliable delivery, session setup | mSBC decode, streaming recognition, dsh, streaming TTS |
| MASS demux (speech, image, OCR) | Conversation context |
| Strip headers and forward mSBC unchanged | Rate control (not built; the running path is fixed 1.0x) |
| Trigger the photo with the ids from the wake | Multi-device management |
| Play 24 kHz PCM over A2DP, reconnect | Task state and stored results |

The phone does not code mSBC. The cloud uses a standard mSBC decoder and produces 16 kHz PCM.

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

The photo command is WearPacket type 14, id 4. Id 9 is a different command. The transaction id and the request id are the pair that arrived with this wake (type 14, id 10). The image returns on channel 3 as MassType 3, then 4. The first wake-audio payload can be far larger than 582 bytes. The length fields in the header are what count.

Channel 1 carries service calls from the glasses, and the phone dispatches them. Payment, factory mode, and interconnection are outside this design.

### Cloud harness

Understanding goes through dsh over ACP. Streaming text is handed to TTS one sentence at a time. The earlier direct CLI calls are no longer on this path.

Recognition is a streaming service that emits text while audio is still arriving. One API that only accepts a whole utterance inline is not used for uplink. Synthesis is streaming PCM16 at 24 kHz.

| Requirement | Why |
|---|---|
| Streaming recognition and streaming synthesis | Waiting for the whole answer puts the first syllable several seconds out |
| Continue the same dsh session | Each turn needs the previous context |
| Split on sentences | Later rate changes are per sentence. Every sentence is 1.0x for now |

On a nearer site, three upstream first-byte times improved by 0.45 to 0.72 s. The first TTS chunk dropped from 2.2–2.9 s to 0.75–1.0 s.

### Checked, and not checked yet

Checked:

| Item | Result |
|---|---|
| Frames, reliable delivery, L2 | Exchanged with this pair of glasses |
| An SPP session opened by this client | It connects and completes the handshake when the official app is not holding the socket |
| mSBC | The cloud decodes it to 16 kHz PCM. Sending mSBC back on channel 3 is acknowledged and silent |
| Vision image | After reusing the ids from the wake, MassType 3 and 4 join into a JPEG whose length matches the declaration |
| Wake | type 14, id 10. The transcribed wake sentence is dropped |
| Live speech | Streaming recognition, semantic end-of-turn, dsh, streaming TTS, and sound over A2DP have completed one turn |
| Firmware echo cancellation | Double-talk during playback still works |

Not checked yet:

| Item | What it blocks |
|---|---|
| Changing the rate from supply | The 800 ms target for the first syllable. The running path is still fixed 1.0x |
| Whether more than the device window of 3 frames in flight causes a NAK | Bulk sends |
| Whether the device send timeout is 10 s or the 6 s that once appeared in a log | That log is gone, so both numbers stay on record |

### Cost and latency

The host is an existing machine. Recognition and synthesis follow the chosen service. From the end of speech to the start of sound, the two pieces already separated are about 1.45 s to resume the session and about 0.75 s for the first TTS chunk. The recognizer tail and playout are not yet split out of the total.

### Build order

```text
0  Session skeleton: connect and finish the handshake     done
1  Decode mSBC to PCM in the cloud and confirm speech     done
2  Move playback to A2DP. Channel 3 playback was silent   done
3  Streaming recognition, semantic end-of-turn, dsh, TTS  one turn done
4  Photo after the wake, reusing the ids, join the JPEG   done
5  Adaptive rate                                           not built
```

### Paths this design leaves aside

| Path | Why |
|---|---|
| Build recognition, the model, and synthesis in-house | That is the whole intelligence path |
| Send the dialog audio back on channel 3 | The frames are acknowledged and nothing is played |
| Carry the dialog on HFP | Audio after the wake word is on SPP |
| Open the system assistant after wake, then use the HFP microphone | That still depends on HFP, and the microphone may already be in use while recording |
| Take audio from inside the official process | The intelligence path is still in-house, and the attachment has to stay in that process |
| Hang a second vision or chat model on the harness | Understanding stays with dsh |
| Use a remote callback for live dialog | That kind of callback fits asynchronous work |

Uplink speech, photo, and wake stay on SPP. Playback is A2DP, including after SPP has dropped.
