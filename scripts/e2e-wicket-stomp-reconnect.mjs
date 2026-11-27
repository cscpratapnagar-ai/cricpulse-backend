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
const finalBatterId = process.env.FINAL_BATTER_ID;

if (![wsUrl, inningsId, baseUrl, readyFile, doneFile, outputFile, expectedStriker, expectedNonStriker, dismissedPlayerId, finalBatterId].every(Boolean)) {
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
let failure;
let receivedMessages = [];
try {
  const messages = [];
  receivedMessages = messages;
  const messagePromise = new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error("Timed out waiting for both committed wicket STOMP events")), 30000);
    globalThis.__resolveWicketMessages = () => {
      if (messages.length >= 2) {
        clearTimeout(timeout);
        resolve(messages);
      }
    };
  });
  firstSocket = await connect(value => {
    messages.push(value);
    globalThis.__resolveWicketMessages?.();
  });
  fs.writeFileSync(readyFile, "subscribed");
  const received = await messagePromise;
  const [runOut, bowled] = received;

  if (runOut.inningsId !== inningsId
      || Number(runOut.runs) !== 1
      || Number(runOut.wickets) !== 1
      || Number(runOut.legalBalls) !== 1
      || runOut.strikerId !== expectedStriker
      || runOut.nonStrikerId !== expectedNonStriker
      || runOut.fallOfWickets?.[0]?.playerId !== dismissedPlayerId) {
    throw new Error("First STOMP snapshot did not match the non-striker RUN_OUT and replacement batter state");
  }
  if (bowled.inningsId !== inningsId
      || Number(bowled.runs) !== 1
      || Number(bowled.wickets) !== 2
      || Number(bowled.legalBalls) !== 2
      || bowled.strikerId !== finalBatterId
      || bowled.nonStrikerId !== expectedNonStriker
      || bowled.fallOfWickets?.[1]?.playerId !== expectedStriker) {
    throw new Error("Second STOMP snapshot did not match the Bowled dismissal and final replacement state");
  }
  const versions = received.map(item => Number(item.eventVersion));
  if (received.some(item => !item.eventId)
      || new Set(received.map(item => item.eventId)).size !== 2
      || versions.some(value => !Number.isFinite(value))
      || versions[1] <= versions[0]) {
    throw new Error("Wicket STOMP events must have unique IDs and strictly increasing event versions");
  }

  firstSocket.close();
  await sleep(150);
  secondSocket = await connect();
  const response = await fetch(baseUrl + "/public/innings/" + inningsId);
  if (!response.ok) throw new Error("Reconnect recovery GET failed with HTTP " + response.status);
  const recovered = await response.json();
  if (Number(recovered.eventVersion) !== versions[1]
      || Number(recovered.runs) !== 1
      || Number(recovered.wickets) !== 2
      || Number(recovered.legalBalls) !== 2
      || recovered.strikerId !== finalBatterId
      || recovered.nonStrikerId !== expectedNonStriker) {
    throw new Error("Reconnected public viewer did not recover the latest committed wicket snapshot");
  }
  secondSocket.close();
  fs.writeFileSync(outputFile, JSON.stringify({
    ok: true,
    eventIds: received.map(item => item.eventId),
    eventVersions: versions,
    recoveredEventVersion: recovered.eventVersion,
    reconnectSnapshotMatches: true
  }, null, 2));
  console.log("PASS: anonymous public STOMP viewer received RUN_OUT and BOWLED events in order");
  console.log("PASS: viewer reconnected and recovered the latest committed eventVersion via public REST snapshot");
} catch (error) {
  failure = error;
  firstSocket?.close();
  secondSocket?.close();
  fs.writeFileSync(outputFile, JSON.stringify({ ok: false, error: error.message, messages: receivedMessages }, null, 2));
  console.error(`FAIL: ${error.message}`);
  process.exitCode = 1;
} finally {
  fs.writeFileSync(doneFile, failure ? "failed" : "done");
}
