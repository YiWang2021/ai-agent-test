const { test, afterEach } = require('node:test');
const assert = require('node:assert/strict');
const client = require('../../main/resources/static/client.js');
const originalFetch = global.fetch;
afterEach(() => { global.fetch = originalFetch; });

test('typed requests use the selected device without overriding an explicit target', () => {
  assert.equal(client.devicePrompt('查询电量', 'device-002'), '设备 device-002：查询电量');
  assert.equal(client.devicePrompt('查 device-001', 'device-002'), '查 device-001');
  assert.equal(client.devicePrompt('E03是什么', ''), 'E03是什么');
});
test('read requests preserve credentials and parse JSON', async () => {
  global.fetch = async (url, options) => {
    assert.equal(url, '/api/devices');
    assert.equal(options.headers['X-API-Key'], 'test-key');
    assert.ok(options.signal);
    return new Response('[{"id":"device-002"}]', { status: 200 });
  };
  assert.deepEqual(await client.json('/api/devices', { headers: { 'X-API-Key': 'test-key' } }), [{ id: 'device-002' }]);
});
test('busy responses expose diagnostic trace and retry advice without automatic retries', async () => {
  let calls = 0;
  global.fetch = async () => { calls++; return new Response('', { status: 429, headers: { 'X-Trace-Id': 'trace-123', 'Retry-After': '5' } }); };
  await assert.rejects(client.json('/api/chat'), /TRACE trace-123/);
  assert.equal(calls, 1);
});
test('unauthorized responses tell the user to configure their account', async () => {
  global.fetch = async () => new Response('', { status: 401 });
  await assert.rejects(client.json('/api/devices'), /连接设置/);
});
test('network and malformed JSON failures propagate for explicit user retry', async () => {
  global.fetch = async () => { throw new TypeError('offline'); };
  await assert.rejects(client.json('/api/devices'), /offline/);
  global.fetch = async () => new Response('not json');
  await assert.rejects(client.json('/api/devices'), SyntaxError);
});
