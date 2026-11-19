// Jarvis brain: receives the conversation, asks Claude, returns a short spoken-style reply.
// Env vars (set in Netlify): ANTHROPIC_API_KEY (required), JARVIS_PASSCODE (recommended),
// JARVIS_MODEL (optional), USER_NAME (optional, default "sir").

const MODEL = process.env.JARVIS_MODEL || "claude-haiku-5-5";
const MAX_HISTORY = 8;

function persona() {
  const name = process.env.USER_NAME || "sir";
  return `You are J.A.R.V.I.S., a calm, dry-witted British AI butler, as in the Iron Man films. Replies are spoken aloud: one to three short sentences, plain speech, no markdown, lists or emojis. UK spelling, no em dashes. Address the user as "${name}" now and then. Be warm, quietly confident, occasionally wry, never grovelling. If unsure, say so briefly.`;
}

const json = (status, body) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });

export default async (req) => {
  // A cheap GET lets the page warm this function up before the first question.
  if (req.method === "GET") return new Response("ready", { status: 200 });
  if (req.method !== "POST") return json(405, { error: "POST only" });

  const pass = process.env.JARVIS_PASSCODE;
  if (pass && req.headers.get("x-jarvis-pass") !== pass) {
    return json(401, { error: "Passcode required" });
  }

  const key = process.env.ANTHROPIC_API_KEY;
  if (!key) return json(500, { error: "ANTHROPIC_API_KEY is not set" });

  let body;
  try {
    body = await req.json();
  } catch {
    return json(400, { error: "Bad JSON" });
  }

  const messages = (Array.isArray(body.messages) ? body.messages : [])
    .filter((m) => m && (m.role === "user" || m.role === "assistant") && typeof m.content === "string")
    .slice(-MAX_HISTORY)
    .map((m) => ({ role: m.role, content: m.content.slice(0, 2000) }));

  // The API needs the first message to be from the user.
  while (messages.length && messages[0].role !== "user") messages.shift();
  if (!messages.length) return json(400, { error: "No user message" });

  const wantStream = body.stream === true;
  const res = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-api-key": key,
      "anthropic-version": "2023-06-01",
    },
    body: JSON.stringify({
      model: MODEL,
      max_tokens: 220,
      system: persona(),
      messages,
      ...(wantStream ? { stream: true } : {}),
    }),
  });

  if (!res.ok) {
    const detail = await res.text();
    return json(502, { error: "Claude request failed", detail: detail.slice(0, 300) });
  }

  if (wantStream) {
    // Turn Anthropic's event stream into plain text chunks the page can speak as they arrive.
    const dec = new TextDecoder();
    const enc = new TextEncoder();
    let buf = "";
    const out = res.body.pipeThrough(
      new TransformStream({
        transform(chunk, controller) {
          buf += dec.decode(chunk, { stream: true });
          let i;
          while ((i = buf.indexOf("\n")) >= 0) {
            const line = buf.slice(0, i).trim();
            buf = buf.slice(i + 1);
            if (!line.startsWith("data:")) continue;
            try {
              const ev = JSON.parse(line.slice(5));
              if (ev.type === "error") console.error("Anthropic stream error", JSON.stringify(ev));
              if (ev.type === "message_delta" && ev.delta && ev.delta.stop_reason && ev.delta.stop_reason !== "end_turn") {
                console.warn("Stream stop_reason", ev.delta.stop_reason);
              }
              if (ev.type === "content_block_delta" && ev.delta && ev.delta.type === "text_delta") {
                controller.enqueue(enc.encode(ev.delta.text));
              }
            } catch {}
          }
        },
      })
    );
    return new Response(out, {
      status: 200,
      headers: { "content-type": "text/plain; charset=utf-8", "cache-control": "no-cache" },
    });
  }

  const data = await res.json();
  const text = (data.content || [])
    .filter((b) => b.type === "text")
    .map((b) => b.text)
    .join(" ")
    .trim();

  return json(200, { reply: text || "My apologies, I seem to have lost my train of thought." });
};

export const config = { path: "/api/chat" };
