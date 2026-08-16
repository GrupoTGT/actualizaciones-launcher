'use strict';

const MDM = Object.freeze({
  spreadsheetId: '1MhpLIjGF2ZOliUO_Bske_oZ8Zcq-2rTLNiI3rSIj1nw',
  contractVersion: 1,
  serviceVersion: '3.0.0-cutover',
  maxClockSkewMs: 5 * 60 * 1000,
  nonceRetentionMs: 24 * 60 * 60 * 1000,
  secretPrefix: 'DEVICE_SECRET_',
  sheets: Object.freeze({
    terminals: '1_TERMINALES',
    devices: '_SB_DEVICES',
    nonces: '_SB_NONCES',
    audit: '_SB_AUDIT',
    contacts: '2_AGENDA',
    contactProfiles: '_MDM_CONTACT_PROFILE',
    apps: '3_APLICACIONES',
    appProfiles: '_MDM_APP_PROFILE',
    config: '_MDM_CONFIG_DESIRED'
  })
});

function doGet() {
  return jsonOutput_({
    ok: true,
    service: 'TGT MDM SAFE BRIDGE',
    version: MDM.serviceVersion,
    contract_version: MDM.contractVersion,
    time: new Date().toISOString()
  });
}

function doPost(e) {
  try {
    const request = parseRequest_(e);
    if (request.action !== 'enroll') {
      throw bridgeError_('UNSUPPORTED_ACTION', 'Action is not enabled in this deployment.');
    }
    return jsonOutput_(enroll_(request));
  } catch (error) {
    const code = error && error.bridgeCode ? error.bridgeCode : 'INTERNAL_ERROR';
    audit_('ERROR', code, safeErrorMessage_(error));
    return jsonOutput_({
      ok: false,
      contract_version: MDM.contractVersion,
      error: code
    });
  }
}

function enroll_(request) {
  validateEnvelope_(request);
  const payload = request.payload;
  const proposedSecret = requireText_(payload.device_secret, 'device_secret', 40, 128);
  const secretKey = secretPropertyKey_(request.device_id);
  const properties = PropertiesService.getScriptProperties();
  const existingSecret = properties.getProperty(secretKey);
  const verificationSecret = existingSecret || proposedSecret;

  verifySignature_(request, verificationSecret);
  consumeNonce_(request.device_id, request.nonce, request.timestamp_ms, request.action);

  const lock = LockService.getScriptLock();
  lock.waitLock(15000);
  try {
    const currentSecret = properties.getProperty(secretKey);
    if (currentSecret && !constantTimeEquals_(currentSecret, proposedSecret)) {
      throw bridgeError_('CREDENTIAL_ALREADY_BOUND', 'A different credential is already pending or active.');
    }
    if (!currentSecret) {
      properties.setProperty(secretKey, proposedSecret);
    }

    const fingerprint = sha256Hex_(Utilities.base64DecodeWebSafe(proposedSecret)).substring(0, 24).toUpperCase();
    const registration = upsertPendingRegistration_(request.device_id, payload, fingerprint);
    const data = {
      approval_state: registration.approvalState,
      commands_enabled: registration.commandsEnabled,
      credential_fingerprint: fingerprint,
      terminal: registration.terminal,
      profile_id: registration.approvalState === 'APPROVED' ? registration.profileId : 'PENDIENTE_SEGURO',
      mode: registration.approvalState === 'APPROVED' ? safeMode_(registration.mode) : 'BLINDADO',
      mode_revision: registration.modeRevision
    };
    if (registration.approvalState === 'APPROVED' && registration.commandsEnabled) {
      data.config_snapshot = buildConfigSnapshot_(request.device_id, registration);
    }
    audit_('INFO', 'ENROLL_OK', request.device_id + ' state=' + registration.approvalState);
    return signedResponse_(request.device_id, request.nonce, data, proposedSecret);
  } finally {
    lock.releaseLock();
  }
}

