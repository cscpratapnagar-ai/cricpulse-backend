#!/usr/bin/env node
import fs from "node:fs";

const wsUrl = process.env.WS_URL;
const inningsId = process.env.INNINGS_ID;
const readyFile = process.env.WS_READY_FILE;
const doneFile = process.env.WS_DONE_FILE;
const outputFile = process.env.WS_OUTPUT_FILE;
const expectedFile = process.env.WS_EXPECTED_FILE;
const authToken = process.env.WS_AUTH_TOKEN;
const destination = process.env.WS_DESTINATION || `/topic/public/innings/${inningsId}`;
if (![wsUrl, inningsId, readyFile, doneFile, outputFile, expectedFile].every(Boolean)) {
  throw new Error("WS_URL, INNINGS_ID, WS_READY_FILE, WS_DONE_FILE, WS_OUTPUT_FILE and WS_EXPECTED_FILE are required");
}
if (typeof WebSocket === "undefined") {
  throw new Error("Node.js with built-in WebSocket support (Node 22+) is required");
}

const socket = new WebSocket(wsUrl);
const frames = [];
let buffer = "";
let subscribed = false;
let finished = false;
let failure = null;

function sendFrame(command, headers = {}, body = "") {
  const headerText = Object.entries(headers).map(([key, value]) => `${key}:${value}`).join("\n");
  socket.send(`${command}\n${headerText}\n\n${body}\0`);
}

function parseFrames() {
  while (true) {
    const start = buffer.indexOf("\n");
    if (start < 0) return;
    const separator = buffer.indexOf("\n\n");
    if (separator < 0) return;
    const command = buffer.slice(0, start).replace(/^\r/, "");
    const headerLines = buffer.slice(start + 1, separator).split("\n").filter(Boolean);
    const headers = Object.fromEntries(headerLines.map(line => {
      const index = line.indexOf(":");
      return [line.slice(0, index).replace(/\r$/, ""), line.slice(index + 1).replace(/\r$/, "")];
    }));
    const contentLength = headers["content-length"] ? Number(headers["content-length"]) : null;
    let bodyEnd;
    if (contentLength !== null) {
      bodyEnd = separator + 2 + contentLength;
      if (buffer.length <= bodyEnd) return;
    } else {
      bodyEnd = buffer.indexOf("\0", separator + 2);
      if (bodyEnd < 0) return;
    }
    const body = buffer.slice(separator + 2, bodyEnd);
    buffer = buffer.slice(bodyEnd + 1);
    handleFrame(command, body, headers);
  }
}

function handleFrame(command, body, headers = {}) {
  if (command === "CONNECTED") {
    console.log("WebSocket STOMP CONNECTED frame received");
    sendFrame("SUBSCRIBE", {
      id: "live-score-e2e",
      destination,
      ...(authToken ? { Authorization: `Bearer ${authToken}` } : {}),
      ack: "auto"
    });
    // Spring SimpleBroker has no SUBSCRIBE receipt. The subsequent score
    // assertions prove that the subscription is active.
    setTimeout(() => {
      if (failure || subscribed) return;
      subscribed = true;
      fs.writeFileSync(readyFile, "ready");
      console.log("WebSocket subscription sent; monitor ready for live score messages");
    }, Number(process.env.WS_SUBSCRIBE_SETTLE_MS || 500));
  } else if (command === "MESSAGE") {
    let payload;
    try {
      payload = JSON.parse(body);
    } catch {
      throw new Error(`WebSocket MESSAGE was not JSON: ${body}`);
    }
    frames.push(payload);
    console.log(`WebSocket update #${frames.length}: runs=${payload.runs} legalBalls=${payload.legalBalls} eventType=${payload.eventType ?? "n/a"} version=${payload.eventVersion ?? "n/a"}`);
  } else if (command === "ERROR") {
    throw new Error(`STOMP broker ERROR: ${JSON.stringify(headers)} ${body}`);
  }
}

