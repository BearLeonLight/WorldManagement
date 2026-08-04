const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const test = require('node:test')

const {
  chooseConnectionMode,
  runWithViaFallback,
  shouldUseViaFallback
} = require('./protocol-strategy.cjs')
const { resolveCheckedArtifact } = require('./via-artifacts.cjs')

const testedVersions = ['1.21.8', '1.21.9', '1.21.11']
const protocols = new Map([
  ['1.21.8', 772],
  ['1.21.9', 773],
  ['1.21.11', 774]
])
const protocolForVersion = version => protocols.get(version)

test('uses the Paper native protocol when Mineflayer tests the reported version', () => {
  const decision = chooseConnectionMode(
    { name: '1.21.11', protocol: 774 },
    testedVersions,
    protocolForVersion
  )

  assert.deepEqual(decision, {
    mode: 'native',
    clientVersion: '1.21.11',
    fallbackClientVersion: '1.21.11',
    serverVersion: '1.21.11',
    serverProtocol: 774
  })
})

test('matches a tested Mineflayer version by protocol when the status name differs', () => {
  const decision = chooseConnectionMode(
    { name: 'Paper 1.21.11', protocol: 774 },
    testedVersions,
    protocolForVersion
  )

  assert.equal(decision.mode, 'native')
  assert.equal(decision.clientVersion, '1.21.11')
})

test('uses the latest tested Mineflayer version only when Paper is unsupported', () => {
  const decision = chooseConnectionMode(
    { name: '26.2', protocol: 1073742181 },
    testedVersions,
    protocolForVersion
  )

  assert.deepEqual(decision, {
    mode: 'via-fallback',
    clientVersion: '1.21.11',
    fallbackClientVersion: '1.21.11',
    serverVersion: '26.2',
    serverProtocol: 1073742181
  })
})

test('applies an explicit client override only to Via fallback', () => {
  const nativeDecision = chooseConnectionMode(
    { name: '1.21.9', protocol: 773 },
    testedVersions,
    protocolForVersion,
    '1.21.8'
  )
  const fallbackDecision = chooseConnectionMode(
    { name: '26.2', protocol: 1073742181 },
    testedVersions,
    protocolForVersion,
    '1.21.8'
  )

  assert.equal(nativeDecision.clientVersion, '1.21.9')
  assert.equal(fallbackDecision.clientVersion, '1.21.8')
})

test('pre-spawn protocol compatibility errors allow one Via fallback', () => {
  assert.equal(shouldUseViaFallback(Object.assign(
    new Error('No data available for version 26.2'),
    { phase: 'pre-spawn' }
  )), true)
  assert.equal(shouldUseViaFallback(Object.assign(
    new Error('Unsupported protocol version'),
    { phase: 'pre-spawn' }
  )), true)
  assert.equal(shouldUseViaFallback(Object.assign(
    new Error('Read error for packet login'),
    { phase: 'pre-spawn' }
  )), true)
})

test('network, post-spawn, and command errors never allow Via fallback', () => {
  assert.equal(shouldUseViaFallback(Object.assign(
    new Error('Timed out waiting for Mineflayer spawn.'),
    { phase: 'pre-spawn' }
  )), false)
  assert.equal(shouldUseViaFallback(Object.assign(
    new Error('Unsupported protocol version'),
    { phase: 'post-spawn' }
  )), false)
  assert.equal(shouldUseViaFallback(Object.assign(
    new Error('Timed out waiting for world create.'),
    { phase: 'post-spawn' }
  )), false)
})

test('native success never prepares or runs Via', async () => {
  const calls = []
  const result = await runWithViaFallback({
    decision: { mode: 'native', clientVersion: '1.21.11' },
    runNative: async () => {
      calls.push('native')
      return 'native-result'
    },
    prepareVia: async () => calls.push('prepare-via'),
    runVia: async () => calls.push('via')
  })

  assert.equal(result, 'native-result')
  assert.deepEqual(calls, ['native'])
})

test('unsupported Paper prepares Via without attempting native login', async () => {
  const calls = []
  const result = await runWithViaFallback({
    decision: { mode: 'via-fallback', clientVersion: '1.21.11' },
    runNative: async () => calls.push('native'),
    prepareVia: async () => calls.push('prepare-via'),
    runVia: async () => {
      calls.push('via')
      return 'via-result'
    }
  })

  assert.equal(result, 'via-result')
  assert.deepEqual(calls, ['prepare-via', 'via'])
})

test('a native protocol compatibility error prepares Via exactly once', async () => {
  const calls = []
  let viaClientVersion
  const compatibilityError = Object.assign(
    new Error('Unsupported protocol version'),
    { phase: 'pre-spawn' }
  )

  await runWithViaFallback({
    decision: {
      mode: 'native',
      clientVersion: '1.21.9',
      fallbackClientVersion: '1.21.11'
    },
    runNative: async () => {
      calls.push('native')
      throw compatibilityError
    },
    prepareVia: async () => calls.push('prepare-via'),
    runVia: async decision => {
      calls.push('via')
      viaClientVersion = decision.clientVersion
    }
  })

  assert.deepEqual(calls, ['native', 'prepare-via', 'via'])
  assert.equal(viaClientVersion, '1.21.11')
})

