const fs = require('node:fs')
const path = require('node:path')
const { spawn } = require('node:child_process')
const { finished } = require('node:stream/promises')
const assert = require('node:assert/strict')
const YAML = require('yaml')
const { assertCommandMatchesPath, assertRuntimeCoverage } = require('../../runtime-coverage.cjs')
const { resolveBuildChild } = require('../../build-child-path.cjs')

const PROJECT_ROOT = path.resolve(__dirname, '..', '..', '..')
const SHUTDOWN_FAILURES = ['zip file error', 'I/O shutdown failed', 'did not drain']
const SHUTDOWN_COMPLETE = 'WorldManagement terminal shutdown complete.'
let passedCommands = 0
const coveredPaths = new Set()

async function main () {
  const configuration = loadConfiguration()
  fs.rmSync(configuration.serverRoot, { recursive: true, force: true })
  prepareServer(configuration)

  const consoleLogPath = path.join(configuration.serverRoot, 'console.log')
  const paperLogPath = path.join(configuration.serverRoot, 'logs', 'latest.log')
  const publishedLogPath = path.join(configuration.serverRoot, 'latest.log')
  const logFile = fs.createWriteStream(consoleLogPath, { encoding: 'utf8' })
  const paper = spawn(configuration.javaExecutable, [
    '-Xms512M', '-Xmx512M',
    '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
    '-jar', 'paper.jar', '--nogui'
  ], { cwd: configuration.serverRoot, stdio: ['pipe', 'pipe', 'pipe'] })
  paper.stdout.pipe(logFile, { end: false })
  paper.stderr.pipe(logFile, { end: false })

  try {
    await waitForLog(consoleLogPath, '[WorldManagement] Enabled WorldManagement', 120000, 'Paper startup')
    await runConsoleMatrix(paper, paperLogPath, configuration.serverRoot)
    paper.stdin.write('stop\n')
    await waitForExit(paper, 30000)
  } finally {
    if (paper.exitCode === null) paper.kill()
    logFile.end()
    await finished(logFile)
  }

  if (paper.exitCode !== 0) throw new Error(`Paper exited with code ${paper.exitCode}.`)
  fs.copyFileSync(paperLogPath, publishedLogPath)
  const shutdownLog = normalizedLog(publishedLogPath)
  if (!shutdownLog.includes(SHUTDOWN_COMPLETE)) {
    throw new Error('Paper exited before WorldManagement terminal shutdown completed.')
  }
  const shutdownFailure = SHUTDOWN_FAILURES.find(marker => shutdownLog.includes(marker))
  if (shutdownFailure) throw new Error(`Paper shutdown reported '${shutdownFailure}'.`)
  const coveredLeaves = assertRuntimeCoverage('CONSOLE_RUNTIME', coveredPaths)
  console.log(`Verified ${passedCommands} console command outcomes across ${coveredLeaves} console runtime leaves without a player; log: ${publishedLogPath}`)
}

function loadConfiguration () {
  return {
    paperJar: requiredFile('WM_PAPER_JAR'),
    pluginJar: requiredFile('WM_PLUGIN_JAR'),
    e2eSupportJar: requiredFile('WM_E2E_SUPPORT_JAR'),
    javaExecutable: process.env.WM_JAVA_EXECUTABLE || 'java',
    serverRoot: resolveServerRoot()
  }
}

function resolveServerRoot () {
  return resolveBuildChild(process.env.WM_CONSOLE_TEST_SERVER_DIR, 'console-command-test', 'WM_CONSOLE_TEST_SERVER_DIR')
}

