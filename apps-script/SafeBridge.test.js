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
  '_SB_COMMANDS', '_SB_ACKS',
  '_SB_OTA_ASSIGNMENTS',
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

const goldenPayload = { z: '</tag>', a: ['ñ', true, 1], nested: { b: 'line\n', a: null } };
const goldenCanonical = context.canonicalJson_(goldenPayload);
assert.equal(goldenCanonical, '{"a":["ñ",true,1],"nested":{"a":null,"b":"line\\n"},"z":"</tag>"}');
assert.equal(context.sha256Hex_(goldenCanonical), '8701f0e972647967a5bc953d769d0dc8f2d8db5a63695a77ef8b2e92c21c50b5');
assert.equal(
  context.Utilities.base64EncodeWebSafe(context.Utilities.computeHmacSha256Signature(goldenCanonical, 'vector-secret')),
  'P0r25EXcCkmVL3q80ZdeCLlJVKL7N5W_ghRYAhh8BzU'
);

const secret = crypto.randomBytes(32).toString('base64url');
let nonceCounter = 0;
function request(deviceId, timestamp = Date.now(), requestSecret = secret) {
  const payload = { device_secret: requestSecret, app_version: '64.0 (64)', model: 'SM-A165F', android: '14' };
  const nonce = crypto.createHash('sha256').update(String(++nonceCounter)).digest('base64url').slice(0, 32);
  const bodyHash = context.sha256Hex_(context.canonicalJson_(payload));
  const canonical = ['1', 'enroll', deviceId, String(timestamp), nonce, bodyHash].join('\n');
  return {
    contract_version: 1, action: 'enroll', device_id: deviceId, timestamp_ms: timestamp,
    nonce, payload, body_sha256: bodyHash,
    signature: crypto.createHmac('sha256', requestSecret).update(canonical).digest('base64url')
  };
}

