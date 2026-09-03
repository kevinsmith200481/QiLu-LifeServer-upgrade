const test = require("node:test");
const assert = require("node:assert/strict");
const { CampusWebSocketManager, State } = require("../html/campus/js/campus-websocket.js");

class FakeClock {
  constructor() {
    this.now = 0;
    this.nextId = 1;
    this.tasks = new Map();
  }

  setTimeout(callback, delay) {
    return this.addTask(callback, delay, 0);
  }

  setInterval(callback, delay) {
    return this.addTask(callback, delay, delay);
  }

  addTask(callback, delay, interval) {
    const id = this.nextId++;
    this.tasks.set(id, { callback, time: this.now + delay, interval });
    return id;
  }

  clear(id) {
    this.tasks.delete(id);
  }

  tick(duration) {
    const target = this.now + duration;
    while (true) {
      const due = [...this.tasks.entries()]
        .filter(([, task]) => task.time <= target)
        .sort((left, right) => left[1].time - right[1].time || left[0] - right[0])[0];
      if (!due) break;
      const [id, task] = due;
      this.now = task.time;
      if (task.interval === 0) {
        this.tasks.delete(id);
      }
      task.callback();
      if (task.interval > 0 && this.tasks.has(id)) {
        task.time += task.interval;
      }
    }
    this.now = target;
  }
}

class FakeEventTarget {
  constructor() {
    this.listeners = new Map();
  }

  addEventListener(type, listener) {
    const listeners = this.listeners.get(type) || new Set();
    listeners.add(listener);
    this.listeners.set(type, listeners);
  }

  removeEventListener(type, listener) {
    const listeners = this.listeners.get(type);
    if (listeners) listeners.delete(listener);
  }

  dispatch(type) {
    const listeners = this.listeners.get(type) || [];
    for (const listener of listeners) listener({ type });
  }
}

class FakeWebSocket {
  static instances = [];

  static reset() {
    FakeWebSocket.instances = [];
  }

  constructor(url) {
    this.url = url;
    this.readyState = 0;
    this.sent = [];
    FakeWebSocket.instances.push(this);
  }

  open() {
    this.readyState = 1;
    this.onopen && this.onopen();
  }

  send(payload) {
    if (this.readyState !== 1) throw new Error("socket is not open");
    this.sent.push(payload);
  }

  receive(payload) {
    this.onmessage && this.onmessage({ data: JSON.stringify(payload) });
  }

  serverClose(code = 1006) {
    this.readyState = 3;
    this.onclose && this.onclose({ code });
  }

  close(code = 1000, reason = "") {
    this.closeCode = code;
    this.closeReason = reason;
    this.serverClose(code);
  }
}

function createManager(overrides = {}) {
  FakeWebSocket.reset();
  const clock = new FakeClock();
  const windowRef = new FakeEventTarget();
  windowRef.navigator = { onLine: true };
  const documentRef = new FakeEventTarget();
  documentRef.visibilityState = "visible";
  const received = [];
  const manager = new CampusWebSocketManager({
    WebSocketImpl: FakeWebSocket,
    windowRef,
    documentRef,
    urlFactory: () => "ws://localhost/ws/inbox?token=test",
    onMessage: (raw, payload) => received.push(payload),
    random: () => 0,
    setTimeoutFn: (callback, delay) => clock.setTimeout(callback, delay),
    clearTimeoutFn: id => clock.clear(id),
    setIntervalFn: (callback, delay) => clock.setInterval(callback, delay),
    clearIntervalFn: id => clock.clear(id),
    ...overrides
  });
  return { manager, clock, windowRef, documentRef, received };
}

test("stable connection receives three PONG frames without rebuilding the socket", () => {
  const { manager, clock } = createManager();
  manager.start();
  const socket = FakeWebSocket.instances[0];
  socket.open();

  for (let index = 0; index < 3; index += 1) {
    const ping = JSON.parse(socket.sent[index]);
    assert.equal(ping.type, "PING");
    socket.receive({ type: "PONG", sentAt: ping.sentAt, serverAt: clock.now });
    if (index < 2) clock.tick(20000);
  }

  assert.equal(FakeWebSocket.instances.length, 1);
  assert.deepEqual(manager.getDiagnostics(), {
    state: State.OPEN,
    connectionAttempts: 1,
    socketCount: 1,
    reconnectTimerCount: 0,
    heartbeatTimerCount: 1,
    pongTimeoutCount: 0
  });
});