function prepareServer (configuration) {
  const plugins = path.join(configuration.serverRoot, 'plugins')
  const pluginData = path.join(plugins, 'WorldManagement')
  fs.mkdirSync(pluginData, { recursive: true })
  fs.copyFileSync(configuration.paperJar, path.join(configuration.serverRoot, 'paper.jar'))
  fs.copyFileSync(configuration.pluginJar, path.join(plugins, path.basename(configuration.pluginJar)))
  fs.copyFileSync(configuration.e2eSupportJar, path.join(plugins, path.basename(configuration.e2eSupportJar)))
  fs.writeFileSync(path.join(configuration.serverRoot, 'eula.txt'), 'eula=true\n')
  fs.writeFileSync(path.join(configuration.serverRoot, 'server.properties'), [
    'server-port=0',
    'online-mode=false',
    'spawn-protection=0',
    'view-distance=2',
    'simulation-distance=2',
    'generate-structures=false',
    'spawn-monsters=false',
    ''
  ].join('\n'))
  fs.writeFileSync(path.join(pluginData, 'config.yml'), [
    'schema-version: 1',
    'storage:',
    '  provider: YAML',
    'storage-migration:',
    '  targets:',
    '    sqlite:',
    '      jdbc-url: "jdbc:sqlite:plugins/WorldManagement/migration-target.db"',
    ''
  ].join('\n'))
  fs.writeFileSync(path.join(pluginData, 'commands.yml'), [
    'commands:',
    '  root-aliases: [worldmanager]',
    '  modules:',
    '    warp:',
    '      aliases: [wmwarp]',
    '    ownership:',
    '      aliases: [wmown]',
    '    storage:',
    '      aliases: [wmstore]',
    ''
  ].join('\n'))
  const worlds = path.join(pluginData, 'worlds')
  fs.mkdirSync(worlds, { recursive: true })
  writeMetadataFixture(worlds, metadataFixture('warpfixture', {
    warps: { obsolete: warpFixture('obsolete') }
  }))
  writeMetadataFixture(worlds, metadataFixture('syncfixture', {
    identity: identityFixture('syncfixture', '11111111-1111-1111-1111-111111111111', 41, true),
    state: 'SYNC_PENDING',
    pending: identityFixture('syncfixture', '11111111-1111-1111-1111-111111111111', 84, false)
  }))
  writeMetadataFixture(worlds, metadataFixture('clearfixture', {
    identity: identityFixture('clearfixture', '22222222-2222-2222-2222-222222222222', 42, true),
    state: 'CONFLICT',
    pending: identityFixture('clearfixture', '33333333-3333-3333-3333-333333333333', 99, false),
    warps: { obsolete: warpFixture('obsolete') }
  }))
  writeMetadataFixture(worlds, metadataFixture('keepfixture', {
    identity: identityFixture('keepfixture', '44444444-4444-4444-4444-444444444444', 42, true),
    state: 'CONFLICT',
    pending: identityFixture('keepfixture', '55555555-5555-5555-5555-555555555555', 99, false),
    warps: { preserved: warpFixture('preserved') }
  }))
  writeMetadataFixture(worlds, metadataFixture('abandonfixture', {
    identity: identityFixture('abandonfixture', '66666666-6666-6666-6666-666666666666', 42, true),
    state: 'CONFLICT',
    pending: identityFixture('abandonfixture', '77777777-7777-7777-7777-777777777777', 99, false)
  }))
  writeMetadataFixture(worlds, metadataFixture('displayfixture', {
    displayName: '<gold>Initial Fixture</gold>'
  }))
}