function parseRequest_(e) {
  const raw = e && e.postData && e.postData.contents ? String(e.postData.contents) : '';
  if (!raw || raw.length > 200000) {
    throw bridgeError_('INVALID_BODY', 'Missing or oversized body.');
  }
  let request;
  try {
    request = JSON.parse(raw);
  } catch (_) {
    throw bridgeError_('INVALID_JSON', 'Body is not valid JSON.');
  }
  if (!request || typeof request !== 'object' || Array.isArray(request)) {
    throw bridgeError_('INVALID_JSON', 'JSON root must be an object.');
  }
  return request;
}

function validateEnvelope_(request) {
  if (Number(request.contract_version) !== MDM.contractVersion) {
    throw bridgeError_('UNSUPPORTED_CONTRACT', 'Unsupported contract version.');
  }
  requireText_(request.action, 'action', 1, 40);
  const deviceId = requireText_(request.device_id, 'device_id', 8, 80);
  if (!/^[A-Za-z0-9._-]+$/.test(deviceId)) {
    throw bridgeError_('INVALID_DEVICE_ID', 'Invalid device identifier.');
  }
  const nonce = requireText_(request.nonce, 'nonce', 16, 128);
  if (!/^[A-Za-z0-9_-]+$/.test(nonce)) {
    throw bridgeError_('INVALID_NONCE', 'Invalid nonce.');
  }
  const timestamp = Number(request.timestamp_ms);
  if (!Number.isFinite(timestamp) || Math.abs(Date.now() - timestamp) > MDM.maxClockSkewMs) {
    throw bridgeError_('STALE_TIMESTAMP', 'Timestamp outside the accepted window.');
  }
  if (!request.payload || typeof request.payload !== 'object' || Array.isArray(request.payload)) {
    throw bridgeError_('INVALID_PAYLOAD', 'Payload must be an object.');
  }
  requireText_(request.body_sha256, 'body_sha256', 64, 64);
  requireText_(request.signature, 'signature', 40, 128);
}

function verifySignature_(request, secret) {
  const expectedBodyHash = sha256Hex_(canonicalJson_(request.payload));
  if (!constantTimeEquals_(expectedBodyHash, String(request.body_sha256).toLowerCase())) {
    throw bridgeError_('BODY_HASH_MISMATCH', 'Payload digest does not match.');
  }
  const canonical = [
    String(request.contract_version),
    request.action,
    request.device_id,
    String(request.timestamp_ms),
    request.nonce,
    expectedBodyHash
  ].join('\n');
  const expected = Utilities.base64EncodeWebSafe(
    Utilities.computeHmacSha256Signature(canonical, secret)
  ).replace(/=+$/g, '');
  if (!constantTimeEquals_(expected, request.signature)) {
    throw bridgeError_('INVALID_SIGNATURE', 'Signature verification failed.');
  }
}

function consumeNonce_(deviceId, nonce, timestamp, action) {
  const lock = LockService.getScriptLock();
  lock.waitLock(15000);
  try {
    const sheet = sheet_(MDM.sheets.nonces);
    ensureHeader_(sheet, ['received_at', 'device_id', 'nonce', 'timestamp_ms', 'action']);
    const lastRow = sheet.getLastRow();
    if (lastRow > 1) {
      const values = sheet.getRange(2, 1, lastRow - 1, 5).getValues();
      const cutoff = Date.now() - MDM.nonceRetentionMs;
      for (let i = values.length - 1; i >= 0; i -= 1) {
        const recordedAt = values[i][0] instanceof Date ? values[i][0].getTime() : Number(values[i][0]);
        if (String(values[i][1]) === deviceId && String(values[i][2]) === nonce) {
          throw bridgeError_('REPLAY_DETECTED', 'Nonce has already been consumed.');
        }
        if (recordedAt && recordedAt < cutoff && i < values.length - 500) {
          sheet.deleteRow(i + 2);
        }
      }
    }
    sheet.appendRow([new Date(), deviceId, nonce, timestamp, action]);
  } finally {
    lock.releaseLock();
  }
}