test("missing PONG closes the dead socket and starts the fixed reconnect backoff", () => {
  const { manager, clock } = createManager();
  manager.start();
  FakeWebSocket.instances[0].open();

  clock.tick(10000);
  assert.equal(manager.state, State.RECONNECT_WAIT);
  assert.equal(manager.getDiagnostics().reconnectTimerCount, 1);
  clock.tick(1000);
  assert.equal(FakeWebSocket.instances.length, 2);

  FakeWebSocket.instances[1].serverClose();
  clock.tick(1999);
  assert.equal(FakeWebSocket.instances.length, 2);
  clock.tick(1);
  assert.equal(FakeWebSocket.instances.length, 3);
});

test("transport errors use a browser-valid close code and keep one reconnect timer", () => {
  const { manager } = createManager();
  manager.start();
  const socket = FakeWebSocket.instances[0];
  socket.open();

  socket.onerror();

  assert.equal(socket.closeCode, 4002);
  assert.equal(manager.state, State.RECONNECT_WAIT);
  assert.equal(manager.getDiagnostics().reconnectTimerCount, 1);
});

test("reconnect delay sequence is 1, 2, 4, 8, 16 and 30 seconds", () => {
  const { manager, clock } = createManager();
  manager.start();
  const delays = [1000, 2000, 4000, 8000, 16000, 30000, 30000];

  for (let index = 0; index < delays.length; index += 1) {
    FakeWebSocket.instances[index].serverClose();
    clock.tick(delays[index] - 1);
    assert.equal(FakeWebSocket.instances.length, index + 1);
    clock.tick(1);
    assert.equal(FakeWebSocket.instances.length, index + 2);
  }
});

test("offline blocks attempts and online reconnects immediately", () => {
  const { manager, clock, windowRef } = createManager();
  manager.start();
  FakeWebSocket.instances[0].open();

  windowRef.navigator.onLine = false;
  windowRef.dispatch("offline");
  const attempts = manager.connectionAttempts;
  clock.tick(60000);
  assert.equal(manager.state, State.OFFLINE);
  assert.equal(manager.connectionAttempts, attempts);

  windowRef.navigator.onLine = true;
  windowRef.dispatch("online");
  assert.equal(manager.connectionAttempts, attempts + 1);
  assert.equal(manager.state, State.CONNECTING);
});

test("manual close, component destroy and authentication failure never reconnect", () => {
  const first = createManager();
  first.manager.start();
  first.manager.close("logout");
  first.clock.tick(60000);
  assert.equal(first.manager.connectionAttempts, 1);
  assert.equal(first.manager.state, State.CLOSED);

  const second = createManager();
  second.manager.start();
  second.windowRef.dispatch("campus:auth-failed");
  second.clock.tick(60000);
  assert.equal(second.manager.connectionAttempts, 1);
  assert.equal(second.manager.state, State.AUTH_FAILED);

  const third = createManager();
  third.manager.start();
  third.manager.destroy();
  third.documentRef.dispatch("visibilitychange");
  third.clock.tick(60000);
  assert.equal(third.manager.connectionAttempts, 1);
});

test("three forced disconnects retain only one socket and one timer of each type", () => {
  const { manager, clock } = createManager();
  manager.start();

  for (let index = 0; index < 3; index += 1) {
    const socket = FakeWebSocket.instances[index];
    socket.open();
    socket.receive({ type: "PONG", sentAt: 1, serverAt: 2 });
    socket.serverClose();
    const waiting = manager.getDiagnostics();
    assert.equal(waiting.socketCount, 0);
    assert.equal(waiting.reconnectTimerCount, 1);
    assert.equal(waiting.heartbeatTimerCount, 0);
    assert.equal(waiting.pongTimeoutCount, 0);
    clock.tick(1000);
  }

  FakeWebSocket.instances[3].open();
  const recovered = manager.getDiagnostics();
  assert.equal(recovered.socketCount, 1);
  assert.equal(recovered.reconnectTimerCount, 0);
  assert.equal(recovered.heartbeatTimerCount, 1);
  assert.equal(recovered.pongTimeoutCount, 1);
});

test("visibility recovery reconnects and repeated business messages are delivered once", () => {
  const { manager, documentRef, received } = createManager();
  manager.start();
  const first = FakeWebSocket.instances[0];
  first.open();
  first.receive({ messageId: "9001", title: "notice" });
  first.receive({ messageId: "9001", title: "notice" });
  first.serverClose();

  documentRef.visibilityState = "visible";
  documentRef.dispatch("visibilitychange");
  assert.equal(FakeWebSocket.instances.length, 2);
  FakeWebSocket.instances[1].open();
  FakeWebSocket.instances[1].receive({ messageId: "9001", title: "notice" });
  assert.equal(received.length, 1);
});