test('a native post-spawn failure is reported without preparing Via', async () => {
  const calls = []
  const commandError = Object.assign(
    new Error('Timed out waiting for world create.'),
    { phase: 'post-spawn' }
  )

  await assert.rejects(runWithViaFallback({
    decision: { mode: 'native', clientVersion: '1.21.11' },
    runNative: async () => {
      calls.push('native')
      throw commandError
    },
    prepareVia: async () => calls.push('prepare-via'),
    runVia: async () => calls.push('via')
  }), commandError)

  assert.deepEqual(calls, ['native'])
})

test('a valid cached Via artifact is reused without a network request', async (context) => {
  const cacheDirectory = fs.mkdtempSync(path.join(os.tmpdir(), 'wm-via-cache-'))
  context.after(() => fs.rmSync(cacheDirectory, { recursive: true, force: true }))
  const contents = Buffer.from('cached ViaVersion artifact')
  const fileName = 'ViaVersion-test.jar'
  const cachedFile = path.join(cacheDirectory, fileName)
  fs.writeFileSync(cachedFile, contents)
  let fetchCalls = 0

  const result = await resolveCheckedArtifact({
    fileName,
    cacheDirectory,
    downloadUrl: 'https://example.invalid/ViaVersion-test.jar',
    expectedHash: crypto.createHash('sha256').update(contents).digest('hex'),
    hashAlgorithm: 'sha256',
    fetchImpl: async () => {
      fetchCalls++
      throw new Error('cache hit must not fetch')
    }
  })

  assert.equal(result, cachedFile)
  assert.equal(fetchCalls, 0)
})

test('a Via artifact checksum mismatch removes the download and target files', async (context) => {
  const cacheDirectory = fs.mkdtempSync(path.join(os.tmpdir(), 'wm-via-download-'))
  context.after(() => fs.rmSync(cacheDirectory, { recursive: true, force: true }))
  const fileName = 'ViaBackwards-test.jar'

  await assert.rejects(resolveCheckedArtifact({
    fileName,
    cacheDirectory,
    downloadUrl: 'https://example.invalid/ViaBackwards-test.jar',
    expectedHash: crypto.createHash('sha256').update('expected').digest('hex'),
    hashAlgorithm: 'sha256',
    fetchImpl: async () => new Response('unexpected', { status: 200 })
  }), /SHA-256 mismatch/)

  assert.equal(fs.existsSync(path.join(cacheDirectory, fileName)), false)
  assert.equal(fs.existsSync(path.join(cacheDirectory, `${fileName}.part`)), false)
})

test('an artifact download is aborted at its deadline and leaves no partial files', async (context) => {
  const cacheDirectory = fs.mkdtempSync(path.join(os.tmpdir(), 'wm-via-timeout-'))
  context.after(() => fs.rmSync(cacheDirectory, { recursive: true, force: true }))
  const fileName = 'ViaVersion-timeout.jar'

  await assert.rejects(resolveCheckedArtifact({
    fileName,
    cacheDirectory,
    downloadUrl: 'https://example.invalid/ViaVersion-timeout.jar',
    expectedHash: crypto.createHash('sha256').update('expected').digest('hex'),
    hashAlgorithm: 'sha256',
    timeoutMilliseconds: 20,
    fetchImpl: async (_url, { signal }) => new Promise((resolve, reject) => {
      signal.addEventListener('abort', () => reject(signal.reason), { once: true })
    })
  }), error => error.name === 'TimeoutError')

  assert.equal(fs.existsSync(path.join(cacheDirectory, fileName)), false)
  assert.equal(fs.existsSync(path.join(cacheDirectory, `${fileName}.part`)), false)
})

test('a valid cached SHA-512 artifact is reused without a network request', async (context) => {
  const cacheDirectory = fs.mkdtempSync(path.join(os.tmpdir(), 'wm-luckperms-cache-'))
  context.after(() => fs.rmSync(cacheDirectory, { recursive: true, force: true }))
  const contents = Buffer.from('cached LuckPerms artifact')
  const fileName = 'LuckPerms-Bukkit-test.jar'
  const cachedFile = path.join(cacheDirectory, fileName)
  fs.writeFileSync(cachedFile, contents)

  const result = await resolveCheckedArtifact({
    fileName,
    cacheDirectory,
    downloadUrl: 'https://example.invalid/LuckPerms-Bukkit-test.jar',
    expectedHash: crypto.createHash('sha512').update(contents).digest('hex'),
    hashAlgorithm: 'sha512',
    fetchImpl: async () => { throw new Error('cache hit must not fetch') }
  })

  assert.equal(result, cachedFile)
})