function upsertPendingRegistration_(deviceId, payload, fingerprint) {
  const terminals = sheet_(MDM.sheets.terminals);
  const terminalRow = findRow_(terminals, 1, deviceId, 5);
  let row = terminalRow;
  if (!row) {
    row = Math.max(terminals.getLastRow() + 1, 5);
    const suffix = deviceId.substring(Math.max(0, deviceId.length - 6)).toUpperCase();
    terminals.getRange(row, 1, 1, 24).setValues([[
      deviceId,
      'NUEVO-' + suffix,
      '',
      'PENDIENTE_SEGURO',
      'BLINDADO',
      'PENDIENTE DE CLASIFICAR',
      'TELEMETRÍA REAL',
      'PENDIENTE DE APROBACIÓN',
      'SIN DATO',
      'SIN DATO',
      'SIN DATO',
      'SIN DATO',
      'SIN DATO',
      'SIN DATO',
      'SIN DATO',
      'SIN DATO',
      'NO VERIFICABLE',
      'NO VERIFICABLE',
      'NO DISPONIBLE',
      'NO DISPONIBLE',
      safeText_(payload.app_version, 'NO DISPONIBLE', 80),
      '',
      'SIN ACK',
      'AUTOALTA PENDIENTE'
    ]]);
  }

  const devices = sheet_(MDM.sheets.devices);
  ensureDeviceColumns_(devices);
  let deviceRow = findRow_(devices, 1, deviceId, 2);
  if (!deviceRow) {
    deviceRow = Math.max(devices.getLastRow() + 1, 2);
    devices.getRange(deviceRow, 1, 1, 38).setValues([new Array(38).fill('')]);
    devices.getRange(deviceRow, 1).setValue(deviceId);
    devices.getRange(deviceRow, 6).setValue(new Date());
  }
  const currentAuth = String(devices.getRange(deviceRow, 31).getValue() || 'PENDING_APPROVAL');
  const commandsEnabled = devices.getRange(deviceRow, 33).getValue() === true && currentAuth === 'APPROVED';
  devices.getRange(deviceRow, 2).setValue(terminals.getRange(row, 2).getValue());
  devices.getRange(deviceRow, 3).setValue(terminals.getRange(row, 3).getValue());
  devices.getRange(deviceRow, 4).setValue(terminals.getRange(row, 4).getValue());
  devices.getRange(deviceRow, 5).setValue(terminals.getRange(row, 6).getValue());
  devices.getRange(deviceRow, 7).setValue(new Date());
  devices.getRange(deviceRow, 8).setValue(safeText_(payload.app_version, '', 80));
  devices.getRange(deviceRow, 9).setValue(safeText_(payload.model, '', 80));
  devices.getRange(deviceRow, 10).setValue(safeText_(payload.android, '', 80));
  devices.getRange(deviceRow, 31).setValue(currentAuth);
  devices.getRange(deviceRow, 32).setValue(fingerprint);
  devices.getRange(deviceRow, 33).setValue(commandsEnabled);
  devices.getRange(deviceRow, 34).setValue(safeMode_(terminals.getRange(row, 5).getValue()));

  return {
    approvalState: currentAuth,
    commandsEnabled: commandsEnabled,
    terminal: String(terminals.getRange(row, 2).getValue()),
    section: String(terminals.getRange(row, 3).getValue()),
    profileId: String(terminals.getRange(row, 4).getValue() || 'PENDIENTE_SEGURO'),
    mode: String(terminals.getRange(row, 5).getValue() || 'BLINDADO'),
    modeRevision: Math.max(0, Number(devices.getRange(deviceRow, 36).getValue()) || 0),
    configRevision: Math.max(0, Number(devices.getRange(deviceRow, 37).getValue()) || 0),
    deviceRow: deviceRow
  };
}

