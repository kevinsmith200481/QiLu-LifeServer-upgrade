(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) {
    module.exports = api;
  }
  if (root) {
    root.CampusWebSocket = api;
  }
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  const State = Object.freeze({
    CONNECTING: "CONNECTING",
    OPEN: "OPEN",
    RECONNECT_WAIT: "RECONNECT_WAIT",
    OFFLINE: "OFFLINE",
    CLOSED: "CLOSED",
    AUTH_FAILED: "AUTH_FAILED"
  });

  const DEFAULT_RECONNECT_DELAYS = [1000, 2000, 4000, 8000, 16000, 30000];

  class CampusWebSocketManager {
    constructor(options) {
      const settings = options || {};
      this.WebSocketImpl = settings.WebSocketImpl || (typeof WebSocket !== "undefined" ? WebSocket : null);
      this.windowRef = settings.windowRef || (typeof window !== "undefined" ? window : null);
      this.documentRef = settings.documentRef || (typeof document !== "undefined" ? document : null);
      this.urlFactory = settings.urlFactory || (() => "");
      this.onMessage = settings.onMessage || (() => {});
      this.onStateChange = settings.onStateChange || (() => {});
      this.onAuthFailed = settings.onAuthFailed || (() => {});
      this.random = settings.random || Math.random;
      const timerHost = this.windowRef || globalThis;
      // Browser timer functions require window as their receiver; binding also keeps injected fake timers intact.
      this.setTimeoutFn = settings.setTimeoutFn || timerHost.setTimeout.bind(timerHost);
      this.clearTimeoutFn = settings.clearTimeoutFn || timerHost.clearTimeout.bind(timerHost);
      this.setIntervalFn = settings.setIntervalFn || timerHost.setInterval.bind(timerHost);
      this.clearIntervalFn = settings.clearIntervalFn || timerHost.clearInterval.bind(timerHost);
      this.heartbeatIntervalMs = settings.heartbeatIntervalMs || 20000;
      this.pongTimeoutMs = settings.pongTimeoutMs || 10000;
      this.reconnectDelays = settings.reconnectDelays || DEFAULT_RECONNECT_DELAYS;

      this.state = State.CLOSED;
      this.socket = null;
      this.reconnectTimer = null;
      this.heartbeatTimer = null;
      this.pongTimeout = null;
      this.reconnectAttempt = 0;
      this.connectionAttempts = 0;
      this.online = !this.windowRef || !this.windowRef.navigator || this.windowRef.navigator.onLine !== false;
      this.manualStopped = true;
      this.listenersAttached = false;
      this.deliveredMessageIds = new Set();

      this.boundOnline = () => this.handleOnline();
      this.boundOffline = () => this.handleOffline();
      this.boundVisibility = () => this.handleVisibilityChange();
      this.boundAuthFailed = () => this.authFailed();
    }

    start() {
      if (!this.WebSocketImpl || !this.urlFactory()) {
        this.setState(State.CLOSED);
        return;
      }
      this.manualStopped = false;
      this.attachListeners();
      if (!this.online) {
        this.setState(State.OFFLINE);
        return;
      }
      this.connect();
    }

    connect() {
      if (this.manualStopped || !this.online || this.state === State.AUTH_FAILED) {
        return;
      }
      if (this.socket) {
        return;
      }
      this.clearReconnectTimer();
      this.setState(State.CONNECTING);
      this.connectionAttempts += 1;

      let socket;
      try {
        socket = new this.WebSocketImpl(this.urlFactory());
      } catch (error) {
        this.scheduleReconnect();
        return;
      }
      this.socket = socket;
      socket.onopen = () => this.handleOpen(socket);
      socket.onmessage = event => this.handleSocketMessage(socket, event && event.data);
      socket.onerror = () => this.handleSocketError(socket);
      socket.onclose = event => this.handleSocketClose(socket, event || {});
    }

    handleOpen(socket) {
      if (socket !== this.socket || this.manualStopped) {
        return;
      }
      this.reconnectAttempt = 0;
      this.setState(State.OPEN);
      this.startHeartbeat();
    }

    handleSocketMessage(socket, raw) {
      if (socket !== this.socket) {
        return;
      }
      let payload;
      try {
        payload = JSON.parse(raw);
      } catch (error) {
        return;
      }
      if (payload && payload.type === "PONG") {
        this.clearPongTimeout();
        return;
      }
      if (payload && payload.type === "AUTH_FAILED") {
        this.authFailed();
        return;
      }
      if (payload && payload.messageId) {
        const messageId = String(payload.messageId);
        if (this.deliveredMessageIds.has(messageId)) {
          return;
        }
        this.deliveredMessageIds.add(messageId);
        // Keep duplicate suppression bounded for long-lived browser tabs.
        if (this.deliveredMessageIds.size > 200) {
          this.deliveredMessageIds.delete(this.deliveredMessageIds.values().next().value);
        }
      }
      this.onMessage(raw, payload);
    }

    handleSocketError(socket) {
      if (socket !== this.socket) {
        return;
      }
      try {
        // Browsers only allow client close code 1000 or 3000-4999.
        socket.close(4002, "transport error");
      } catch (error) {
        this.socket = null;
        this.stopHeartbeat();
        this.scheduleReconnect();
      }
    }

    handleSocketClose(socket, event) {
      if (socket !== this.socket) {
        return;
      }
      this.socket = null;
      this.stopHeartbeat();
      if (event.code === 4401) {
        this.authFailed();
        return;
      }
      if (this.manualStopped) {
        this.setState(State.CLOSED);
        return;
      }
      if (!this.online) {
        this.setState(State.OFFLINE);
        return;
      }
      this.scheduleReconnect();
    }

    startHeartbeat() {
      this.stopHeartbeat();
      this.sendPing();
      this.heartbeatTimer = this.setIntervalFn(() => this.sendPing(), this.heartbeatIntervalMs);
    }

    sendPing() {
      if (!this.socket || this.state !== State.OPEN) {
        return;
      }
      try {
        this.socket.send(JSON.stringify({ type: "PING", sentAt: Date.now() }));
      } catch (error) {
        this.handleSocketError(this.socket);
        return;
      }
      this.clearPongTimeout();
      this.pongTimeout = this.setTimeoutFn(() => {
        this.pongTimeout = null;
        const socket = this.socket;
        if (socket) {
          socket.close(4000, "pong timeout");
        }
      }, this.pongTimeoutMs);
    }

    scheduleReconnect() {
      if (this.manualStopped || !this.online || this.reconnectTimer || this.state === State.AUTH_FAILED) {
        return;
      }
      const index = Math.min(this.reconnectAttempt, this.reconnectDelays.length - 1);
      const baseDelay = this.reconnectDelays[index];
      const jitter = Math.floor(baseDelay * 0.2 * this.random());
      this.reconnectAttempt += 1;
      this.setState(State.RECONNECT_WAIT);
      this.reconnectTimer = this.setTimeoutFn(() => {
        this.reconnectTimer = null;
        this.connect();
      }, baseDelay + jitter);
    }

    handleOffline() {
      this.online = false;
      this.clearReconnectTimer();
      this.stopHeartbeat();
      const socket = this.socket;
      this.socket = null;
      if (socket) {
        socket.close(1000, "browser offline");
      }
      if (!this.manualStopped && this.state !== State.AUTH_FAILED) {
        this.setState(State.OFFLINE);
      }
    }

    handleOnline() {
      this.online = true;
      if (!this.manualStopped && this.state !== State.AUTH_FAILED && !this.socket) {
        this.connect();
      }
    }

    handleVisibilityChange() {
      if (!this.documentRef || this.documentRef.visibilityState !== "visible") {
        return;
      }
      if (!this.manualStopped && this.online && !this.socket && this.state !== State.AUTH_FAILED) {
        this.connect();
      }
    }

    authFailed() {
      if (this.state === State.AUTH_FAILED) {
        return;
      }
      this.manualStopped = true;
      this.clearResources(4401, "authentication failed");
      this.setState(State.AUTH_FAILED);
      this.onAuthFailed();
    }

    close(reason) {
      this.manualStopped = true;
      this.clearResources(1000, reason || "manual close");
      this.setState(State.CLOSED);
    }

    destroy() {
      this.close("component destroyed");
      this.detachListeners();
    }

    clearResources(code, reason) {
      this.clearReconnectTimer();
      this.stopHeartbeat();
      const socket = this.socket;
      this.socket = null;
      if (socket) {
        socket.close(code, reason);
      }
    }

    clearReconnectTimer() {
      if (this.reconnectTimer !== null) {
        this.clearTimeoutFn(this.reconnectTimer);
        this.reconnectTimer = null;
      }
    }

    stopHeartbeat() {
      if (this.heartbeatTimer !== null) {
        this.clearIntervalFn(this.heartbeatTimer);
        this.heartbeatTimer = null;
      }
      this.clearPongTimeout();
    }

    clearPongTimeout() {
      if (this.pongTimeout !== null) {
        this.clearTimeoutFn(this.pongTimeout);
        this.pongTimeout = null;
      }
    }

    attachListeners() {
      if (this.listenersAttached || !this.windowRef) {
        return;
      }
      this.windowRef.addEventListener("online", this.boundOnline);
      this.windowRef.addEventListener("offline", this.boundOffline);
      this.windowRef.addEventListener("campus:auth-failed", this.boundAuthFailed);
      if (this.documentRef) {
        this.documentRef.addEventListener("visibilitychange", this.boundVisibility);
      }
      this.listenersAttached = true;
    }

    detachListeners() {
      if (!this.listenersAttached || !this.windowRef) {
        return;
      }
      this.windowRef.removeEventListener("online", this.boundOnline);
      this.windowRef.removeEventListener("offline", this.boundOffline);
      this.windowRef.removeEventListener("campus:auth-failed", this.boundAuthFailed);
      if (this.documentRef) {
        this.documentRef.removeEventListener("visibilitychange", this.boundVisibility);
      }
      this.listenersAttached = false;
    }

    setState(state) {
      this.state = state;
      this.onStateChange(state);
    }

    getDiagnostics() {
      return {
        state: this.state,
        connectionAttempts: this.connectionAttempts,
        socketCount: this.socket ? 1 : 0,
        reconnectTimerCount: this.reconnectTimer === null ? 0 : 1,
        heartbeatTimerCount: this.heartbeatTimer === null ? 0 : 1,
        pongTimeoutCount: this.pongTimeout === null ? 0 : 1
      };
    }
  }

  return { CampusWebSocketManager, State, DEFAULT_RECONNECT_DELAYS };
});
