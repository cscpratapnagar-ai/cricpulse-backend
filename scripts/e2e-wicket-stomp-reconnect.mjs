#!/usr/bin/env node
import fs from "node:fs";

const wsUrl = process.env.WS_URL;
const inningsId = process.env.INNINGS_ID;
const baseUrl = process.env.BASE_URL;
const readyFile = process.env.WS_READY_FILE;
const doneFile = process.env.WS_DONE_FILE;
const outputFile = process.env.WS_OUTPUT_FILE;
const expectedStriker = process.env.NEW_BATTER_ID;
const expectedNonStriker = process.env.STRIKER_ID;
const dismissedPlayerId = process.env.DISMISSED_PLAYER_ID;

if (![wsUrl, inningsId, baseUrl, readyFile, doneFile, outputFile, expectedStriker, expectedNonStriker].every(Boolean)) {
  throw new Error("WS_URL, INNINGS_ID, BASE_URL, WS_READY_FILE, WS_DONE_FILE, WS_OUTPUT_FILE, NEW_BATTER_ID and STRIKER_ID are required");
}
if (typeof WebSocket === "undefined") throw new Error("Node.js 22+ built-in WebSocket is required");

function parseFrame(raw) {
  const normalized = raw.replace(/\r\n/g, "\n");
  const separator = normalized.indexOf("\n\n");
  if (separator < 0) return null;
  const lines = normalized.slice(0, separator).split("\n");
  const command = lines.shift()?.trim();
  const headers = Object.fromEntries(lines.filter(Boolean).map(line => {
    const index = line.indexOf(":");
    return [line.slice(0, index), line.slice(index + 1)];
  }));
  return { command, headers, body: normalized.slice(separator + 2) };
}

function sendFrame(socket, command, headers = {}) {
  const headerText = Object.entries(headers).map(([key, value]) => `${key}:${value}`).join("\n");
  socket.send(`${command}\n${headerText}\n\n\0`);
}

function connect(onPayload = () => {}) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(wsUrl);
    let buffer = "";
    let subscribed = false;
    const timeout = setTimeout(() => {
      socket.close();
      reject(new Error("Timed out connecting and subscribing to public STOMP topic"));
    }, 15000);
    socket.addEventListener("open", () => {
      sendFrame(socket, "CONNECT", { "accept-version": "1.2", "heart-beat": "0,0" });
    });
    socket.addEventListener("message", async event => {
      try {
        const data = typeof event.data === "string" ? event.data : await event.data.text();
        buffer += data.replace(/\r\n/g, "\n");
        while (buffer.includes("\0")) {
          const end = buffer.indexOf("\0");
          const raw = buffer.slice(0, end);
          buffer = buffer.slice(end + 1);
          const frame = parseFrame(raw);
          if (!frame) continue;
          if (frame.command === "CONNECTED" && !subscribed) {
            sendFrame(socket, "SUBSCRIBE", {
              id: `wicket-reconnect-${Date.now()}`,
              destination: `/topic/public/innings/${inningsId}`,
              ack: "auto"
            });
            subscribed = true;
            clearTimeout(timeout);
            setTimeout(() => resolve(socket), 300);
          } else if (frame.command === "MESSAGE") {
            onPayload(JSON.parse(frame.body));
          } else if (frame.command === "ERROR") {
            throw new Error(`STOMP broker ERROR: ${frame.body}`);
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
      reject(new Error("Public STOMP WebSocket transport failed"));
    });
  });
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
let firstSocket;
let secondSocket;
let payload;
let failure;
try {
  const messagePromise = new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error("Timed out waiting for committed RUN_OUT STOMP event")), 30000);
    globalThis.__resolveWicketMessage = value => {
      clearTimeout(timeout);
      resolve(value);
    };
  });
  firstSocket = await connect(value => {
    if (!payload) {
      payload = value;
      globalThis.__resolveWicketMessage?.(value);
    }
  });
  fs.writeFileSync(readyFile, "subscribed");
  payload = await messagePromise;

  if (payload.inningsId !== inningsId
      || Number(payload.runs) !== 1
      || Number(payload.wickets) !== 1
      || Number(payload.legalBalls) !== 1
      || payload.strikerId !== expectedStriker
      || payload.nonStrikerId !== expectedNonStriker) {
    throw new Error(`STOMP wicket snapshot mismatch: ${JSON.stringify({
      inningsId: payload.inningsId, runs: payload.runs, wickets: payload.wickets,
      legalBalls: payload.legalBalls, strikerId: payload.strikerId, nonStrikerId: payload.nonStrikerId
    })}`);
  }
  if (!Array.isArray(payload.fallOfWickets) || payload.fallOfWickets.length !== 1
      || payload.fallOfWickets[0].playerId !== dismissedPlayerId
      || Number(payload.fallOfWickets[0].runs) !== 1) {
    throw new Error("STOMP snapshot did not include the committed fall-of-wicket projection");
  }
  if (typeof payload.eventVersion !== "number" || !payload.eventId) {
    throw new Error("STOMP snapshot is missing eventId or eventVersion");
  }

  firstSocket.close();
  await sleep(150);
  secondSocket = await connect();
  const response = await fetch(`${baseUrl}/public/innings/${inningsId}`);
  if (!response.ok) throw new Error(`Reconnect recovery GET failed with HTTP ${response.status}`);
  const recovered = await response.json();
  if (Number(recovered.eventVersion) !== Number(payload.eventVersion)
      || Number(recovered.runs) !== 1
      || Number(recovered.wickets) !== 1
      || recovered.strikerId !== expectedStriker
      || recovered.nonStrikerId !== expectedNonStriker) {
    throw new Error("Reconnected public viewer did not recover the exact committed wicket snapshot");
  }
  secondSocket.close();
  fs.writeFileSync(outputFile, JSON.stringify({
    ok: true,
    eventId: payload.eventId,
    eventVersion: payload.eventVersion,
    recoveredEventVersion: recovered.eventVersion,
    reconnectSnapshotMatches: true
  }, null, 2));
  console.log("PASS: anonymous public STOMP viewer received the real RUN_OUT event");
  console.log("PASS: viewer reconnected and recovered the same committed eventVersion via public REST snapshot");
} catch (error) {
  failure = error;
  firstSocket?.close();
  secondSocket?.close();
  fs.writeFileSync(outputFile, JSON.stringify({ ok: false, error: error.message, payload }, null, 2));
  console.error(`FAIL: ${error.message}`);
  process.exitCode = 1;
} finally {
  fs.writeFileSync(doneFile, failure ? "failed" : "done");
}