function buildConfigSnapshot_(deviceId, registration) {
  const contactRows = sheet_(MDM.sheets.contacts).getDataRange().getValues().slice(5);
  const contactsById = {};
  contactRows.forEach(function (row) {
    const id = String(row[0] || '').trim();
    const phone = String(row[2] || '').replace(/[^0-9]/g, '');
    if (id && phone && String(row[4] || '').toUpperCase() === 'ACTIVO') {
      contactsById[id] = { contact_id: id, name: String(row[1] || '').trim(), phone: phone };
    }
  });

  const contacts = [];
  const seenPhones = {};
  sheet_(MDM.sheets.contactProfiles).getDataRange().getValues().slice(1).forEach(function (row) {
    if (String(row[0]) !== registration.profileId || row[2] !== true) return;
    const contact = contactsById[String(row[1])];
    if (!contact || seenPhones[contact.phone]) return;
    seenPhones[contact.phone] = true;
    contacts.push({
      contact_id: contact.contact_id,
      name: contact.name,
      phone: contact.phone,
      can_call_terminal: row[3] === true,
      terminal_can_call: row[4] === true
    });
  });

  const appRows = sheet_(MDM.sheets.apps).getDataRange().getValues().slice(4);
  const appsById = {};
  appRows.forEach(function (row) {
    const id = String(row[0] || '').trim();
    const packageName = String(row[2] || '').trim();
    if (id && packageName && String(row[4] || '').toUpperCase() === 'ACTIVO') {
      appsById[id] = {
        app_id: id,
        label: String(row[1] || '').trim(),
        package_name: packageName,
        order: Math.max(0, Number(row[5]) || 0)
      };
    }
  });

  const apps = [];
  const seenPackages = {};
  sheet_(MDM.sheets.appProfiles).getDataRange().getValues().slice(1).forEach(function (row) {
    if (String(row[0]) !== registration.profileId || row[2] !== true) return;
    const app = appsById[String(row[1])];
    if (!app || seenPackages[app.package_name]) return;
    seenPackages[app.package_name] = true;
    apps.push(app);
  });
  apps.sort(function (a, b) { return a.order - b.order || a.package_name.localeCompare(b.package_name); });

  const settings = {};
  const priorities = {};
  sheet_(MDM.sheets.config).getDataRange().getValues().slice(1).forEach(function (row) {
    const scope = String(row[1] || '').trim().toUpperCase();
    const target = String(row[2] || '').trim();
    const key = String(row[3] || '').trim().toUpperCase();
    if (!key || key === 'PANEL_IT_PASSWORD') return;
    let priority = 0;
    if (scope === 'GLOBAL' && target === 'GLOBAL') priority = 1;
    if (scope === 'PERFIL' && target === registration.profileId) priority = 2;
    if (scope === 'TERMINAL' && target === deviceId) priority = 3;
    if (priority && priority >= (priorities[key] || 0)) {
      settings[key] = String(row[4] === null || row[4] === undefined ? '' : row[4]);
      priorities[key] = priority;
    }
  });

  const snapshot = {
    schema_version: 1,
    complete: true,
    device_id: deviceId,
    revision: 0,
    profile_id: registration.profileId,
    terminal: registration.terminal,
    section: registration.section,
    contacts: contacts,
    apps: apps,
    settings: settings
  };
  const snapshotHash = sha256Hex_(canonicalJson_(snapshot));
  const devices = sheet_(MDM.sheets.devices);
  const previousHash = String(devices.getRange(registration.deviceRow, 18).getValue() || '');
  let revision = registration.configRevision;
  if (!constantTimeEquals_(previousHash, snapshotHash)) {
    revision += 1;
    devices.getRange(registration.deviceRow, 18).setValue(snapshotHash);
    devices.getRange(registration.deviceRow, 37).setValue(revision);
  }
  snapshot.revision = revision;
  return snapshot;
}

function ensureDeviceColumns_(sheet) {
  if (sheet.getMaxColumns() < 38) {
    sheet.insertColumnsAfter(sheet.getMaxColumns(), 38 - sheet.getMaxColumns());
  }
  const extraHeaders = [
    'auth_state',
    'credential_fingerprint',
    'commands_enabled',
    'desired_mode',
    'applied_mode',
    'mode_revision',
    'config_revision',
    'last_ack_error'
  ];
  sheet.getRange(1, 31, 1, extraHeaders.length).setValues([extraHeaders]);
}

