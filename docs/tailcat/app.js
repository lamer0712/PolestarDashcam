const params = new URLSearchParams(location.search);
const fileName = params.get("file") || "galleryplus-download.bin";
const carAddr = params.get("car") || "";
const canonicalDERPMapURL = "https://tailcat.dev/derpmap.json";
const checkpointBytes = 4 * 1024 * 1024;
const $ = (id) => document.getElementById(id);
const setStatus = (msg, err=false) => { const el=$("status"); el.textContent=msg; el.classList.toggle("err", err); };
const setProgress = (loaded, total) => {
  $("fill").style.width = total > 0 ? `${Math.min(100, Math.floor(100*loaded/total))}%` : "0%";
};

async function fetchWasm() {
  const gz = await fetch("main.wasm.gz", {cache:"force-cache"});
  if (!gz.ok) throw new Error(`fetching main.wasm.gz: ${gz.status}`);
  const size = Number(gz.headers.get("Content-Length")) || 0;
  let loaded = 0;
  const counted = gz.body.pipeThrough(new TransformStream({
    transform(chunk, controller) {
      loaded += chunk.byteLength;
      setStatus(size > 0 ? `Preparing… ${Math.min(100, Math.floor(100*loaded/size))}%` : "Preparing…");
      controller.enqueue(chunk);
    }
  })).pipeThrough(new DecompressionStream("gzip"));
  return new Response(counted, {headers:{"Content-Type":"application/wasm"}});
}

let wakeLock = null;
async function keepScreenAwake() {
  const button = $("wake");
  if (!("wakeLock" in navigator)) {
    button.hidden = true;
    return false;
  }
  if (wakeLock && !wakeLock.released) {
    button.hidden = true;
    return true;
  }
  try {
    wakeLock = await navigator.wakeLock.request("screen");
    button.hidden = true;
    wakeLock.addEventListener("release", () => {
      wakeLock = null;
      if (document.visibilityState === "visible" && !complete) button.hidden = false;
    }, {once:true});
    return true;
  } catch (_) {
    wakeLock = null;
    if (document.visibilityState === "visible") button.hidden = false;
    return false;
  }
}

$("wake").addEventListener("click", keepScreenAwake);

const ready = new Promise((resolve) => { globalThis.onTailcatReady = resolve; });
const go = new Go();
WebAssembly.instantiateStreaming(fetchWasm(), go.importObject)
  .then(({instance}) => go.run(instance))
  .catch(() => setStatus("Unable to prepare transfer.", true));
await ready;
await keepScreenAwake();

if (!navigator.storage?.getDirectory) {
  throw new Error("This browser cannot keep a resumable download. Update the browser and try again.");
}

const encoder = new TextEncoder();
const decoder = new TextDecoder();
const root = await navigator.storage.getDirectory();
const transferIdBytes = await crypto.subtle.digest("SHA-256", encoder.encode(`${carAddr}|${fileName}`));
const transferId = [...new Uint8Array(transferIdBytes)].slice(0, 12).map(v => v.toString(16).padStart(2, "0")).join("");
const receiverKeyStorage = `galleryplus-tailcat-receiver-key-${transferId}`;
const partialName = `galleryplus-${transferId}.partial`;
const metaKey = `galleryplus-transfer-${transferId}`;
const partialHandle = await root.getFileHandle(partialName, {create:true});

class ResumeStore {
  constructor(name) {
    this.name = name;
    this.worker = new Worker("storage-worker.js?v=1", {type:"module"});
    this.nextId = 1;
    this.pending = new Map();
    this.worker.onmessage = ({data}) => {
      const request = this.pending.get(data.id);
      if (!request) return;
      this.pending.delete(data.id);
      if (data.error) request.reject(new Error(data.error));
      else request.resolve(data.result);
    };
    this.ready = this.open();
  }
  call(command, values={}, transfers=[]) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      this.pending.set(id, {resolve, reject});
      this.worker.postMessage({id, command, ...values}, transfers);
    });
  }
  open() { return this.call("open", {name:this.name}); }
  size() { return this.call("size"); }
  truncate(size) { return this.call("truncate", {size}); }
  write(chunk, offset) {
    const bytes = chunk.slice().buffer;
    return this.call("write", {bytes, offset}, [bytes]);
  }
  flush() { return this.call("flush"); }
  close() { return this.call("close"); }
}

const store = new ResumeStore(partialName);
await store.ready;
let listener = null;
let activeConn = null;
let active = false;
let complete = false;
let registrationInFlight = false;
let lastDataAt = 0;

function savedMeta() {
  try { return JSON.parse(localStorage.getItem(metaKey) || "null"); }
  catch (_) { return null; }
}

function saveMeta(offset, total, done=false) {
  localStorage.setItem(metaKey, JSON.stringify({fileName, offset, total, complete:done, updatedAt:Date.now()}));
}

async function showCompleted(total) {
  const file = await partialHandle.getFile();
  if (file.size !== total) return false;
  const url = URL.createObjectURL(file);
  const a = $("download");
  a.href = url;
  a.download = fileName;
  $("done").textContent = `Received ${(total / (1<<20)).toFixed(1)} MB`;
  $("result").classList.remove("hidden");
  setStatus("Transfer complete.");
  setProgress(total, total);
  complete = true;
  if (wakeLock && !wakeLock.released) await wakeLock.release();
  $("wake").hidden = true;
  return true;
}

