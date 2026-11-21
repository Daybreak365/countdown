// 방 서버와 주고받기
export class Net {
  constructor() {
    this.ws = null;
    this.handlers = {};
    this.id = null;
  }

  on(type, fn) {
    this.handlers[type] = fn;
    return this;
  }

  emit(type, msg) {
    this.handlers[type]?.(msg);
  }

  connect() {
    return new Promise((resolve, reject) => {
      const proto = location.protocol === 'https:' ? 'wss' : 'ws';
      const ws = new WebSocket(`${proto}://${location.host}/ws`);
      this.ws = ws;
      let opened = false;
      ws.onopen = () => { opened = true; resolve(); };
      ws.onerror = () => { if (!opened) reject(new Error('connect')); };
      ws.onclose = () => {
        if (this.ws === ws) this.ws = null;
        if (opened) this.emit('closed');
      };
      ws.onmessage = (e) => {
        let msg;
        try { msg = JSON.parse(e.data); } catch { return; }
        if (msg.t === 'joined') this.id = msg.you.id;
        this.emit(msg.t, msg);
      };
    });
  }

  send(msg) {
    if (this.ws?.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(msg));
  }

  close() {
    const ws = this.ws;
    this.ws = null;
    this.id = null;
    ws?.close();
  }

  get open() {
    return this.ws?.readyState === WebSocket.OPEN;
  }
}