function signedResponse_(deviceId, requestNonce, data, secret) {
  const body = {
    ok: true,
    contract_version: MDM.contractVersion,
    server_time_ms: Date.now(),
    device_id: deviceId,
    request_nonce: requestNonce,
    data: data
  };
  const signature = Utilities.base64EncodeWebSafe(
    Utilities.computeHmacSha256Signature(canonicalJson_(body), secret)
  ).replace(/=+$/g, '');
  body.response_signature = signature;
  return body;
}

function canonicalJson_(value) {
  if (value === null) return 'null';
  if (Array.isArray(value)) return '[' + value.map(canonicalJson_).join(',') + ']';
  if (typeof value === 'object') {
    return '{' + Object.keys(value).sort().map(function (key) {
      return JSON.stringify(key) + ':' + canonicalJson_(value[key]);
    }).join(',') + '}';
  }
  return JSON.stringify(value);
}

function sha256Hex_(value) {
  const bytes = typeof value === 'string' ? Utilities.newBlob(value).getBytes() : value;
  return Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, bytes).map(function (b) {
    return ((b + 256) % 256).toString(16).padStart(2, '0');
  }).join('');
}

function constantTimeEquals_(a, b) {
  const left = String(a || '');
  const right = String(b || '');
  let mismatch = left.length ^ right.length;
  const length = Math.max(left.length, right.length);
  for (let i = 0; i < length; i += 1) {
    mismatch |= (left.charCodeAt(i % Math.max(1, left.length)) || 0) ^
      (right.charCodeAt(i % Math.max(1, right.length)) || 0);
  }
  return mismatch === 0;
}

function secretPropertyKey_(deviceId) {
  return MDM.secretPrefix + sha256Hex_(deviceId).substring(0, 32).toUpperCase();
}

function safeMode_(value) {
  return String(value || '').trim().toUpperCase() === 'LIBRE GESTIONADO'
    ? 'LIBRE GESTIONADO'
    : 'BLINDADO';
}

function safeText_(value, fallback, maxLength) {
  const text = String(value === null || value === undefined ? '' : value).trim();
  if (!text) return fallback;
  return text.substring(0, maxLength);
}

function requireText_(value, field, minLength, maxLength) {
  const text = String(value === null || value === undefined ? '' : value);
  if (text.length < minLength || text.length > maxLength) {
    throw bridgeError_('INVALID_' + field.toUpperCase(), 'Invalid ' + field + '.');
  }
  return text;
}

function findRow_(sheet, column, value, firstDataRow) {
  const lastRow = sheet.getLastRow();
  if (lastRow < firstDataRow) return 0;
  const values = sheet.getRange(firstDataRow, column, lastRow - firstDataRow + 1, 1).getDisplayValues();
  for (let i = 0; i < values.length; i += 1) {
    if (String(values[i][0]) === String(value)) return firstDataRow + i;
  }
  return 0;
}

function ensureHeader_(sheet, headers) {
  if (sheet.getMaxColumns() < headers.length) {
    sheet.insertColumnsAfter(sheet.getMaxColumns(), headers.length - sheet.getMaxColumns());
  }
  sheet.getRange(1, 1, 1, headers.length).setValues([headers]);
}

function sheet_(name) {
  const sheet = SpreadsheetApp.openById(MDM.spreadsheetId).getSheetByName(name);
  if (!sheet) throw bridgeError_('MISSING_SHEET', 'Required sheet is missing.');
  return sheet;
}

function audit_(level, code, message) {
  try {
    const sheet = sheet_(MDM.sheets.audit);
    ensureHeader_(sheet, ['received_at', 'level', 'code', 'message', 'service_version']);
    sheet.appendRow([new Date(), level, code, String(message || '').substring(0, 500), MDM.serviceVersion]);
  } catch (_) {
    // Never expose request bodies or credentials through fallback logging.
  }
}

function bridgeError_(code, message) {
  const error = new Error(message);
  error.bridgeCode = code;
  return error;
}

function safeErrorMessage_(error) {
  return error && error.bridgeCode ? error.bridgeCode : 'INTERNAL_ERROR';
}

function jsonOutput_(body) {
  return ContentService.createTextOutput(JSON.stringify(body))
    .setMimeType(ContentService.MimeType.JSON);
}