socket.addEventListener("open", () => {
  console.log(`WebSocket transport opened: ${wsUrl}`);
  sendFrame("CONNECT", {
    "accept-version": "1.2",
    "heart-beat": "0,0",
    ...(authToken ? { Authorization: `Bearer ${authToken}` } : {})
  });
});
socket.addEventListener("message", async event => {
  try {
    const data = typeof event.data === "string" ? event.data : await event.data.text();
    buffer += data.replace(/\r\n/g, "\n");
    parseFrames();
  } catch (error) {
    failure = error;
    socket.close();
  }
});
socket.addEventListener("error", event => {
  failure = new Error(`WebSocket transport error${event?.message ? `: ${event.message}` : ""}`);
  console.error(failure.message);
});
socket.addEventListener("close", event => {
  if (!finished && !failure) {
    failure = new Error(`WebSocket closed before verification completed (code=${event.code}, reason=${event.reason || "none"})`);
  }
  if (failure) console.error(failure.message);
});

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const deadline = Date.now() + 45000;
while (Date.now() < deadline && !subscribed && !failure) await sleep(100);
if (!subscribed && !failure) failure = new Error("Timed out waiting for WebSocket SUBSCRIBE settle window");

if (!failure) {
  while (Date.now() < deadline && !fs.existsSync(doneFile) && !failure) await sleep(100);
  if (!fs.existsSync(doneFile) && !failure) failure = new Error("Timed out waiting for HTTP scoring E2E completion");
}
if (!failure) await sleep(1500);

let expected = [];
if (!failure) {
  try {
    expected = JSON.parse(fs.readFileSync(expectedFile, "utf8"));
    if (!Array.isArray(expected) || expected.length === 0) {
      throw new Error("Expected WebSocket sequence must be a non-empty array");
    }
  } catch (error) {
    failure = new Error(`Could not read expected WebSocket sequence: ${error.message}`);
  }
}
if (!failure && frames.length !== expected.length) {
  failure = new Error(`Expected exactly ${expected.length} authoritative WebSocket updates; received ${frames.length}. Duplicate or missed delivery events are not allowed.`);
}
if (!failure) {
  for (let index = 0; index < expected.length; index++) {
    const payload = frames[index];
    const { runs, legalBalls } = expected[index];
    if (payload.inningsId !== inningsId || Number(payload.runs) !== runs || Number(payload.legalBalls) !== legalBalls) {
      failure = new Error(`Update #${index + 1} mismatch: expected innings=${inningsId} score=${runs}/${legalBalls}, got innings=${payload.inningsId} score=${payload.runs}/${payload.legalBalls}`);
      break;
    }
  }
}
if (!failure) {
  const eventIds = frames.map(frame => frame.eventId);
  if (eventIds.some(eventId => typeof eventId !== "string" || !eventId)
      || new Set(eventIds).size !== eventIds.length) {
    failure = new Error("Every committed state transition must have a unique non-empty eventId");
  }
  const versions = frames.map(frame => Number(frame.eventVersion));
  if (!failure && versions.some(version => !Number.isFinite(version))) {
    failure = new Error("Every WebSocket snapshot must include a numeric eventVersion");
  } else if (!failure && versions.some((version, index) => index > 0 && version <= versions[index - 1])) {
    failure = new Error(`Authoritative eventVersion must increase strictly for every committed transition: ${versions.join(" -> ")}`);
  }
}

finished = true;
socket.close();
if (failure) {
  fs.writeFileSync(outputFile, JSON.stringify({ ok: false, error: failure.message, updates: frames }, null, 2));
  console.error(`FAIL: ${failure.message}`);
  process.exitCode = 1;
} else {
  fs.writeFileSync(outputFile, JSON.stringify({ ok: true, updates: frames }, null, 2));
  console.log("PASS: delivery and undo were broadcast once each; retry emitted no duplicate update");
  console.log("PASS: WebSocket snapshots match expected REST states and event versions increase monotonically");
}
