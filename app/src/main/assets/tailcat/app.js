const CHUNK = 64 * 1024;
const params = new URLSearchParams(location.search);
const fileName = params.get("file") || "galleryplus-download.bin";
const canonicalDERPMapURL = "https://tailcat.dev/derpmap.json";
const $ = (id) => document.getElementById(id);
const setStatus = (msg, err=false) => { const el=$("status"); el.textContent=msg; el.classList.toggle("err", err); };
const setProgress = (loaded, total) => { if (total > 0) $("fill").style.width = `${Math.min(100, Math.floor(100*loaded/total))}%`; };

async function fetchWasm() {
  const wasm = await fetch("main.wasm", {cache:"force-cache"});
  if (!wasm.ok) throw new Error(`fetching main.wasm: ${wasm.status}`);
  const size = Number(wasm.headers.get("Content-Length")) || 0;
  let loaded = 0;
  const counted = wasm.body.pipeThrough(new TransformStream({
    transform(chunk, controller) {
      loaded += chunk.byteLength;
      setStatus(size > 0 ? `Loading Tailcat… ${Math.min(100, Math.floor(100*loaded/size))}%` : "Loading Tailcat…");
      controller.enqueue(chunk);
    }
  }));
  return new Response(counted, {headers:{"Content-Type":"application/wasm"}});
}

const ready = new Promise((resolve) => { globalThis.onTailcatReady = resolve; });
const go = new Go();
WebAssembly.instantiateStreaming(fetchWasm(), go.importObject).then(({instance}) => go.run(instance)).catch(e => setStatus(String(e), true));
await ready;
setStatus("Starting secure receiver…");

async function registerAddress(addr) {
  const res = await fetch("/tailcat-register", {method:"POST", headers:{"Content-Type":"text/plain"}, body:addr});
  if (!res.ok) throw new Error(`register failed: ${res.status}`);
}

async function start() {
  const ln = await tailcatListen({derpMapURL: canonicalDERPMapURL, privateKey:"", verbose:false, onConnection});
  $("addr").textContent = ln.addr;
  try {
    await registerAddress(ln.addr);
    setStatus("Connected. Waiting for Gallery+ to send the file…");
  } catch (e) {
    setStatus("Receiver is ready, but Gallery+ did not confirm registration.", true);
    $("fallback").classList.remove("hidden");
    $("copy").onclick = () => navigator.clipboard.writeText(ln.addr);
  }
}

async function onConnection(conn) {
  setStatus("Receiving file…");
  const chunks = [];
  let n = 0;
  try {
    for (let chunk; (chunk = await conn.read()) !== null; ) {
      chunks.push(chunk);
      n += chunk.length;
      setProgress(n, 0);
      setStatus(`Receiving file… ${(n / (1<<20)).toFixed(1)} MB`);
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
  } catch (e) {
    conn.close();
    setStatus(`Receive failed: ${e.message || e}`, true);
  }
}

start().catch(e => setStatus(`Tailcat failed: ${e.message || e}`, true));
