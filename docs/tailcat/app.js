const params = new URLSearchParams(location.search);
const fileName = params.get("file") || "galleryplus-download.bin";
const carAddr = params.get("car") || "";
const canonicalDERPMapURL = "https://tailcat.dev/derpmap.json";
const $ = (id) => document.getElementById(id);
const setStatus = (msg, err=false) => { const el=$("status"); el.textContent=msg; el.classList.toggle("err", err); };
const setProgress = (loaded, total) => { if (total > 0) $("fill").style.width = `${Math.min(100, Math.floor(100*loaded/total))}%`; };

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
  try {
    if ("wakeLock" in navigator) {
      wakeLock = await navigator.wakeLock.request("screen");
    }
  } catch (_) {
    wakeLock = null;
  }
}

document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible") keepScreenAwake();
});

const ready = new Promise((resolve) => { globalThis.onTailcatReady = resolve; });
const go = new Go();
WebAssembly.instantiateStreaming(fetchWasm(), go.importObject).then(({instance}) => go.run(instance)).catch(e => setStatus("Unable to prepare transfer.", true));
await ready;
await keepScreenAwake();
setStatus("Opening receiver…");

async function registerAddress(addr) {
  if (!carAddr) throw new Error("missing vehicle address");
  const conn = await tailcatDial({addr: carAddr, port: 2, derpMapURL: canonicalDERPMapURL, verbose:false});
  await conn.write(new TextEncoder().encode(addr));
  await conn.closeWrite();
  while ((await conn.read()) !== null) {}
  conn.close();
}

async function start() {
  const ln = await tailcatListen({derpMapURL: canonicalDERPMapURL, privateKey:"", verbose:false, onConnection});
  try {
    await registerAddress(ln.addr);
    setStatus("Ready. Waiting for Gallery+…");
  } catch (_) {
    setStatus("Connection was not ready. Close this page and scan the QR again.", true);
  }
}

async function onConnection(conn) {
  await keepScreenAwake();
  setStatus("Receiving file…");
  const chunks = [];
  let n = 0;
  try {
    for (let chunk; (chunk = await conn.read()) !== null; ) {
      chunks.push(chunk);
      n += chunk.length;
      setStatus(`Receiving… ${(n / (1<<20)).toFixed(1)} MB`);
    }
    conn.close();
    const blob = new Blob(chunks, {type:"application/octet-stream"});
    const url = URL.createObjectURL(blob);
    const a = $("download");
    a.href = url;
    a.download = fileName;
    $("done").textContent = `Received ${(n / (1<<20)).toFixed(1)} MB`;
    $("result").classList.remove("hidden");
    setStatus("Transfer complete.");
    setProgress(1,1);
  } catch (_) {
    conn.close();
    setStatus("Receive failed. Please try again.", true);
  }
}

start().catch(() => setStatus("Unable to start transfer. Please try again.", true));
