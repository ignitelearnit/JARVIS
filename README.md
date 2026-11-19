# JARVIS (browser edition)

A holographic-style HUD you can talk to. Say "Jarvis" and ask anything, and Claude answers out loud in a dry British butler voice.

- `public/index.html`: the HUD, wake word, speech in and out (all in the browser)
- `netlify/functions/chat.mjs`: the brain, a small function that calls Claude with your API key (the key never reaches the browser)

## Deploy on Netlify

1. Push this folder to your GitHub repo.
2. In Netlify: Add new site, Import from Git, pick the repo. No build command is needed. Publish directory is `public` (already set in `netlify.toml`).
3. Site settings, Environment variables, add:
   - `ANTHROPIC_API_KEY`: your key from console.anthropic.com (required)
   - `JARVIS_PASSCODE`: any passphrase, so strangers who find the URL cannot spend your credit (strongly recommended)
   - `USER_NAME`: what Jarvis calls you (optional, default "sir")
   - `JARVIS_MODEL`: optional, default `claude-sonnet-5-5`
4. Redeploy, open the site in Chrome or Edge, enter your passcode, tap Initialise and allow the microphone.

Optional: point a subdomain such as `jarvis.yourdomain.com` at the Netlify site from your DNS settings.

## Using it

- Say "Jarvis, what's the weather like for a walk?" in one go, or say "Jarvis" and wait for "Yes?".
- After each reply Jarvis stays awake for 20 seconds, so you can follow up without repeating the wake word.
- You can also type in the box at the bottom.
- The Mic button turns always-listening on and off.

## The Jarvis voice

Jarvis speaks with a community-made Piper voice model of the film Jarvis (`jgkawell/jarvis` on Hugging Face), running entirely in your browser. The first start downloads about 80MB (the voice plus a small phonemiser), then it is cached.

- By default the page tries to fetch the model from Hugging Face. If that fails it falls back to your browser's built-in voice.
- To host the voice yourself (more reliable), download the `.onnx` file and its matching `.onnx.json` from the Hugging Face repo, rename them to `jarvis.onnx` and `jarvis.onnx.json`, and put them in `public/voice/`. The page checks there first.
- The model is a fan-made imitation of the actor's voice from the films. Keep this project private and passcode-protected, and do not use it commercially.

## Limits to know about

- Wake word listening uses the browser's built-in speech recognition. It works in Chrome and Edge, needs the tab open, and will not run in the background on a locked phone. That is what the Android app step is for.
- Jarvis does not share your Claude app memory or chat history. Put anything you want him to always know into the persona in `netlify/functions/chat.mjs`.
- Replies are capped at 300 tokens and the last 12 messages are sent each time, which keeps cost low.

## Android app (say "Jarvis" with the phone in your pocket)

The `android/` folder is a small app that wraps this site, listens for "Jarvis" in the background (Picovoice Porcupine, runs on the phone), listens to your request with Android's own speech recognition, and hands it to the page, which asks Claude and answers in the Jarvis voice.

1. Make a free account at console.picovoice.ai and copy your AccessKey.
2. In GitHub, open the repo's Actions tab, choose "Build Android APK", click "Run workflow", then download the `jarvis-apk` file from the finished run and unzip it.
3. Copy `app-debug.apk` to the phone and install it (allow "install unknown apps" when asked).
4. Open the app, allow the microphone and notifications, switch on "Display over other apps", then paste your Picovoice AccessKey and Jarvis site passcode into the settings box. Long-press the screen later to reopen settings.
5. Say "Jarvis". He should pop up, say "Yes?", listen, answer, then listen for a follow-up and step aside after a few seconds of silence.

Known limits: it needs a restart of the app after a phone reboot, and some phone makers (Xiaomi, Samsung, OnePlus) kill background apps unless you set battery use for Jarvis to "Unrestricted".

## Next steps

1. Start the Android app automatically after a reboot.
2. Connect tools (calendar, notes) so Jarvis can act, not just talk.
3. Mac menu bar wrapper reusing this page.
