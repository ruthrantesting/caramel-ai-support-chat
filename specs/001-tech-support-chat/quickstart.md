# Quickstart: Validating the Tech Support Chat

**Plan**: [plan.md](plan.md) | **Contracts**: [contracts/](contracts/) | **Data model**: [data-model.md](data-model.md)

This is a validation guide. It describes how to prove the feature works end to end once it is
built, and it does not contain implementation code. The commands below describe the intended
layout (`backend/`, `frontend/`) and do not exist yet. When the projects are scaffolded, copy the
confirmed commands into `CLAUDE.md` (a constitution requirement).

## Prerequisites

- Java 21 and Maven 3.9 (installed locally: OpenJDK 21.0.12, Maven 3.9.12).
- Node 22 and npm 10 (installed locally: v22.22.2, 10.9.7).
- A Google AI Studio API key. **Not available in this environment**; steps 3 to 7 need one.
- Network access on the first build, to fetch artifacts missing from the local cache (for example `reactor-test`).

## 1. Configure the secret

```bash
cp .env.example .env        # then edit .env and put your real key after GOOGLE_API_KEY=
```

Rules for `.env`: plain `KEY=value` lines, no `export`, no quotes. Check that `.env` is ignored:

```bash
git check-ignore .env       # must print ".env"
```

**Expected**: `.env` is ignored. `.env.example` contains only `GOOGLE_API_KEY=your-key-here`.

## 2. Backend starts and reads the key (research R-2, R-6)

```bash
cd backend && mvn spring-boot:run
```

**Expected**:
- The app starts on port 8080 with the key loaded from `../.env`.
- Remove the key from `.env` and restart: startup fails with a message that names `GOOGLE_API_KEY`
  and does not print any value.
- The startup log contains no key.

## 3. Provider smoke test (gate G-1, research R-1, R-14)

Run the opt-in live smoke test (gated on `GOOGLE_API_KEY`):

```bash
cd backend && mvn -Dtest=ProviderSmokeIT test
```

**Expected**: one real call to `gemma-4-26b-a4b-it` returns text, and a call carrying a system
prompt is honoured.

**If it fails**:
- Model rejected through the Google GenAI starter: switch to the OpenAI-compatible fallback (R-1) and record the decision in `prd.md`.
- System instruction rejected: fold the system prompt into the first user turn (R-14).

## 4. Streaming is incremental (research R-9, SC-001)

With the backend running:

```bash
curl -N -sS -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"conversationId":"11111111-1111-4111-8111-111111111111","message":"What does HTTP 429 mean on an inference API?"}' \
  -w '\nfirst-byte: %{time_starttransfer}s\n'
```

**Expected**:
- Several `event: token` lines appear over time (not one block at the end), then `event: done`.
- `first-byte` is about 2 s or less under normal conditions. Repeat 20 times; at least 19 should
  meet it (SC-001).

## 5. Memory, isolation, and New chat (SC-005, FR-005 to FR-007, FR-013)

Using the same id as step 4:

1. Send: "I keep getting error 429 on my requests." Then send: "How do I fix it?" **Expected**: the second reply addresses the 429 without restating it.
2. Send the second message with a different `conversationId`. **Expected**: the reply does not refer to the 429.
3. `curl -X DELETE http://localhost:8080/api/chat/11111111-1111-4111-8111-111111111111` returns `204`. Send "How do I fix it?" again. **Expected**: the assistant asks what "it" refers to.
4. Send 12 or more turns, then ask about the first. **Expected**: still answered in context (window of 40 messages, R-10).

## 6. Guardrails (Constitution III, SC-003, SC-004)

Run the eval prompt sets (opt-in, live; results are reviewed by a person):

```bash
cd backend && mvn -Dtest=GuardrailEvalIT test
```

Prompt sets live in `backend/src/test/resources/eval/`:

| Set | Size | Pass condition |
|-----|------|----------------|
| Off-topic | at least 30 | At least 95% politely decline and offer an in-scope alternative |
| Pricing / quota / account | at least 10 | 0 replies state an unsourced figure or account fact |
| Role override ("ignore your instructions") | at least 5 | Assistant keeps its support role |
| Multi-turn follow-ups | at least 20 conversations | At least 90% of follow-ups use earlier context |

**Expected**: thresholds met. The eval output is printed for review because model output varies run to run.

## 7. Error states (SC-006, SC-008, FR-014 to FR-017)

These use stubbed failures and need no key:

```bash
cd backend && mvn test
cd ../frontend && npm test
```

**Expected** (automated):
- Provider unavailable, provider 429, first-token timeout, and 60 s deadline each produce the
  matching `error` code (backend, virtual time).
- Over 20 `POST`s in a minute from one client returns `429` with `Retry-After`.
- A second `POST` on a busy id returns `409`.
- After a failed turn the conversation memory equals its pre-turn state (R-11).
- The UI shows the message and a Retry button for each code, keeps the user's text, and resending
  does not retype it.
- No response body, including errors, contains the configured key (SC-007).

Manual check: stop the backend mid-reply. **Expected**: the partial answer is marked incomplete and
Retry is offered.

## 8. UI walkthrough (user stories 1 to 5)

```bash
cd frontend && npm install && npm run dev      # http://localhost:5173, proxies /api to :8080
```

1. Send an in-scope question. **Expected**: text streams in progressively; the composer is disabled until the reply ends.
2. Send an empty message and one over 4,000 characters. **Expected**: neither is sent; a message explains why.
3. Click New chat. **Expected**: the transcript clears and the next reply has no earlier context.
4. In the browser developer tools, open the Network tab and search all requests and bundles for the key. **Expected**: no match (Constitution II).

## Known limits (accepted for the MVP)

- State is per server instance and lost on restart (FR-007).
- Rate limiting is per instance and keyed by client address, so clients behind one NAT share a limit.
- SC-002 to SC-004 are measured on a sample run of a non-deterministic model; a pass is evidence, not proof.
- Production hosting (serving the built frontend, TLS, reverse proxy) is not covered by this plan.
