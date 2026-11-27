#!/usr/bin/env node
import fs from "node:fs";

const { WS_URL, BASE_URL, INNINGS_ID, WS_READY_FILE, WS_DONE_FILE, WS_OUTPUT_FILE, EXPECTED_DISMISSALS } = process.env;
if (![WS_URL, BASE_URL, INNINGS_ID, WS_READY_FILE, WS_DONE_FILE, WS_OUTPUT_FILE, EXPECTED_DISMISSALS].every(Boolean)) {
  throw new Error("Missing required STOMP dismissal monitor environment");
}
if (typeof WebSocket === "undefined") throw new Error("Node.js 22+ built-in WebSocket is required");
const expected = JSON.parse(EXPECTED_DISMISSALS);
if (!Array.isArray(expected) || expected.length < 1) throw new Error("EXPECTED_DISMISSALS must be a non-empty JSON array");

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
function frame(command, headers = {}) {
  return `${command}\n${Object.entries(headers).map(([key, value]) => `${key}:${value}`).join("\n")}\n\n\0`;
}
function parse(raw) {
  const normalized = raw.replace(/\r\n/g, "\n");
  const split = normalized.indexOf("\n\n");
  if (split < 0) return null;
  const lines = normalized.slice(0, split).split("\n");
  const command = lines.shift()?.trim();
  const headers = Object.fromEntries(lines.filter(Boolean).map(line => {
    const at = line.indexOf(":");
    return [line.slice(0, at), line.slice(at + 1)];
  }));
  return { command, headers, body: normalized.slice(split + 2) };
}
async function subscribe(onMessage) {
  const socket = new WebSocket(WS_URL);
  let buffer = "";
  let subscribed = false;
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error("Timed out connecting/subscribing to public STOMP topic")), 15000);
    socket.addEventListener("open", () => socket.send(frame("CONNECT", { "accept-version": "1.2", "heart-beat": "0,0" })));
    socket.addEventListener("message", async event => {
      try {
        const data = typeof event.data === "string" ? event.data : await event.data.text();
        buffer += data.replace(/\r\n/g, "\n");
        while (buffer.includes("\0")) {
          const end = buffer.indexOf("\0");
          const parsed = parse(buffer.slice(0, end));
          buffer = buffer.slice(end + 1);
          if (!parsed) continue;
          if (parsed.command === "CONNECTED" && !subscribed) {
            socket.send(frame("SUBSCRIBE", { id: `dismissal-${Date.now()}`, destination: `/topic/public/innings/${INNINGS_ID}`, ack: "auto" }));
            subscribed = true;
            clearTimeout(timeout);
            await sleep(350);
            resolve();
          } else if (parsed.command === "MESSAGE") {
            onMessage(JSON.parse(parsed.body));
          } else if (parsed.command === "ERROR") {
            throw new Error(`STOMP ERROR: ${parsed.body}`);
          }
        }
      } catch (error) {
        clearTimeout(timeout);
        socket.close();
        reject(error);
      }
    });
    socket.addEventListener("error", () => {
      clearTimeout(timeout);
      reject(new Error("Public STOMP transport failed"));
    });
  });
  return socket;
}
let socket;
let messages = [];
let failure;
try {
  const completed = new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error(`Expected ${expected.length} dismissal events, received ${messages.length}`)), 45000);
    globalThis.__checkDismissals = () => {
      if (messages.length >= expected.length) {
        clearTimeout(timeout);
        resolve();
      }
    };
  });
  socket = await subscribe(payload => {
    messages.push(payload);
    globalThis.__checkDismissals?.();
  });
  fs.writeFileSync(WS_READY_FILE, "subscribed");
  await completed;

  const ids = messages.map(item => item.eventId);
  const versions = messages.map(item => Number(item.eventVersion));
  if (ids.some(id => typeof id !== "string" || !id) || new Set(ids).size !== ids.length) {
    throw new Error("Dismissal events must have unique non-empty event IDs");
  }
  if (versions.some(value => !Number.isFinite(value)) || versions.some((value, i) => i > 0 && value <= versions[i - 1])) {
    throw new Error("Dismissal event versions must increase strictly");
  }
  for (let i = 0; i < expected.length; i++) {
    const actual = messages[i];
    const recentWicket = (actual.recentBalls ?? []).filter(ball => ball.wicketType).at(-1);
    const fow = (actual.fallOfWickets ?? []).at(-1);
    if (actual.inningsId !== INNINGS_ID
        || Number(actual.wickets) !== i + 1
        || recentWicket?.wicketType !== expected[i].wicketType
        || fow?.playerId !== expected[i].dismissedPlayerId) {
      throw new Error(`STOMP dismissal #${i + 1} mismatch: expected ${expected[i].wicketType}/${expected[i].dismissedPlayerId}; got ${recentWicket?.wicketType}/${fow?.playerId}`);
    }
  }

  socket.close();
  await sleep(150);
  socket = await subscribe(() => {});
  const response = await fetch(`${BASE_URL}/public/innings/${INNINGS_ID}`);
  if (!response.ok) throw new Error(`Reconnect snapshot returned HTTP ${response.status}`);
  const snapshot = await response.json();
  const latest = messages.at(-1);
  if (Number(snapshot.eventVersion) !== versions.at(-1)
      || Number(snapshot.wickets) !== Number(latest.wickets)
      || snapshot.strikerId !== latest.strikerId
      || snapshot.nonStrikerId !== latest.nonStrikerId) {
    throw new Error("Reconnect REST snapshot does not match the latest committed STOMP state");
  }
  socket.close();
  fs.writeFileSync(WS_OUTPUT_FILE, JSON.stringify({ ok: true, eventIds: ids, eventVersions: versions, dismissals: expected, reconnectSnapshotMatches: true }, null, 2));
  console.log(`PASS: ${expected.length} dismissal STOMP events arrived once, in order, with matching dismissal/FOW state`);
  console.log("PASS: public subscriber reconnected and recovered the latest committed snapshot");
} catch (error) {
  failure = error;
  socket?.close();
  fs.writeFileSync(WS_OUTPUT_FILE, JSON.stringify({ ok: false, error: error.message, messages }, null, 2));
  console.error(`FAIL: ${error.message}`);
  process.exitCode = 1;
} finally {
  fs.writeFileSync(WS_DONE_FILE, failure ? "failed" : "done");
}