async function registerAddress() {
  if (complete || active || registrationInFlight || !listener || !carAddr) return;
  registrationInFlight = true;
  try {
    setStatus((await store.size()) > 0 ? "Reconnecting to resume…" : "Connecting to Gallery+…");
    const conn = await tailcatDial({addr:carAddr, port:2, derpMapURL:canonicalDERPMapURL, verbose:false});
    await conn.write(encoder.encode(listener.addr));
    await conn.closeWrite();
    while ((await conn.read()) !== null) {}
    conn.close();
    setStatus("Ready. Waiting for Gallery+…");
  } catch (_) {
    setStatus("Waiting to reconnect…");
  } finally {
    registrationInFlight = false;
  }
}

function joinBytes(left, right) {
  const joined = new Uint8Array(left.length + right.length);
  joined.set(left, 0);
  joined.set(right, left.length);
  return joined;
}

async function readLine(conn) {
  let buffered = new Uint8Array(0);
  while (true) {
    const newline = buffered.indexOf(10);
    if (newline >= 0) {
      return {line:decoder.decode(buffered.slice(0, newline)), rest:buffered.slice(newline + 1)};
    }
    if (buffered.length > 4096) throw new Error("Invalid transfer header");
    const chunk = await conn.read();
    if (chunk === null) throw new Error("Transfer ended before the file header");
    buffered = joinBytes(buffered, chunk);
  }
}

async function onConnection(conn) {
  if (active || complete) { conn.close(); return; }
  active = true;
  activeConn = conn;
  lastDataAt = Date.now();
  let received = await store.size();
  let total = savedMeta()?.total || 0;
  try {
    await keepScreenAwake();
    setStatus(received > 0 ? `Resuming from ${(received/(1<<20)).toFixed(1)} MB…` : "Receiving file…");
    await conn.write(encoder.encode(`GALLERYPLUS/1 RESUME ${received}\n`));
    const header = await readLine(conn);
    const match = /^GALLERYPLUS\/1 FILE (\d+) (\d+)$/.exec(header.line);
    if (!match) throw new Error("Unsupported sender response");
    total = Number(match[1]);
    received = Number(match[2]);
    if (!Number.isSafeInteger(total) || !Number.isSafeInteger(received) || received < 0 || received > total) {
      throw new Error("Invalid file size from sender");
    }

    await store.truncate(received);
    let checkpointAt = received;
    saveMeta(received, total, false);
    setProgress(received, total);

    const storeChunk = async (chunk) => {
      if (!chunk?.length) return;
      if (received + chunk.length > total) throw new Error("Sender exceeded the announced file size");
      await store.write(chunk, received);
      received += chunk.length;
      lastDataAt = Date.now();
      setProgress(received, total);
      setStatus(`Receiving… ${(100 * received / total).toFixed(1)}%`);
      if (received - checkpointAt >= checkpointBytes && received < total) {
        await store.flush();
        saveMeta(received, total, false);
        checkpointAt = received;
      }
    };

    await storeChunk(header.rest);
    for (let chunk; (chunk = await conn.read()) !== null; ) await storeChunk(chunk);
    if (received !== total) throw new Error(`Transfer stopped at ${received} of ${total} bytes`);
    await store.flush();
    await store.close();
    saveMeta(received, total, true);
    await conn.write(encoder.encode(`GALLERYPLUS/1 OK ${total}\n`));
    await conn.closeWrite();
    conn.close();
    await showCompleted(total);
  } catch (_) {
    try { await store.flush(); } catch (_) {}
    try {
      const committed = await store.size();
      saveMeta(committed, total, false);
      setProgress(committed, total);
      setStatus(`Paused at ${(committed/(1<<20)).toFixed(1)} MB. Reconnecting…`);
    } catch (_) {
      setStatus("Transfer paused. Reconnecting…");
    }
    conn.close();
  } finally {
    activeConn = null;
    active = false;
    if (!complete) setTimeout(registerAddress, 1000);
  }
}

async function start() {
  const meta = savedMeta();
  if (meta?.complete) {
    await store.close();
    if (await showCompleted(meta.total)) return;
    await store.open();
  }
  const privateKey = localStorage.getItem(receiverKeyStorage) || "";
  listener = await tailcatListen({derpMapURL:canonicalDERPMapURL, privateKey, verbose:false, onConnection});
  localStorage.setItem(receiverKeyStorage, listener.privateKeyJSON);
  await registerAddress();
}

document.addEventListener("visibilitychange", async () => {
  if (document.visibilityState !== "visible") return;
  await keepScreenAwake();
  if (active && Date.now() - lastDataAt > 45_000) activeConn?.close();
  if (!active && !complete) registerAddress();
});

setInterval(() => {
  if (document.visibilityState !== "visible" || complete) return;
  if (active && Date.now() - lastDataAt > 45_000) activeConn?.close();
  else if (!active) registerAddress();
}, 8000);

start().catch((e) => setStatus(e.message || "Unable to start transfer. Please try again.", true));
