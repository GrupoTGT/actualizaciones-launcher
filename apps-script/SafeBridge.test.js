'use strict';

const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const vm = require('node:vm');

class Range {
  constructor(sheet, row, column, rows, columns) {
    Object.assign(this, { sheet, row, column, rows, columns });
  }
  getValues() { return this.#matrix(false); }
  getDisplayValues() { return this.#matrix(true); }
  getValue() { return this.sheet.value(this.row, this.column); }
  setValue(value) { this.sheet.set(this.row, this.column, value); return this; }
  setValues(values) {
    for (let r = 0; r < this.rows; r += 1) {
      for (let c = 0; c < this.columns; c += 1) this.sheet.set(this.row + r, this.column + c, values[r][c]);
    }
    return this;
  }
  #matrix(display) {
    return Array.from({ length: this.rows }, (_, r) => Array.from({ length: this.columns }, (_, c) => {
      const value = this.sheet.value(this.row + r, this.column + c);
      return display ? String(value ?? '') : value;
    }));
  }
}

class Sheet {
  constructor(name) { this.name = name; this.rows = []; this.maxColumns = 50; }
  value(row, column) { return this.rows[row - 1]?.[column - 1] ?? ''; }
  set(row, column, value) {
    while (this.rows.length < row) this.rows.push([]);
    while (this.rows[row - 1].length < column) this.rows[row - 1].push('');
    this.rows[row - 1][column - 1] = value;
  }
  getRange(row, column, rows = 1, columns = 1) { return new Range(this, row, column, rows, columns); }
  getLastRow() {
    for (let i = this.rows.length - 1; i >= 0; i -= 1) if (this.rows[i].some(value => value !== '')) return i + 1;
    return 0;
  }
  getMaxColumns() { return this.maxColumns; }
  insertColumnsAfter(_, count) { this.maxColumns += count; }
  appendRow(values) { this.getRange(Math.max(1, this.getLastRow() + 1), 1, 1, values.length).setValues([values]); }
  deleteRow(row) { this.rows.splice(row - 1, 1); }
  getDataRange() { return this.getRange(1, 1, Math.max(1, this.getLastRow()), this.maxColumns); }
}

const sheets = new Map([
  '1_TERMINALES', '_SB_DEVICES', '_SB_NONCES', '_SB_AUDIT', '_SB_TELEMETRY',
  '2_AGENDA', '_MDM_CONTACT_PROFILE', '3_APLICACIONES', '_MDM_APP_PROFILE', '_MDM_CONFIG_DESIRED'
].map(name => [name, new Sheet(name)]));
for (let row = 1; row <= 4; row += 1) sheets.get('1_TERMINALES').set(row, 1, row === 1 ? 'device_id' : '');

const properties = new Map();
const context = vm.createContext({
  console,
  Date,
  JSON,
  Math,
  Object,
  Array,
  Number,
  String,
  RegExp,
  Error,
  PropertiesService: { getScriptProperties: () => ({
    getProperty: key => properties.get(key) ?? null,
    setProperty: (key, value) => properties.set(key, value)
  }) },
  LockService: { getScriptLock: () => ({ waitLock() {}, releaseLock() {} }) },
  SpreadsheetApp: { openById: () => ({ getSheetByName: name => sheets.get(name) || null }) },
  ContentService: {
    MimeType: { JSON: 'JSON' },
    createTextOutput: value => ({ value, setMimeType() { return this; } })
  },
  Utilities: {
    DigestAlgorithm: { SHA_256: 'sha256' },
    newBlob: value => ({ getBytes: () => Buffer.from(String(value), 'utf8') }),
    computeDigest: (_, bytes) => Array.from(
      crypto.createHash('sha256').update(Buffer.from(bytes)).digest(), value => value > 127 ? value - 256 : value
    ),
    computeHmacSha256Signature: (value, secret) => Array.from(
      crypto.createHmac('sha256', secret).update(value).digest(), byte => byte > 127 ? byte - 256 : byte
    ),
    base64EncodeWebSafe: bytes => Buffer.from(bytes).toString('base64url'),
    base64DecodeWebSafe: value => Buffer.from(value, 'base64url')
  }
});
vm.runInContext(fs.readFileSync(__dirname + '/SafeBridge.gs', 'utf8'), context);

const secret = crypto.randomBytes(32).toString('base64url');
let nonceCounter = 0;
function request(deviceId, timestamp = Date.now()) {
  const payload = { device_secret: secret, app_version: '64.0 (64)', model: 'SM-A165F', android: '14' };
  const nonce = crypto.createHash('sha256').update(String(++nonceCounter)).digest('base64url').slice(0, 32);
  const bodyHash = context.sha256Hex_(context.canonicalJson_(payload));
  const canonical = ['1', 'enroll', deviceId, String(timestamp), nonce, bodyHash].join('\n');
  return {
    contract_version: 1, action: 'enroll', device_id: deviceId, timestamp_ms: timestamp,
    nonce, payload, body_sha256: bodyHash,
    signature: crypto.createHmac('sha256', secret).update(canonical).digest('base64url')
  };
}

const deviceId = 'device-test-1234';
const firstRequest = request(deviceId);
const first = context.enroll_(firstRequest);
assert.equal(first.data.approval_state, 'PENDING_APPROVAL');
assert.equal(first.data.commands_enabled, false);
assert.equal(sheets.get('1_TERMINALES').getLastRow(), 5);
assert.equal(sheets.get('_SB_DEVICES').getLastRow(), 2);
assert.equal(sheets.get('1_TERMINALES').value(5, 23), 'SIN ACK');

const repeated = context.enroll_(request(deviceId));
assert.equal(repeated.data.approval_state, 'PENDING_APPROVAL');
assert.equal(sheets.get('1_TERMINALES').getLastRow(), 5, 'repeated registration must not duplicate terminal');
assert.equal(sheets.get('_SB_DEVICES').getLastRow(), 2, 'repeated registration must not duplicate device');

sheets.get('_SB_DEVICES').set(2, 31, 'APPROVED');
sheets.get('_SB_DEVICES').set(2, 33, false);
sheets.get('1_TERMINALES').set(5, 4, 'PROFILE_SALA');
const linked = context.enroll_(request(deviceId));
assert.equal(linked.data.approval_state, 'APPROVED');
assert.equal(linked.data.profile_id, 'PROFILE_SALA');
assert.equal(linked.data.commands_enabled, false);

const invalid = request('device-invalid-1');
invalid.signature = 'x'.repeat(64);
assert.throws(() => context.enroll_(invalid), error => error.bridgeCode === 'INVALID_SIGNATURE');
assert.throws(() => context.enroll_(firstRequest), error => error.bridgeCode === 'REPLAY_DETECTED');
assert.throws(() => context.validateEnvelope_(request('device-stale-123', Date.now() - 10 * 60 * 1000)),
  error => error.bridgeCode === 'STALE_TIMESTAMP');

const airplane = context.normalizedTelemetry_({ airplane_mode: true, wifi_connected: false, internet_validated: false });
assert.equal(airplane.airplane_mode, true);
assert.equal(airplane.wifi_connected, false);
assert.equal(airplane.internet_validated, false);
assert.equal(airplane.vowifi_state, 'NO VERIFICABLE');

console.log('SAFE_BRIDGE_TESTS_OK');
