// Jarvis voice: runs a Piper text-to-speech model inside the browser.
// The model is downloaded once, then cached by the browser.
//
// Model lookup order (first one that works wins):
//   1. /voice/jarvis.onnx + /voice/jarvis.onnx.json  (files you host in this repo, see README)
//   2. Hugging Face: jgkawell/jarvis (community Jarvis voice, tried at a few likely paths)

const ORT_BASE = "https://cdn.jsdelivr.net/npm/onnxruntime-web@1.18.0/dist/";
const PHONEMIZE_BASE = "https://cdn.jsdelivr.net/npm/@diffusionstudio/piper-wasm@1.0.0/build/piper_phonemize";
const HF = "https://huggingface.co/jgkawell/jarvis/resolve/main";
const CACHE_NAME = "jarvis-voice-v1";

const CANDIDATES = [
  { onnx: "/voice/jarvis.onnx", json: "/voice/jarvis.onnx.json" },
  { onnx: `${HF}/en/en_GB/jarvis/high/jarvis-high.onnx`, json: `${HF}/en/en_GB/jarvis/high/jarvis-high.onnx.json` },
  { onnx: `${HF}/jarvis-high.onnx`, json: `${HF}/jarvis-high.onnx.json` },
  { onnx: `${HF}/en/en_GB/jarvis/medium/jarvis-medium.onnx`, json: `${HF}/en/en_GB/jarvis/medium/jarvis-medium.onnx.json` },
  { onnx: `${HF}/jarvis-medium.onnx`, json: `${HF}/jarvis-medium.onnx.json` },
];

function loadScript(src) {
  return new Promise((resolve, reject) => {
    const s = document.createElement("script");
    s.src = src;
    s.onload = resolve;
    s.onerror = () => reject(new Error("Could not load " + src));
    document.head.appendChild(s);
  });
}

// Fetch with a cache and a progress callback. Returns an ArrayBuffer.
async function cachedFetch(url, onProgress) {
  let cache = null;
  try { cache = await caches.open(CACHE_NAME); } catch {}
  if (cache) {
    const hit = await cache.match(url);
    if (hit) return hit.arrayBuffer();
  }
  const res = await fetch(url);
  if (!res.ok) throw new Error(`${res.status} for ${url}`);
  const total = +(res.headers.get("Content-Length") || 0);
  const reader = res.body.getReader();
  const chunks = [];
  let loaded = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    chunks.push(value);
    loaded += value.length;
    if (onProgress && total) onProgress(loaded / total);
  }
  const blob = new Blob(chunks);
  if (cache) { try { await cache.put(url, new Response(blob.slice())); } catch {} }
  return blob.arrayBuffer();
}

export class JarvisVoice {
  constructor() {
    this.ready = false;
    this.ctx = null;
    this.analyser = null;
    this.session = null;
    this.config = null;
    this.phonemizer = null;
    this.runId = 0;
    this.sources = [];
    this.buf = null;
  }

  // Call from a user gesture (a click) so the browser allows audio.
  init() {
    if (this.ctx) return;
    const AC = window.AudioContext || window.webkitAudioContext;
    this.ctx = new AC();
    this.analyser = this.ctx.createAnalyser();
    this.analyser.fftSize = 512;
    this.analyser.connect(this.ctx.destination);
    this.buf = new Uint8Array(this.analyser.fftSize);
    if (this.ctx.state === "suspended") this.ctx.resume();
  }

  // Returns true if the voice model loaded, false if we should fall back.
  async load(onProgress) {
    try {
      await loadScript(ORT_BASE + "ort.min.js");
      const ort = window.ort;
      ort.env.wasm.wasmPaths = ORT_BASE;
      ort.env.wasm.numThreads = self.crossOriginIsolated ? Math.min(4, navigator.hardwareConcurrency || 2) : 1;

      let lastError = null;
      const ORDER = (typeof window !== "undefined" && window.AndroidJarvis) ? [...CANDIDATES.slice(2), ...CANDIDATES.slice(0, 2)] : CANDIDATES;
      for (const c of ORDER) {
        try {
          const cfgBuf = await cachedFetch(c.json);
          const config = JSON.parse(new TextDecoder().decode(cfgBuf));
          const model = await cachedFetch(c.onnx, onProgress);
          this.session = await ort.InferenceSession.create(model, { executionProviders: ["wasm"] });
          this.config = config;
          break;
        } catch (e) {
          lastError = e;
        }
      }
      if (!this.session) throw lastError || new Error("No voice model found");

      const mod = await import("/vendor/piper-phonemize-module.js");
      this.phonemizer = mod.createPiperPhonemize;
      this.ready = true;
      return true;
    } catch (e) {
      console.warn("[jarvis voice] falling back to browser voice:", e);
      this.ready = false;
      return false;
    }
  }