async function runConsoleMatrix (paper, logPath, serverRoot) {
  await command(paper, logPath, 'wm list', '受管世界：', 'wm list')
  await command(paper, logPath, 'wm list detached', '目前沒有已停止管理的世界。', 'wm list detached')
  await command(paper, logPath, 'worldmanager list', '受管世界：', 'wm list')
  await command(paper, logPath, 'wmwarp list warpfixture', '傳送點：obsolete', 'wm warp list <world>')
  await command(paper, logPath, 'wm warp delete warpfixture obsolete', '傳送點 obsolete 已刪除', 'wm warp delete <world> <name>')
  assert.equal(readMetadata(serverRoot, 'warpfixture').warps.obsolete, undefined, 'Warp delete must persist an empty warp map.')

  await command(paper, logPath, 'wm identity show syncfixture', 'SYNC_PENDING', 'wm identity show <world>')
  await command(paper, logPath, 'wm identity sync syncfixture', 'pending identity snapshot已同步。', 'wm identity sync <world>')
  assertIdentity(readMetadata(serverRoot, 'syncfixture'), {
    state: 'VERIFIED', uuid: '11111111-1111-1111-1111-111111111111', seed: 84, structures: false, pending: false
  })
  await assertAuditActions(serverRoot, 'syncfixture', [
    'world.identity.sync.admission', 'world.identity.sync.success'
  ])

  await command(
    paper, logPath, 'wm identity accept-replacement clearfixture confirm clear-warps',
    'Warp policy：clear-warps。', 'wm identity accept-replacement <world> confirm clear-warps'
  )
  const clearFixture = readMetadata(serverRoot, 'clearfixture')
  assertIdentity(clearFixture, {
    state: 'VERIFIED', uuid: '33333333-3333-3333-3333-333333333333', seed: 99, structures: false, pending: false
  })
  assert.deepEqual(clearFixture.warps, {}, 'clear-warps must remove every persisted warp.')
  await assertAuditActions(serverRoot, 'clearfixture', [
    'world.identity.accept-replacement.admission', 'world.identity.accept-replacement.success'
  ])

  await command(
    paper, logPath, 'wm identity accept-replacement keepfixture confirm keep-warps',
    'Warp policy：keep-warps。', 'wm identity accept-replacement <world> confirm keep-warps'
  )
  const keepFixture = readMetadata(serverRoot, 'keepfixture')
  assertIdentity(keepFixture, {
    state: 'VERIFIED', uuid: '55555555-5555-5555-5555-555555555555', seed: 99, structures: false, pending: false
  })
  assert.deepEqual(keepFixture.warps.preserved, {
    x: 0.5,
    y: 80,
    z: 0.5,
    yaw: 0,
    pitch: 0,
    visibility: 'PUBLIC',
    'trusted-players': [],
    'trusted-ranks': [],
    'required-permission': ''
  }, 'keep-warps must preserve persisted warp data.')
  await assertAuditActions(serverRoot, 'keepfixture', [
    'world.identity.accept-replacement.admission', 'world.identity.accept-replacement.success'
  ])

  await command(
    paper, logPath, 'wm identity abandon abandonfixture confirm',
    'metadata已轉為DETACHED', 'wm identity abandon <world> confirm'
  )
  const abandonFixture = readMetadata(serverRoot, 'abandonfixture')
  assert.equal(abandonFixture['management-state'], 'DETACHED', 'Identity abandon must detach metadata.')
  assert.equal(abandonFixture['desired-state'], 'UNLOADED', 'Identity abandon must not load a detached world.')
  assert.equal(abandonFixture.identity['verification-state'], 'CONFLICT', 'Identity abandon must retain the observed conflict.')
  assert.equal(abandonFixture.identity.pending.present, true, 'Identity abandon must retain the pending identity for recovery.')
  await assertAuditActions(serverRoot, 'abandonfixture', [
    'world.identity.abandon.admission', 'world.identity.abandon.success'
  ])

  await command(
    paper, logPath, 'wm display-name set displayfixture <gold>Console Fixture</gold>',
    '顯示名稱已設為 Console Fixture。', 'wm display-name set <world> <display-name...>'
  )
  assert.equal(readMetadata(serverRoot, 'displayfixture')['display-name'], '<gold>Console Fixture</gold>')
  await assertAuditActions(serverRoot, 'displayfixture', ['world.display-name.set'])
  await command(
    paper, logPath, 'wm display-name reset displayfixture',
    '顯示名稱已重設。', 'wm display-name reset <world>'
  )
  assert.equal(readMetadata(serverRoot, 'displayfixture')['display-name'], 'displayfixture')
  await assertAuditActions(serverRoot, 'displayfixture', ['world.display-name.reset'])

  await command(paper, logPath, 'wm adopt overworld', '世界 overworld 已加入管理', 'wm adopt <world>')

  await command(paper, logPath, 'wm create basic', '世界 basic 已建立並加入管理', 'wm create <world>')
  const basicStoragePaths = [
    path.join(serverRoot, 'world', 'dimensions', 'minecraft', 'basic'),
    path.join(serverRoot, 'basic')
  ]
  if (!basicStoragePaths.some(candidate =>
    fs.existsSync(path.join(candidate, 'level.dat')) || fs.existsSync(path.join(candidate, 'data'))
  )) {
    throw new Error('Paper did not create on-disk storage for basic world.')
  }
  await command(paper, logPath, 'wm create envonly NORMAL', '世界 envonly 已建立並加入管理', 'wm create <world> <environment>')
  await command(paper, logPath, 'wm create typeonly NORMAL FLAT', '世界 typeonly 已建立並加入管理', 'wm create <world> <environment> <world-type>')
  await command(paper, logPath, 'wm create imported NORMAL FLAT 12345', '世界 imported 已建立並加入管理', 'wm create <world> <environment> <world-type> <seed>')
  await command(paper, logPath, 'wm unload imported', '世界 imported 已unloaded', 'wm unload <world>')
  await command(paper, logPath, 'wm load imported', '世界 imported 已loaded', 'wm load <world>')
  await command(paper, logPath, 'wm unload imported', '世界 imported 已unloaded', 'wm unload <world>')
  await command(paper, logPath, 'wm remove imported', '世界 imported 已停止管理', 'wm remove <world>')
  await command(paper, logPath, 'wm remove imported purge confirm', '已永久清除已停止管理世界 imported', 'wm remove <world> purge confirm')
  await command(paper, logPath, 'wm import imported NORMAL', '世界 imported 已匯入並加入管理', 'wm import <world> <environment>')

  await command(paper, logPath, 'wm ownership owner remove overworld', '世界擁有者已更新', 'wm ownership owner remove <world>')
  await command(paper, logPath, 'wm ownership rank create overworld builder', '世界階級設定已更新', 'wm ownership rank create <world> <rank>')
  await command(paper, logPath, 'wm ownership rank perm overworld builder add BUILD', '世界階級設定已更新', 'wm ownership rank perm <world> <rank> <operation> <permission>')
  await command(paper, logPath, 'wm ownership rank perm overworld builder remove BUILD', '世界階級設定已更新', 'wm ownership rank perm <world> <rank> <operation> <permission>')
  await command(paper, logPath, 'wmown rank toggle overworld', '世界階級設定已更新', 'wm ownership rank toggle <world>')
  await command(paper, logPath, 'wm ownership rank delete overworld builder', '世界階級設定已更新', 'wm ownership rank delete <world> <rank>')
  await command(paper, logPath, 'wm ownership access overworld mode WHITELIST', '世界存取設定已更新', 'wm ownership access <world> <operation> <value>', false)
  await command(paper, logPath, 'wm ownership access overworld mode NONE', '世界存取設定已更新', 'wm ownership access <world> <operation> <value>', false)

  await command(paper, logPath, 'wm create archive', '世界 archive 已建立並加入管理', 'wm create <world>')
  await command(paper, logPath, 'wm unload archive', '世界 archive 已unloaded', 'wm unload <world>')
  await command(paper, logPath, 'wm remove archive', '世界 archive 已停止管理', 'wm remove <world>')
  await command(paper, logPath, 'wm list detached', 'archive', 'wm list detached')
  assert.equal(readMetadata(serverRoot, 'archive')['management-state'], 'DETACHED')
  await command(paper, logPath, 'wm manage archive', '世界 archive 已重新加入管理', 'wm manage <world>')
  await command(paper, logPath, 'wm remove archive', '世界 archive 已停止管理', 'wm remove <world>')
  await command(paper, logPath, 'wm remove archive purge confirm', '已永久清除已停止管理世界 archive', 'wm remove <world> purge confirm')

  await command(paper, logPath, 'wm delete basic confirm', '世界 basic 已完成存檔並卸載', 'wm delete <world> confirm')
  await command(paper, logPath, 'wm delete basic confirm', '世界 basic 已永久刪除', 'wm delete <world> confirm')
  if (fs.existsSync(path.join(serverRoot, 'plugins', 'WorldManagement', 'worlds', 'basic.yml'))) {
    throw new Error('Console delete left basic metadata behind.')
  }
  if (basicStoragePaths.some(candidate => fs.existsSync(candidate))) {
    throw new Error('Console delete left basic world storage behind.')
  }
  if (findDirectories(serverRoot, '.worldmanagement-quarantine').length > 0) {
    throw new Error('Console delete left a world quarantine behind.')
  }

  await command(paper, logPath, 'wmstore migrate INVALID SQLITE confirm', '未知的儲存供應者', 'wm storage migrate <source> <target> confirm')
  await command(paper, logPath, 'wm storage migrate YAML SQLITE confirm', '已遷移', 'wm storage migrate <source> <target> confirm')
  if (!fs.existsSync(path.join(serverRoot, 'plugins', 'WorldManagement', 'migration-target.db'))) {
    throw new Error('Console storage migration did not create the SQLite target.')
  }
}