function telemetryRequest(deviceId, payload, timestamp = Date.now(), requestSecret = secret) {
  const nonce = crypto.createHash('sha256').update(String(++nonceCounter)).digest('base64url').slice(0, 32);
  const bodyHash = context.sha256Hex_(context.canonicalJson_(payload));
  const canonical = ['1', 'telemetry', deviceId, String(timestamp), nonce, bodyHash].join('\n');
  return {
    contract_version: 1, action: 'telemetry', device_id: deviceId, timestamp_ms: timestamp,
    nonce, payload, body_sha256: bodyHash,
    signature: crypto.createHmac('sha256', requestSecret).update(canonical).digest('base64url')
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
sheets.get('2_AGENDA').set(6, 1, 'CONTACT_1');
sheets.get('2_AGENDA').set(6, 2, 'IT');
sheets.get('2_AGENDA').set(6, 3, '600000001');
sheets.get('2_AGENDA').set(6, 5, 'ACTIVO');
sheets.get('_MDM_CONTACT_PROFILE').set(2, 1, 'PROFILE_SALA');
sheets.get('_MDM_CONTACT_PROFILE').set(2, 2, 'CONTACT_1');
sheets.get('_MDM_CONTACT_PROFILE').set(2, 3, true);
sheets.get('_MDM_CONTACT_PROFILE').set(2, 4, true);
sheets.get('_MDM_CONTACT_PROFILE').set(2, 5, true);
sheets.get('3_APLICACIONES').set(5, 1, 'APP_1');
sheets.get('3_APLICACIONES').set(5, 2, 'Cámara');
sheets.get('3_APLICACIONES').set(5, 3, 'com.sec.android.app.camera');
sheets.get('3_APLICACIONES').set(5, 5, 'ACTIVO');
sheets.get('_MDM_APP_PROFILE').set(2, 1, 'PROFILE_SALA');
sheets.get('_MDM_APP_PROFILE').set(2, 2, 'APP_1');
sheets.get('_MDM_APP_PROFILE').set(2, 3, true);
const linked = context.enroll_(request(deviceId));
assert.equal(linked.data.approval_state, 'APPROVED');
assert.equal(linked.data.profile_id, 'PROFILE_SALA');
assert.equal(linked.data.commands_enabled, false);
assert.equal(linked.data.config_snapshot.contacts.length, 1);
assert.equal(linked.data.config_snapshot.apps.length, 1);
assert.equal(Object.keys(linked.data.config_snapshot.settings).length, 0);

sheets.get('_SB_OTA_ASSIGNMENTS').appendRow([
  'assignment_id', 'device_id', 'status', 'version_code', 'version_name',
  'apk_url', 'sha256', 'size_bytes', 'issued_at', 'expires_at',
  'last_delivered_at', 'delivery_count', 'last_error'
]);
sheets.get('_SB_OTA_ASSIGNMENTS').appendRow([
  'SALA3-V65-PILOT', deviceId, 'ACTIVE', 65, '65.0-pilot',
  'https://github.com/GrupoTGT/actualizaciones-launcher/releases/download/' +
    'v65.0-pilot/LauncherKioscoTGT-v65.0-pilot.apk',
  'a'.repeat(64), 6630440, new Date(), new Date(Date.now() + 60 * 60 * 1000), '', 0, ''
]);

const heartbeatWithoutCommands = context.telemetry_(telemetryRequest(deviceId, {
  app_version: '64.0 (64)', applied_mode: 'BLINDADO', applied_mode_revision: 0,
  transition_phase: 'STABLE', last_error: 'SIN ERROR', configured_apps: [],
  installed_configured_apps: []
}));
assert.equal(heartbeatWithoutCommands.data.commands_enabled, false);
assert.equal(heartbeatWithoutCommands.data.config_snapshot.contacts.length, 1);
assert.equal(heartbeatWithoutCommands.data.config_snapshot.apps.length, 1);
assert.equal(heartbeatWithoutCommands.data.pilot_ota.device_id, deviceId);
assert.equal(heartbeatWithoutCommands.data.pilot_ota.version_code, 65);
assert.equal(context.resolvePilotOtaAssignment_('another-device', new Date()), null);

sheets.get('_SB_DEVICES').set(2, 33, true);
sheets.get('1_TERMINALES').set(5, 5, 'LIBRE GESTIONADO');
const directive = context.resolveManagedDirective_(
  deviceId,
  sheets.get('_SB_DEVICES'),
  2,
  sheets.get('1_TERMINALES'),
  5,
  new Date()
);
assert.equal(directive.mode, 'LIBRE GESTIONADO');
assert.equal(directive.revision, 1);
assert.equal(sheets.get('_SB_COMMANDS').value(2, 8), 'PENDING_ACK');
context.acknowledgeAppliedMode_(deviceId, context.normalizedTelemetry_({
  applied_mode: 'LIBRE GESTIONADO', applied_mode_revision: 1,
  transition_phase: 'STABLE', last_error: 'SIN ERROR'
}), new Date());
assert.equal(sheets.get('_SB_COMMANDS').value(2, 8), 'ACK_APPLIED');
assert.equal(sheets.get('_SB_ACKS').value(2, 4), 'ACK_APPLIED');

const approvalGuardId = 'device-approval-guard';
context.enroll_(request(approvalGuardId));
const guardRow = 3;
sheets.get('_SB_DEVICES').set(guardRow, 31, 'APPROVED');
const approvedSecretFingerprint = context.sha256Hex_(
  context.Utilities.base64DecodeWebSafe(secret)
).substring(0, 24).toUpperCase();
sheets.get('_SB_DEVICES').set(guardRow, 32, '');
assert.throws(
  () => context.enroll_(request(approvalGuardId)),
  error => error.bridgeCode === 'APPROVAL_FINGERPRINT_REQUIRED'
);
sheets.get('_SB_DEVICES').set(guardRow, 32, approvedSecretFingerprint);
const attackerSecret = crypto.randomBytes(32).toString('base64url');
assert.throws(
  () => context.enroll_(request(approvalGuardId, Date.now(), attackerSecret)),
  error => error.bridgeCode === 'CREDENTIAL_APPROVAL_MISMATCH'
);

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
