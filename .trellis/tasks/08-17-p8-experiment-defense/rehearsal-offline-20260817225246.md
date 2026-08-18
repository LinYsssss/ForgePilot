# Offline Webhook Rehearsal

- Generated: 2026-08-18T05:52:48.0161061Z
- Payload: p8-offline-payload-20260817225246.json
- Delivery: p8-offline-20260817225247
- Payload SHA-256: 6cb9d4a5d07868d7f998d1f51527433afa48be3cc55e4cbf91e6dbb00b73a5f1
- Source commit SHA: 2d549e5 (worktree had separate uncommitted P8 changes)
- Demo base SHA: 6977028167f12d7c71e74ae49b9fc924e941c6fb
- Demo head SHA: e205e0e23b0334d8bd5b48f6defdc96ec91aaeb4
- Signature: HMAC-SHA256 over the exact payload bytes
- HTTP status: 202
- Outcome: PROCESSED
- Agent run: 1
- Terminal: false (observed after 45s)
- Run status: PREPARING_REPOSITORY
- Gate verdict: not reached

## Redaction

The webhook secret was supplied out-of-band and was not written to this record.

AgentRun observation: step 1 remained PENDING in PREPARING_REPOSITORY because this local rehearsal intentionally ran with `AGENT_SCHEDULING_ENABLED=false` and no RabbitMQ. The signed webhook was nevertheless durably accepted with HTTP 202 / PROCESSED and AgentRun id 1; this record is a link/injection rehearsal, not a model-quality or terminal-run claim.

## Machine-readable record

```json
{
  "generatedAt": "2026-08-18T05:52:48.0161061Z",
  "baseUrl": "http://localhost:8080",
  "payload": "p8-offline-payload-20260817225246.json",
  "deliveryId": "p8-offline-20260817225247",
  "payloadSha256": "6cb9d4a5d07868d7f998d1f51527433afa48be3cc55e4cbf91e6dbb00b73a5f1",
  "sourceCommitSha": "2d549e5",
  "demoBaseSha": "6977028167f12d7c71e74ae49b9fc924e941c6fb",
  "demoHeadSha": "e205e0e23b0334d8bd5b48f6defdc96ec91aaeb4",
  "signatureAlgorithm": "HMAC-SHA256",
  "signatureVerifiedBy": "/api/webhooks/scm/github",
  "httpStatus": 202,
  "outcome": "PROCESSED",
  "agentRunId": 1,
  "runStatus": "PREPARING_REPOSITORY",
  "terminal": false,
  "gateVerdict": null,
  "steps": [
    { "sequenceNo": 1, "stepType": "PREPARING_REPOSITORY", "status": "PENDING" }
  ],
  "secretStored": true,
  "secretValueRecorded": false
}
```