async function command (paper, logPath, input, expected, commandPath, creditCoverage = true) {
  assertCommandMatchesPath(input, commandPath)
  const start = normalizedLog(logPath).length
  paper.stdin.write(`${input}\n`)
  await waitForLog(logPath, expected, 30000, input, start)
  passedCommands++
  if (creditCoverage) coveredPaths.add(commandPath)
}

function writeMetadataFixture (worldsDirectory, metadata) {
  fs.writeFileSync(path.join(worldsDirectory, `${metadata['world-id']}.yml`), YAML.stringify(metadata))
}

function metadataFixture (worldId, {
  displayName = worldId,
  identity = identityFixture(worldId, fixtureUuid(worldId), 42, true),
  state = 'VERIFIED',
  pending = undefined,
  warps = {}
} = {}) {
  if ((state === 'VERIFIED') !== (pending === undefined)) {
    throw new Error(`Fixture ${worldId} must only contain a pending identity while non-verified.`)
  }
  return {
    'schema-version': 1,
    'world-id': worldId,
    'display-name': displayName,
    identity: {
      accepted: identity,
      'verification-state': state,
      'lifecycle-capability': 'MANAGED',
      pending: pending === undefined ? { present: false } : { present: true, snapshot: pending }
    },
    creation: { 'requested-world-type': 'NONE' },
    'management-state': 'ACTIVE',
    'desired-state': 'UNLOADED',
    owner: 'server',
    version: 0,
    'rank-system': { enabled: false },
    'access-control': { mode: 'NONE', entries: [] },
    ranks: {
      OWNER: { 'display-name': 'World Owner', permissions: [] },
      GUEST: { 'display-name': 'Guest', permissions: [] }
    },
    players: {},
    warps
  }
}