  level() {
    if (!this.analyser) return 0;
    this.analyser.getByteTimeDomainData(this.buf);
    let sum = 0;
    for (let i = 0; i < this.buf.length; i++) {
      const v = (this.buf[i] - 128) / 128;
      sum += v * v;
    }
    return Math.min(1, Math.sqrt(sum / this.buf.length) * 3);
  }

  stop() {
    this.runId++;
    for (const s of this.sources) { try { s.stop(); } catch {} }
    this.sources = [];
  }

  async phonemeIds(text) {
    const input = JSON.stringify([{ text }]);
    return new Promise(async (resolve, reject) => {
      try {
        const m = await this.phonemizer({
          print: (line) => resolve(JSON.parse(line).phoneme_ids),
          printErr: (line) => reject(new Error(line)),
          locateFile: (f) =>
            f.endsWith(".wasm") ? `${PHONEMIZE_BASE}.wasm` : f.endsWith(".data") ? `${PHONEMIZE_BASE}.data` : f,
        });
        m.callMain(["-l", this.config.espeak.voice, "--input", input, "--espeak_data", "/espeak-ng-data"]);
      } catch (e) {
        reject(e);
      }
    });
  }

  async synth(text) {
    const ort = window.ort;
    const ids = await this.phonemeIds(text);
    const inf = this.config.inference || {};
    const feeds = {
      input: new ort.Tensor("int64", BigInt64Array.from(ids, (n) => BigInt(n)), [1, ids.length]),
      input_lengths: new ort.Tensor("int64", BigInt64Array.from([BigInt(ids.length)]), [1]),
      scales: new ort.Tensor("float32", Float32Array.from([
        inf.noise_scale ?? 0.667,
        (inf.length_scale ?? 1) * 1.05,   // a touch slower, more butler-like
        inf.noise_w ?? 0.8,
      ]), [3]),
    };
    if (Object.keys(this.config.speaker_id_map || {}).length || (this.config.num_speakers || 1) > 1) {
      feeds.sid = new ort.Tensor("int64", BigInt64Array.from([0n]), [1]);
    }
    const out = await this.session.run(feeds);
    return out.output ? out.output.data : Object.values(out)[0].data;
  }

  static clean(text) {
    return text
      .replace(/https?:\/\/\S+/g, "")
      .replace(/[*_`#>]/g, "")
      .replace(/\s+/g, " ")
      .trim();
  }

  // Speaks text sentence by sentence so the first words start quickly.
  // Resolves when the speech has finished (or was stopped).
  async speak(text, { onStart } = {}) {
    if (!this.ready) throw new Error("voice not ready");
    this.init();
    if (this.ctx.state === "suspended") await this.ctx.resume();
    const myRun = ++this.runId;
    const sentences = (JarvisVoice.clean(text).match(/[^.!?]+[.!?]*\s*/g) || []).map((s) => s.trim()).filter(Boolean);
    const rate = this.config.audio.sample_rate;
    let when = 0, started = false, lastSource = null;

    for (const sentence of sentences) {
      if (myRun !== this.runId) return;
      const samples = await this.synth(sentence);
      if (myRun !== this.runId) return;
      const buffer = this.ctx.createBuffer(1, samples.length, rate);
      buffer.copyToChannel(samples, 0);
      const src = this.ctx.createBufferSource();
      src.buffer = buffer;
      src.connect(this.analyser);
      when = Math.max(when, this.ctx.currentTime + 0.05);
      src.start(when);
      when += buffer.duration + 0.12;
      this.sources.push(src);
      lastSource = src;
      if (!started) { started = true; if (onStart) onStart(); }
    }
    if (!lastSource) return;
    await new Promise((resolve) => { lastSource.onended = resolve; });
  }
}
