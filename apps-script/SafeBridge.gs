'use strict';

const MDM = Object.freeze({
  spreadsheetId: '1MhpLIjGF2ZOliUO_Bske_oZ8Zcq-2rTLNiI3rSIj1nw',
  contractVersion: 1,
  serviceVersion: '3.4.0-device-scoped-pilot-ota',
  maxClockSkewMs: 5 * 60 * 1000,
  nonceRetentionMs: 24 * 60 * 60 * 1000,
  secretPrefix: 'DEVICE_SECRET_',
  sheets: Object.freeze({
    terminals: '1_TERMINALES',
    devices: '_SB_DEVICES',
    nonces: '_SB_NONCES',
    audit: '_SB_AUDIT',
    telemetry: '_SB_TELEMETRY',
    commands: '_SB_COMMANDS',
    acks: '_SB_ACKS',
    contacts: '2_AGENDA',
    contactProfiles: '_MDM_CONTACT_PROFILE',
    apps: '3_APLICACIONES',
    appProfiles: '_MDM_APP_PROFILE',
    config: '_MDM_CONFIG_DESIRED',
    otaAssignments: '_SB_OTA_ASSIGNMENTS'
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
    if (request.action === 'enroll') return jsonOutput_(enroll_(request));
    if (request.action === 'telemetry') return jsonOutput_(telemetry_(request));
    throw bridgeError_('UNSUPPORTED_ACTION', 'Action is not enabled in this deployment.');
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

function telemetry_(request) {
  validateEnvelope_(request);
  const properties = PropertiesService.getScriptProperties();
  const secret = properties.getProperty(secretPropertyKey_(request.device_id));
  if (!secret) throw bridgeError_('UNKNOWN_DEVICE', 'Device credential is not registered.');
  verifySignature_(request, secret);
  consumeNonce_(request.device_id, request.nonce, request.timestamp_ms, request.action);

  const lock = LockService.getScriptLock();
  lock.waitLock(15000);
  try {
    const devices = sheet_(MDM.sheets.devices);
    ensureDeviceColumns_(devices);
    const deviceRow = findRow_(devices, 1, request.device_id, 2);
    if (!deviceRow) throw bridgeError_('UNKNOWN_DEVICE', 'Device inventory row is missing.');
    const terminal = sheet_(MDM.sheets.terminals);
    const terminalRow = findRow_(terminal, 1, request.device_id, 5);
    const payload = normalizedTelemetry_(request.payload);
    const now = new Date();
    const approvalState = String(devices.getRange(deviceRow, 31).getValue() || 'PENDING_APPROVAL');
    const commandsEnabled = approvalState === 'APPROVED' && devices.getRange(deviceRow, 33).getValue() === true;
    devices.getRange(deviceRow, 35).setValue(payload.applied_mode);
    acknowledgeAppliedMode_(request.device_id, payload, now);
    const directive = commandsEnabled && terminalRow
      ? resolveManagedDirective_(request.device_id, devices, deviceRow, terminal, terminalRow, now)
      : { mode: 'BLINDADO', revision: 0, commandId: '' };

    devices.getRange(deviceRow, 7).setValue(now);
    devices.getRange(deviceRow, 8).setValue(payload.app_version);
    devices.getRange(deviceRow, 9).setValue(payload.model);
    devices.getRange(deviceRow, 10).setValue(payload.android);
    devices.getRange(deviceRow, 11).setValue(payload.ip);
    devices.getRange(deviceRow, 13).setValue(payload.battery_percent);
    devices.getRange(deviceRow, 14).setValue(payload.charging);
    devices.getRange(deviceRow, 15).setValue(payload.wifi_rssi_dbm);
    devices.getRange(deviceRow, 16).setValue(payload.brightness_percent);
    devices.getRange(deviceRow, 17).setValue(payload.volume_percent);
    devices.getRange(deviceRow, 19).setValue(payload.internet_validated);
    devices.getRange(deviceRow, 20).setValue(payload.ssid);
    devices.getRange(deviceRow, 23).setValue(payload.network_transport);
    devices.getRange(deviceRow, 24).setValue(payload.telephony_capable);
    devices.getRange(deviceRow, 25).setValue(payload.transition_phase);
    devices.getRange(deviceRow, 26).setValue(payload.last_error);
    devices.getRange(deviceRow, 27).setValue(now);
    devices.getRange(deviceRow, 28).setValue(payload.vowifi_state);
    devices.getRange(deviceRow, 29).setValue(now);
    devices.getRange(deviceRow, 38).setValue(payload.last_error === 'SIN ERROR' ? '' : payload.last_error);

    if (terminalRow) {
      updateTerminalTelemetry_(
        terminal,
        terminalRow,
        payload,
        now,
        directive.commandId ? 'PENDIENTE ' + directive.commandId : 'SIN ORDEN PENDIENTE'
      );
    }
    appendTelemetry_(request.device_id, payload, now);
    audit_('INFO', 'TELEMETRY_OK', request.device_id);
    const data = {
      telemetry_accepted: true,
      received_at_ms: now.getTime(),
      approval_state: approvalState,
      commands_enabled: commandsEnabled,
      mode: directive.mode,
      mode_revision: directive.revision,
      command_id: directive.commandId
    };
    if (approvalState === 'APPROVED' && terminalRow) {
      data.config_snapshot = buildConfigSnapshot_(request.device_id, {
        terminal: String(terminal.getRange(terminalRow, 2).getValue()),
        section: String(terminal.getRange(terminalRow, 3).getValue()),
        profileId: String(terminal.getRange(terminalRow, 4).getValue() || 'PENDIENTE_SEGURO'),
        configRevision: Math.max(0, Number(devices.getRange(deviceRow, 37).getValue()) || 0),
        deviceRow: deviceRow
      });
      const pilotOta = resolvePilotOtaAssignment_(request.device_id, now);
      if (pilotOta) data.pilot_ota = pilotOta;
    }
    return signedResponse_(request.device_id, request.nonce, data, secret);
  } finally {
    lock.releaseLock();
  }
}

function resolvePilotOtaAssignment_(deviceId, now) {
  const sheet = sheet_(MDM.sheets.otaAssignments);
  const headers = [
    'assignment_id', 'device_id', 'status', 'version_code', 'version_name',
    'apk_url', 'sha256', 'size_bytes', 'issued_at', 'expires_at',
    'last_delivered_at', 'delivery_count', 'last_error'
  ];
  ensureHeader_(sheet, headers);
  const lastRow = sheet.getLastRow();
  if (lastRow < 2) return null;
  const rows = sheet.getRange(2, 1, lastRow - 1, headers.length).getValues();
  for (let index = rows.length - 1; index >= 0; index -= 1) {
    const row = rows[index];
    if (String(row[1]) !== deviceId || String(row[2]).trim().toUpperCase() !== 'ACTIVE') continue;
    const sheetRow = index + 2;
    const assignmentId = String(row[0] || '').trim();
    const versionCode = Math.floor(Number(row[3]));
    const versionName = String(row[4] || '').trim();
    const apkUrl = String(row[5] || '').trim();
    const sha256 = String(row[6] || '').trim().toLowerCase();
    const sizeBytes = Math.floor(Number(row[7]));
    const expiresAt = row[9] instanceof Date ? row[9].getTime() : new Date(row[9]).getTime();
    const valid = /^[A-Za-z0-9._-]{8,100}$/.test(assignmentId) &&
      Number.isFinite(versionCode) && versionCode > 0 && versionName.length > 0 &&
      /^https:\/\/github\.com\/GrupoTGT\/actualizaciones-launcher\/releases\/download\/[A-Za-z0-9._-]+\/[A-Za-z0-9._-]+\.apk$/.test(apkUrl) &&
      /^[0-9a-f]{64}$/.test(sha256) && Number.isFinite(sizeBytes) && sizeBytes > 0 &&
      Number.isFinite(expiresAt) && expiresAt > now.getTime();
    if (!valid) {
      sheet.getRange(sheetRow, 13).setValue('INVALID_OR_EXPIRED_ASSIGNMENT');
      audit_('WARNING', 'PILOT_OTA_REJECTED', deviceId + ' assignment=' + assignmentId);
      return null;
    }
    sheet.getRange(sheetRow, 11).setValue(now);
    sheet.getRange(sheetRow, 12).setValue(Math.max(0, Number(row[11]) || 0) + 1);
    sheet.getRange(sheetRow, 13).setValue('');
    audit_('INFO', 'PILOT_OTA_DELIVERED', deviceId + ' assignment=' + assignmentId);
    return {
      assignment_id: assignmentId,
      device_id: deviceId,
      version_code: versionCode,
      version_name: versionName,
      apk_url: apkUrl,
      sha256: sha256,
      size_bytes: sizeBytes,
      expires_at_ms: expiresAt
    };
  }
  return null;
}

function normalizedTelemetry_(payload) {
  return {
    app_version: safeCellText_(payload.app_version, 'NO DISPONIBLE', 80),
    model: safeCellText_(payload.model, 'NO DISPONIBLE', 100),
    android: safeCellText_(payload.android, 'NO DISPONIBLE', 80),
    battery_percent: safeScalar_(payload.battery_percent),
    charging: safeScalar_(payload.charging),
    ip: safeCellText_(payload.ip, 'NO DISPONIBLE', 64),
    network_transport: safeCellText_(payload.network_transport, 'NO DISPONIBLE', 40),
    wifi_connected: payload.wifi_connected === true,
    ssid: safeCellText_(payload.ssid, 'NO DISPONIBLE', 80),
    wifi_rssi_dbm: safeScalar_(payload.wifi_rssi_dbm),
    internet_validated: payload.internet_validated === true,
    mobile_data_connected: payload.mobile_data_connected === true,
    airplane_mode: safeScalar_(payload.airplane_mode),
    brightness_percent: safeScalar_(payload.brightness_percent),
    volume_percent: safeScalar_(payload.volume_percent),
    device_owner: payload.device_owner === true,
    lock_task_state: safeCellText_(payload.lock_task_state, 'NO DISPONIBLE', 32),
    uptime_ms: safeNonNegativeNumber_(payload.uptime_ms),
    storage_available_bytes: safeNonNegativeNumber_(payload.storage_available_bytes),
    storage_total_bytes: safeNonNegativeNumber_(payload.storage_total_bytes),
    desired_mode: safeMode_(payload.desired_mode),
    desired_mode_revision: safeRevision_(payload.desired_mode_revision),
    applied_mode: safeMode_(payload.applied_mode),
    applied_mode_revision: safeRevision_(payload.applied_mode_revision),
    transition_phase: safeCellText_(payload.transition_phase, 'NO DISPONIBLE', 60),
    last_error: safeCellText_(payload.last_error, 'SIN ERROR', 240),
    agenda_status: safeCellText_(payload.agenda_status, 'NO DISPONIBLE', 60),
    agenda_contacts: safeNonNegativeNumber_(payload.agenda_contacts),
    configured_apps: safeTextArray_(payload.configured_apps),
    installed_configured_apps: safeTextArray_(payload.installed_configured_apps),
    telephony_capable: payload.telephony_capable === true,
    vowifi_state: safeCellText_(payload.vowifi_state, 'NO VERIFICABLE', 60)
  };
}

function resolveManagedDirective_(deviceId, devices, deviceRow, terminals, terminalRow, now) {
  const requestedMode = safeMode_(terminals.getRange(terminalRow, 5).getValue());
  const storedMode = safeMode_(devices.getRange(deviceRow, 34).getValue());
  let revision = Math.max(0, Number(devices.getRange(deviceRow, 36).getValue()) || 0);
  let commandId = findPendingModeCommand_(deviceId, revision);
  if (requestedMode !== storedMode) {
    revision += 1;
    commandId = deviceId + '-MODE-' + revision;
    devices.getRange(deviceRow, 34).setValue(requestedMode);
    devices.getRange(deviceRow, 36).setValue(revision);
    enqueueModeCommand_(commandId, deviceId, revision, requestedMode, now);
    audit_('INFO', 'MODE_COMMAND_CREATED', deviceId + ' rev=' + revision + ' mode=' + requestedMode);
  } else if (!commandId && safeMode_(devices.getRange(deviceRow, 35).getValue()) !== requestedMode) {
    revision += 1;
    commandId = deviceId + '-MODE-' + revision;
    devices.getRange(deviceRow, 36).setValue(revision);
    enqueueModeCommand_(commandId, deviceId, revision, requestedMode, now);
    audit_('INFO', 'MODE_COMMAND_REISSUED', deviceId + ' rev=' + revision + ' mode=' + requestedMode);
  }
  return { mode: requestedMode, revision: revision, commandId: commandId };
}

function enqueueModeCommand_(commandId, deviceId, revision, mode, now) {
  const sheet = sheet_(MDM.sheets.commands);
  const headers = [
    'command_id', 'device_id', 'revision', 'issued_at', 'expires_at', 'action',
    'desired_mode', 'status', 'ack_at', 'applied_mode', 'applied_revision', 'error'
  ];
  ensureHeader_(sheet, headers);
  if (findRow_(sheet, 1, commandId, 2)) return;
  sheet.appendRow([
    commandId, deviceId, revision, now, new Date(now.getTime() + 24 * 60 * 60 * 1000),
    'SET_MANAGED_MODE', mode, 'PENDING_ACK', '', '', '', ''
  ]);
}

function findPendingModeCommand_(deviceId, revision) {
  const sheet = sheet_(MDM.sheets.commands);
  ensureHeader_(sheet, [
    'command_id', 'device_id', 'revision', 'issued_at', 'expires_at', 'action',
    'desired_mode', 'status', 'ack_at', 'applied_mode', 'applied_revision', 'error'
  ]);
  const lastRow = sheet.getLastRow();
  if (lastRow < 2) return '';
  const rows = sheet.getRange(2, 1, lastRow - 1, 12).getValues();
  for (let i = rows.length - 1; i >= 0; i -= 1) {
    if (String(rows[i][1]) !== deviceId || Number(rows[i][2]) !== revision ||
        String(rows[i][7]) !== 'PENDING_ACK') continue;
    const expiresAt = rows[i][4] instanceof Date ? rows[i][4].getTime() : new Date(rows[i][4]).getTime();
    if (Number.isFinite(expiresAt) && expiresAt < Date.now()) {
      sheet.getRange(i + 2, 8).setValue('EXPIRED');
      continue;
    }
    return String(rows[i][0]);
  }
  return '';
}

function acknowledgeAppliedMode_(deviceId, payload, now) {
  if (payload.applied_mode_revision < 0) return;
  const sheet = sheet_(MDM.sheets.commands);
  ensureHeader_(sheet, [
    'command_id', 'device_id', 'revision', 'issued_at', 'expires_at', 'action',
    'desired_mode', 'status', 'ack_at', 'applied_mode', 'applied_revision', 'error'
  ]);
  const lastRow = sheet.getLastRow();
  if (lastRow < 2) return;
  const rows = sheet.getRange(2, 1, lastRow - 1, 12).getValues();
  for (let i = rows.length - 1; i >= 0; i -= 1) {
    const row = rows[i];
    if (String(row[1]) !== deviceId || Number(row[2]) !== payload.applied_mode_revision ||
        String(row[7]) !== 'PENDING_ACK') continue;
    const commandRow = i + 2;
    const expectedMode = safeMode_(row[6]);
    if (expectedMode !== payload.applied_mode) return;
    sheet.getRange(commandRow, 8, 1, 4).setValues([[
      'ACK_APPLIED', now, payload.applied_mode, payload.applied_mode_revision
    ]]);
    appendModeAck_(String(row[0]), deviceId, payload, now);
    audit_('INFO', 'MODE_COMMAND_ACK', deviceId + ' rev=' + payload.applied_mode_revision);
    return;
  }
}

function appendModeAck_(commandId, deviceId, payload, now) {
  const sheet = sheet_(MDM.sheets.acks);
  ensureHeader_(sheet, [
    'ack_at', 'command_id', 'device_id', 'status', 'applied_mode',
    'applied_revision', 'transition_phase', 'last_error'
  ]);
  if (findRow_(sheet, 2, commandId, 2)) return;
  sheet.appendRow([
    now, commandId, deviceId, 'ACK_APPLIED', payload.applied_mode,
    payload.applied_mode_revision, payload.transition_phase, payload.last_error
  ]);
}

function updateTerminalTelemetry_(sheet, row, payload, now, commandState) {
  sheet.getRange(row, 7, 1, 18).setValues([[
    'TELEMETRÍA REAL',
    payload.internet_validated ? 'CONECTADO' : 'SIN INTERNET VALIDADO',
    '0 min',
    payload.battery_percent,
    payload.charging,
    payload.wifi_connected,
    payload.internet_validated,
    payload.wifi_rssi_dbm,
    payload.mobile_data_connected,
    payload.airplane_mode,
    payload.vowifi_state,
    'NO VERIFICABLE',
    payload.device_owner,
    payload.lock_task_state,
    payload.app_version,
    payload.last_error,
    commandState,
    now
  ]]);
}

function appendTelemetry_(deviceId, payload, now) {
  const sheet = sheet_(MDM.sheets.telemetry);
  const headers = [
    'received_at', 'device_id', 'app_version', 'model', 'android', 'battery_percent',
    'charging', 'ip', 'network_transport', 'wifi_connected', 'ssid', 'wifi_rssi_dbm',
    'internet_validated', 'mobile_data_connected', 'airplane_mode', 'brightness_percent',
    'volume_percent', 'device_owner', 'lock_task_state', 'uptime_ms',
    'storage_available_bytes', 'storage_total_bytes', 'desired_mode',
    'desired_mode_revision', 'applied_mode', 'applied_mode_revision', 'transition_phase',
    'last_error', 'agenda_status', 'agenda_contacts', 'configured_apps',
    'installed_configured_apps', 'telephony_capable', 'vowifi_state'
  ];
  ensureHeader_(sheet, headers);
  sheet.appendRow([
    now, deviceId, payload.app_version, payload.model, payload.android,
    payload.battery_percent, payload.charging, payload.ip, payload.network_transport,
    payload.wifi_connected, payload.ssid, payload.wifi_rssi_dbm,
    payload.internet_validated, payload.mobile_data_connected, payload.airplane_mode,
    payload.brightness_percent, payload.volume_percent, payload.device_owner,
    payload.lock_task_state, payload.uptime_ms, payload.storage_available_bytes,
    payload.storage_total_bytes, payload.desired_mode, payload.desired_mode_revision,
    payload.applied_mode, payload.applied_mode_revision, payload.transition_phase,
    payload.last_error, payload.agenda_status, payload.agenda_contacts,
    payload.configured_apps.join(','), payload.installed_configured_apps.join(','),
    payload.telephony_capable, payload.vowifi_state
  ]);
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
    const fingerprint = sha256Hex_(Utilities.base64DecodeWebSafe(proposedSecret)).substring(0, 24).toUpperCase();
    const registration = upsertPendingRegistration_(request.device_id, payload, fingerprint);
    if (registration.approvalState === 'APPROVED' && !currentSecret) {
      properties.setProperty(secretKey, proposedSecret);
    }
    if (registration.approvalState === 'APPROVED' && registration.commandsEnabled) {
      const directive = resolveManagedDirective_(
        request.device_id,
        sheet_(MDM.sheets.devices),
        registration.deviceRow,
        sheet_(MDM.sheets.terminals),
        registration.terminalRow,
        new Date()
      );
      registration.mode = directive.mode;
      registration.modeRevision = directive.revision;
    }
    const data = {
      approval_state: registration.approvalState,
      commands_enabled: registration.commandsEnabled,
      credential_fingerprint: fingerprint,
      terminal: registration.terminal,
      profile_id: registration.approvalState === 'APPROVED' ? registration.profileId : 'PENDIENTE_SEGURO',
      mode: registration.approvalState === 'APPROVED' ? safeMode_(registration.mode) : 'BLINDADO',
      mode_revision: registration.modeRevision
    };
    if (registration.approvalState === 'APPROVED') {
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
  const approvedFingerprint = String(devices.getRange(deviceRow, 32).getValue() || '').trim().toUpperCase();
  if (currentAuth === 'APPROVED' && !approvedFingerprint) {
    throw bridgeError_('APPROVAL_FINGERPRINT_REQUIRED', 'Approved devices require a verified credential fingerprint.');
  }
  if (currentAuth === 'APPROVED' && approvedFingerprint && approvedFingerprint !== fingerprint) {
    throw bridgeError_('CREDENTIAL_APPROVAL_MISMATCH', 'Credential does not match the approved fingerprint.');
  }
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
  if (currentAuth !== 'APPROVED' || !approvedFingerprint) {
    devices.getRange(deviceRow, 32).setValue(fingerprint);
  }
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
    deviceRow: deviceRow,
    terminalRow: row
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
    settings: {}
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

function safeCellText_(value, fallback, maxLength) {
  const text = safeText_(value, fallback, maxLength);
  return /^[=+\-@]/.test(text) ? "'" + text : text;
}

function safeScalar_(value) {
  if (typeof value === 'boolean') return value;
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  return safeCellText_(value, 'NO DISPONIBLE', 80);
}

function safeNonNegativeNumber_(value) {
  const number = Number(value);
  return Number.isFinite(number) && number >= 0 ? number : 0;
}

function safeRevision_(value) {
  const number = Number(value);
  return Number.isFinite(number) && number >= -1 ? Math.floor(number) : -1;
}

function safeTextArray_(value) {
  if (!Array.isArray(value)) return [];
  return value.slice(0, 200).map(function (item) {
    return safeCellText_(item, '', 160);
  }).filter(function (item) { return item.length > 0; });
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
