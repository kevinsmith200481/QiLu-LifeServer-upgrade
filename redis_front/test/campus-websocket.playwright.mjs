import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright-core";

const currentDirectory = path.dirname(fileURLToPath(import.meta.url));
const managerScript = path.resolve(currentDirectory, "../html/campus/js/campus-websocket.js");
const outputPath = process.argv[2] ? path.resolve(process.argv[2]) : null;
const browserCandidates = [
  process.env.PLAYWRIGHT_CHROME_PATH,
  "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
  "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe"
].filter(Boolean);
const executablePath = browserCandidates.find(candidate => fs.existsSync(candidate));

if (!executablePath) {
  throw new Error("Chrome or Edge executable was not found; set PLAYWRIGHT_CHROME_PATH.");
}

const results = [];
let browser;

async function record(name, action) {
  const startedAt = Date.now();
  try {
    await action();
    results.push({ name, passed: true, durationMs: Date.now() - startedAt });
  } catch (error) {
    results.push({ name, passed: false, durationMs: Date.now() - startedAt, error: error.message });
  }
}

async function createFixture() {
  const page = await browser.newPage();
  await page.setContent("<!doctype html><html><body><main id='fixture'></main></body></html>");
  await page.addScriptTag({ path: managerScript });
  await page.evaluate(() => {
    class BrowserFakeWebSocket {
      constructor(url) {
        this.url = url;
        this.readyState = 0;
        this.sent = [];
        window.__sockets.push(this);
      }

      open() {
        this.readyState = 1;
        if (this.onopen) this.onopen();
      }

      send(payload) {
        if (this.readyState !== 1) throw new Error("socket is not open");
        this.sent.push(payload);
        const message = JSON.parse(payload);
        if (this.autoPong && message.type === "PING") {
          // Reply inside the browser event loop so Playwright transport latency cannot create a false timeout.
          queueMicrotask(() => this.receive({ type: "PONG", sentAt: message.sentAt, serverAt: Date.now() }));
        }
      }

      receive(payload) {
        if (this.onmessage) this.onmessage({ data: JSON.stringify(payload) });
      }

      serverClose(code = 1006) {
        this.readyState = 3;
        if (this.onclose) this.onclose({ code });
      }

      close(code = 1000, reason = "") {
        this.closeCode = code;
        this.closeReason = reason;
        this.serverClose(code);
      }
    }

    window.__sockets = [];
    window.__notifications = 0;
    window.__unreadRefreshes = 0;
    window.__listRefreshes = 0;
    window.__manager = new CampusWebSocket.CampusWebSocketManager({
      WebSocketImpl: BrowserFakeWebSocket,
      urlFactory: () => "ws://fixture/ws/inbox?token=browser-test",
      reconnectDelays: [50, 100, 200, 400, 800, 1000],
      heartbeatIntervalMs: 100,
      pongTimeoutMs: 80,
      random: () => 0,
      onMessage: () => {
        window.__notifications += 1;
        window.__unreadRefreshes += 1;
        window.__listRefreshes += 1;
      }
    });
  });
  return page;
}

try {
  browser = await chromium.launch({ executablePath, headless: true });

  await record("stable-three-pong", async () => {
    const page = await createFixture();
    await page.evaluate(() => {
      window.__manager.start();
      window.__sockets[0].autoPong = true;
      window.__sockets[0].open();
    });
    await page.waitForFunction(() => window.__sockets[0].sent.length >= 3);
    const diagnostics = await page.evaluate(() => window.__manager.getDiagnostics());
    assert.equal(diagnostics.state, "OPEN");
    assert.equal(diagnostics.connectionAttempts, 1);
    assert.equal(diagnostics.socketCount, 1);
    await page.close();
  });

  await record("three-disconnects-single-resource", async () => {
    const page = await createFixture();
    await page.evaluate(() => window.__manager.start());
    for (let index = 0; index < 3; index += 1) {
      await page.evaluate(socketIndex => {
        const socket = window.__sockets[socketIndex];
        socket.open();
        socket.receive({ type: "PONG", sentAt: Date.now(), serverAt: Date.now() });
        socket.serverClose(1006);
      }, index);
      await page.waitForFunction(count => window.__sockets.length === count, index + 2);
    }
    await page.evaluate(() => window.__sockets[3].open());
    const diagnostics = await page.evaluate(() => window.__manager.getDiagnostics());
    assert.deepEqual(
      [diagnostics.socketCount, diagnostics.reconnectTimerCount, diagnostics.heartbeatTimerCount, diagnostics.pongTimeoutCount],
      [1, 0, 1, 1]
    );
    await page.close();
  });

  await record("offline-online-and-visibility-recovery", async () => {
    const page = await createFixture();
    await page.evaluate(() => {
      window.__manager.start();
      window.__sockets[0].open();
      window.dispatchEvent(new Event("offline"));
    });
    const attemptsOffline = await page.evaluate(() => window.__manager.connectionAttempts);
    await page.waitForTimeout(150);
    assert.equal(await page.evaluate(() => window.__manager.connectionAttempts), attemptsOffline);

    await page.evaluate(() => window.dispatchEvent(new Event("online")));
    await page.waitForFunction(expected => window.__manager.connectionAttempts === expected, attemptsOffline + 1);
    await page.evaluate(() => {
      window.__sockets[1].serverClose(1006);
      Object.defineProperty(document, "visibilityState", { configurable: true, value: "visible" });
      document.dispatchEvent(new Event("visibilitychange"));
    });
    await page.waitForFunction(() => window.__sockets.length === 3);
    assert.equal(await page.evaluate(() => window.__manager.getDiagnostics().socketCount), 1);
    await page.close();
  });

  await record("logout-destroy-auth-failed-stop-reconnect", async () => {
    for (const mode of ["logout", "destroy", "auth"]) {
      const page = await createFixture();
      await page.evaluate(currentMode => {
        window.__manager.start();
        window.__sockets[0].open();
        if (currentMode === "logout") window.__manager.close("logout");
        if (currentMode === "destroy") window.__manager.destroy();
        if (currentMode === "auth") window.__sockets[0].receive({ type: "AUTH_FAILED" });
      }, mode);
      await page.waitForTimeout(200);
      assert.equal(await page.evaluate(() => window.__manager.connectionAttempts), 1);
      await page.close();
    }
  });

  await record("reconnect-push-deduplication", async () => {
    const page = await createFixture();
    await page.evaluate(() => {
      window.__manager.start();
      window.__sockets[0].open();
      window.__sockets[0].receive({ messageId: "7001", title: "test notice" });
      window.__sockets[0].serverClose(1006);
    });
    await page.waitForFunction(() => window.__sockets.length === 2);
    const counters = await page.evaluate(() => {
      window.__sockets[1].open();
      window.__sockets[1].receive({ messageId: "7001", title: "test notice" });
      return [window.__notifications, window.__unreadRefreshes, window.__listRefreshes];
    });
    assert.deepEqual(counters, [1, 1, 1]);
    await page.close();
  });
} finally {
  if (browser) await browser.close();
}

const report = {
  schemaVersion: 1,
  browserExecutable: executablePath,
  passed: results.length === 5 && results.every(result => result.passed),
  results
};

if (outputPath) {
  fs.mkdirSync(path.dirname(outputPath), { recursive: true });
  fs.writeFileSync(outputPath, JSON.stringify(report, null, 2) + "\n", "utf8");
}
process.stdout.write(JSON.stringify(report, null, 2) + "\n");
if (!report.passed) process.exitCode = 1;