function identityFixture (worldId, uuid, seed, generateStructures) {
  return {
    'paper-key': `minecraft:${worldId}`,
    'world-uuid': uuid,
    environment: 'NORMAL',
    seed,
    'generate-structures': generateStructures
  }
}

function warpFixture (name) {
  return {
    x: 0.5,
    y: 80,
    z: 0.5,
    yaw: 0,
    pitch: 0,
    visibility: 'PUBLIC',
    'trusted-players': [],
    'trusted-ranks': [],
    'required-permission': '',
    name
  }
}

function fixtureUuid (worldId) {
  const fixtures = {
    warpfixture: '00000000-0000-0000-0000-000000000001',
    displayfixture: '00000000-0000-0000-0000-000000000002'
  }
  const uuid = fixtures[worldId]
  if (uuid === undefined) throw new Error(`No fixture UUID is defined for ${worldId}.`)
  return uuid
}

function readMetadata (serverRoot, worldId) {
  const metadataFile = path.join(serverRoot, 'plugins', 'WorldManagement', 'worlds', `${worldId}.yml`)
  assert.ok(fs.existsSync(metadataFile), `Expected metadata file: ${metadataFile}`)
  const metadata = YAML.parse(fs.readFileSync(metadataFile, 'utf8'))
  assert.equal(metadata['schema-version'], 1, `${worldId} must remain schema 1 metadata.`)
  return metadata
}

function assertIdentity (metadata, { state, uuid, seed, structures, pending }) {
  assert.equal(metadata.identity['verification-state'], state)
  assert.equal(metadata.identity.accepted['world-uuid'], uuid)
  assert.equal(metadata.identity.accepted.seed, seed)
  assert.equal(metadata.identity.accepted['generate-structures'], structures)
  assert.equal(metadata.identity.pending.present, pending)
}

async function assertAuditActions (serverRoot, worldName, actions) {
  const auditPath = path.join(serverRoot, 'plugins', 'WorldManagement', 'audit.jsonl')
  await waitForCondition(() => {
    if (!fs.existsSync(auditPath)) return false
    const records = fs.readFileSync(auditPath, 'utf8').trim().split(/\r?\n/)
      .filter(Boolean)
      .map(line => JSON.parse(line))
    return actions.every(action => records.some(record => record.action === action && record.worldName === worldName))
  }, 30000, `audit actions for ${worldName}: ${actions.join(', ')}`)
}

async function waitForCondition (condition, timeoutMilliseconds, label) {
  const deadline = Date.now() + timeoutMilliseconds
  while (Date.now() < deadline) {
    if (condition()) return
    await new Promise(resolve => setTimeout(resolve, 50))
  }
  throw new Error(`Timed out waiting for ${label}.`)
}

async function waitForLog (logPath, expected, timeoutMilliseconds, label, start = 0) {
  const deadline = Date.now() + timeoutMilliseconds
  while (Date.now() < deadline) {
    if (fs.existsSync(logPath) && normalizedLog(logPath).slice(start).includes(expected)) return
    await new Promise(resolve => setTimeout(resolve, 50))
  }
  throw new Error(`Timed out waiting for ${label}: ${expected}`)
}

function normalizedLog (logPath) {
  return fs.readFileSync(logPath, 'utf8').replace(/\u001B\[[\d;]*[^\d;]/g, '')
}

function waitForExit (paper, timeoutMilliseconds) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Timed out waiting for Paper shutdown.')), timeoutMilliseconds)
    paper.once('exit', () => {
      clearTimeout(timer)
      resolve()
    })
  })
}

function findDirectories (root, name) {
  const matches = []
  for (const entry of fs.readdirSync(root, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue
    const resolved = path.join(root, entry.name)
    if (entry.name === name) matches.push(resolved)
    else matches.push(...findDirectories(resolved, name))
  }
  return matches
}

function requiredFile (environmentName) {
  const resolved = path.resolve(requiredValue(environmentName))
  if (!fs.statSync(resolved).isFile()) throw new Error(`${environmentName} is not a file: ${resolved}`)
  return resolved
}

function requiredValue (environmentName) {
  const value = process.env[environmentName]
  if (!value) throw new Error(`${environmentName} is required.`)
  return value
}

main().catch(error => {
  console.error(error.stack || error)
  process.exitCode = 1
})
