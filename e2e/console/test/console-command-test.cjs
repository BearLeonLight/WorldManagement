const fs = require('node:fs')
const path = require('node:path')
const { spawn } = require('node:child_process')
const assert = require('node:assert/strict')
const YAML = require('yaml')
const { assertCommandMatchesPath, assertRuntimeCoverage } = require('../../runtime-coverage.cjs')
const { resolveBuildChild } = require('../../build-child-path.cjs')
const {
  closeLogStream,
  createChildProcessDeadline,
  processExited,
  stopChildProcess,
  waitForClose
} = require('../../process-control.cjs')
const { resolveCheckedArtifact } = require('../../player/test/via-artifacts.cjs')
const { assertSuccessfulShutdown } = require('../../shutdown-log.cjs')

const PROJECT_ROOT = path.resolve(__dirname, '..', '..', '..')
let passedCommands = 0
const coveredPaths = new Set()

async function main () {
  const paperProcesses = new Set()
  const suiteDeadline = createChildProcessDeadline(paperProcesses, 360000, 'Console E2E')
  try {
    await runConsoleSuite(paperProcesses)
  } catch (error) {
    if (suiteDeadline.expired()) throw suiteDeadline.failure(error)
    throw error
  } finally {
    suiteDeadline.close()
  }
}

async function runConsoleSuite (paperProcesses) {
  const configuration = loadConfiguration()
  configuration.luckPermsPlugin = await resolveLuckPermsArtifact(configuration)
  fs.rmSync(configuration.serverRoot, { recursive: true, force: true })
  prepareServer(configuration)

  const consoleLogPath = path.join(configuration.serverRoot, 'console.log')
  const paperLogPath = path.join(configuration.serverRoot, 'logs', 'latest.log')
  const publishedLogPath = path.join(configuration.serverRoot, 'latest.log')
  const first = launchPaper(configuration, consoleLogPath, 'w', paperProcesses)
  let recoveryTargets
  try {
    await waitForLog(consoleLogPath, '[WorldManagement] Enabled WorldManagement', 120000, 'Paper startup', 0, first.paper)
    recoveryTargets = await runConsoleMatrix(first.paper, paperLogPath, configuration.serverRoot)
  } finally {
    await stopPaper(first, 'console Paper', paperProcesses, consoleLogPath, 0)
  }

  const restartOffset = normalizedLog(consoleLogPath).length
  const second = launchPaper(configuration, consoleLogPath, 'a', paperProcesses)
  try {
    await waitForLog(
      consoleLogPath, '[WorldManagement] Enabled WorldManagement', 120000,
      'Paper recovery startup', restartOffset, second.paper
    )
    assertRecoveredDeletes(configuration.serverRoot, recoveryTargets)
  } finally {
    await stopPaper(second, 'console recovery Paper', paperProcesses, consoleLogPath, restartOffset)
  }

  fs.copyFileSync(paperLogPath, publishedLogPath)
  const coveredLeaves = assertRuntimeCoverage('CONSOLE_RUNTIME', coveredPaths)
  console.log(`Verified ${passedCommands} console command outcomes across ${coveredLeaves} console runtime leaves without a player; log: ${publishedLogPath}`)
}

function launchPaper (configuration, logPath, flags, paperProcesses) {
  const logFile = fs.createWriteStream(logPath, { encoding: 'utf8', flags })
  const paper = spawn(configuration.javaExecutable, [
    '-Xms512M', '-Xmx512M',
    '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
    '-jar', 'paper.jar', '--nogui'
  ], { cwd: configuration.serverRoot, stdio: ['pipe', 'pipe', 'pipe'] })
  paperProcesses.add(paper)
  paper.stdout.pipe(logFile, { end: false })
  paper.stderr.pipe(logFile, { end: false })
  return { paper, logFile }
}

async function stopPaper ({ paper, logFile }, label, paperProcesses, logPath, start) {
  try {
    await stopChildProcess(paper, label)
    await waitForClose(paper, 5000, `${label} output close`)
  } finally {
    paperProcesses.delete(paper)
    await closeLogStream(logFile)
  }
  if (paper.exitCode !== 0) throw new Error(`${label} exited with code ${paper.exitCode}.`)
  assertSuccessfulShutdown(normalizedLog(logPath).slice(start), label)
}

function loadConfiguration () {
  return {
    paperJar: requiredFile('WM_PAPER_JAR'),
    pluginJar: requiredFile('WM_PLUGIN_JAR'),
    e2eSupportJar: requiredFile('WM_E2E_SUPPORT_JAR'),
    luckPermsPlugin: process.env.WM_LUCKPERMS_PLUGIN_JAR,
    luckPermsArtifact: {
      fileName: requiredValue('WM_LUCKPERMS_FILE_NAME'),
      cacheDirectory: requiredValue('WM_LUCKPERMS_CACHE_DIR'),
      downloadUrl: requiredValue('WM_LUCKPERMS_URL'),
      expectedHash: requiredValue('WM_LUCKPERMS_SHA512'),
      hashAlgorithm: 'sha512'
    },
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
  fs.copyFileSync(configuration.luckPermsPlugin, path.join(plugins, path.basename(configuration.luckPermsPlugin)))
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
    '  help:',
    '    players-enabled: false',
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

async function resolveLuckPermsArtifact (configuration) {
  if (configuration.luckPermsPlugin) {
    const configured = path.resolve(configuration.luckPermsPlugin)
    if (!fs.statSync(configured).isFile()) throw new Error(`LuckPerms plugin is not a file: ${configured}`)
    console.log(`Using configured LuckPerms plugin: ${configured}.`)
    return configured
  }
  const resolved = await resolveCheckedArtifact(configuration.luckPermsArtifact)
  console.log(`Using ${configuration.luckPermsArtifact.fileName} from ${resolved}.`)
  return resolved
}

async function runConsoleMatrix (paper, logPath, serverRoot) {
  await command(paper, logPath, 'wm help', 'WorldManagement 指令幫助', 'wm help')
  await command(
    paper, logPath, 'wm help ownership rank set',
    '/wm ownership rank set <world> <player> <rank>', 'wm help <query...>'
  )
  await command(paper, logPath, 'worldmanager help 1', 'WorldManagement 指令幫助', 'wm help <query...>', false)
  await feedback(paper, logPath, 'wm ownership rank', '/wm ownership rank set <world> <player> <rank>')
  await feedback(paper, logPath, 'wm storage migrate YAML SQLITE nope', '指令包含無效的子指令或參數')
  await feedback(paper, logPath, 'wm list detached extra', '指令包含多餘參數')

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

  await feedback(paper, logPath, 'wme2e world create adoptdetached', 'WM_E2E_WORLD_CREATED world=minecraft:adoptdetached loaded=true')
  await command(
    paper, logPath, 'wm adopt adoptdetached --detached',
    '世界 adoptdetached 已登錄，metadata 已保存但不套用治理', 'wm adopt <world> --detached'
  )
  assert.equal(readMetadata(serverRoot, 'adoptdetached')['management-state'], 'DETACHED')
  await command(
    paper, logPath, 'wm display-name set adoptdetached <gold>Detached Fixture</gold>',
    '顯示名稱已設為 Detached Fixture。', 'wm display-name set <world> <display-name...>', false
  )
  assert.equal(readMetadata(serverRoot, 'adoptdetached')['display-name'], '<gold>Detached Fixture</gold>')
  await command(paper, logPath, 'wm load adoptdetached', '世界 adoptdetached 已經載入', 'wm load <world>', false)
  await command(
    paper, logPath, 'wm remove adoptdetached purge confirm',
    '已永久清除已停止管理世界 adoptdetached', 'wm remove <world> purge confirm', false
  )

  await command(paper, logPath, 'wm create basic NORMAL NORMAL', '世界 basic 已建立並加入管理', 'wm create <world> <environment> <world-type>')
  const basicStoragePaths = [
    path.join(serverRoot, 'world', 'dimensions', 'minecraft', 'basic'),
    path.join(serverRoot, 'basic')
  ]
  if (!basicStoragePaths.some(candidate =>
    fs.existsSync(path.join(candidate, 'level.dat')) || fs.existsSync(path.join(candidate, 'data'))
  )) {
    throw new Error('Paper did not create on-disk storage for basic world.')
  }
  await command(paper, logPath, 'wm create typeonly NORMAL FLAT', '世界 typeonly 已建立並加入管理', 'wm create <world> <environment> <world-type>')
  await command(
    paper, logPath, 'wm create createddetached NORMAL FLAT --detached',
    '世界 createddetached 已建立，metadata 已保存但不套用', 'wm create <world> <environment> <world-type> <options>', false
  )
  assert.equal(readMetadata(serverRoot, 'createddetached')['management-state'], 'DETACHED')
  await command(paper, logPath, 'wm load createddetached', '世界 createddetached 已經載入', 'wm load <world>', false)
  await command(
    paper, logPath, 'wm remove createddetached purge confirm',
    '已永久清除已停止管理世界 createddetached', 'wm remove <world> purge confirm', false
  )
  await feedback(paper, logPath, 'wme2e world create runtimeonly', 'WM_E2E_WORLD_CREATED world=minecraft:runtimeonly loaded=true')
  await command(paper, logPath, 'wm unload runtimeonly', '世界 runtimeonly 已unloaded', 'wm unload <world>', false)
  assert.equal(
    fs.existsSync(path.join(serverRoot, 'plugins', 'WorldManagement', 'worlds', 'runtimeonly.yml')),
    false,
    'Runtime-only unload must not create metadata.'
  )
  await command(
    paper, logPath, 'wm load runtimeonly NORMAL --detached',
    '世界 runtimeonly 已載入，metadata 已保存但不套用治理',
    'wm load <world> <environment> --detached'
  )
  assert.equal(readMetadata(serverRoot, 'runtimeonly')['management-state'], 'DETACHED')
  await command(paper, logPath, 'wm create imported NORMAL FLAT --seed 12345', '世界 imported 已建立並加入管理', 'wm create <world> <environment> <world-type> <options>')
  await command(paper, logPath, 'wm unload imported', '世界 imported 已unloaded', 'wm unload <world>')
  await command(paper, logPath, 'wm load imported', '世界 imported 已loaded', 'wm load <world>')
  await command(paper, logPath, 'wm unload imported', '世界 imported 已unloaded', 'wm unload <world>')
  await command(paper, logPath, 'wm remove imported', '世界 imported 已停止管理', 'wm remove <world>')
  await command(paper, logPath, 'wm remove imported purge confirm', '已永久清除已停止管理世界 imported', 'wm remove <world> purge confirm')
  await command(
    paper, logPath, 'wm import imported NORMAL --detached',
    '世界 imported 已匯入，metadata 已保存但不套用治理', 'wm import <world> <environment> --detached'
  )
  assert.equal(readMetadata(serverRoot, 'imported')['management-state'], 'DETACHED')
  await command(paper, logPath, 'wm unload imported', '世界 imported 已unloaded', 'wm unload <world>', false)
  await command(paper, logPath, 'wm load imported', '世界 imported 已loaded', 'wm load <world>', false)
  await command(paper, logPath, 'wm manage imported', '世界 imported 已重新加入管理', 'wm manage <world>', false)
  await command(paper, logPath, 'wm unload imported', '世界 imported 已unloaded', 'wm unload <world>', false)
  await command(paper, logPath, 'wm remove imported', '世界 imported 已停止管理', 'wm remove <world>', false)
  await command(
    paper, logPath, 'wm remove imported purge confirm',
    '已永久清除已停止管理世界 imported', 'wm remove <world> purge confirm', false
  )
  await command(
    paper, logPath, 'wm import imported NORMAL',
    '世界 imported 已匯入並加入管理', 'wm import <world> <environment>'
  )
  assert.equal(readMetadata(serverRoot, 'imported')['management-state'], 'ACTIVE')

  await command(paper, logPath, 'wm ownership owner remove overworld', '世界擁有者已更新', 'wm ownership owner remove <world>')
  await command(paper, logPath, 'wm ownership rank create overworld builder', '世界階級設定已更新', 'wm ownership rank create <world> <rank>')
  await command(paper, logPath, 'wm ownership rank perm overworld builder add BUILD', '世界階級設定已更新', 'wm ownership rank perm <world> <rank> <operation> <permission>')
  await command(paper, logPath, 'wm ownership rank perm overworld builder remove BUILD', '世界階級設定已更新', 'wm ownership rank perm <world> <rank> <operation> <permission>')
  await command(paper, logPath, 'wmown rank toggle overworld', '世界階級設定已更新', 'wm ownership rank toggle <world>')
  await command(paper, logPath, 'wm ownership rank delete overworld builder', '世界階級設定已更新', 'wm ownership rank delete <world> <rank>')
  await command(paper, logPath, 'wm ownership access overworld mode WHITELIST', '世界存取設定已更新', 'wm ownership access <world> <operation> <value>', false)
  await command(paper, logPath, 'wm ownership access overworld mode NONE', '世界存取設定已更新', 'wm ownership access <world> <operation> <value>', false)

  await command(paper, logPath, 'wm create archive NORMAL NORMAL', '世界 archive 已建立並加入管理', 'wm create <world> <environment> <world-type>')
  await command(paper, logPath, 'wm unload archive', '世界 archive 已unloaded', 'wm unload <world>')
  await command(paper, logPath, 'wm remove archive', '世界 archive 已停止管理', 'wm remove <world>')
  await command(paper, logPath, 'wm list detached', 'archive', 'wm list detached')
  assert.equal(readMetadata(serverRoot, 'archive')['management-state'], 'DETACHED')
  await command(paper, logPath, 'wm manage archive', '世界 archive 已重新加入管理', 'wm manage <world>')
  await command(paper, logPath, 'wm remove archive', '世界 archive 已停止管理', 'wm remove <world>')
  await command(paper, logPath, 'wm remove archive purge confirm', '已永久清除已停止管理世界 archive', 'wm remove <world> purge confirm')

  await command(paper, logPath, 'wm delete basic confirm', '世界 basic 已完成存檔並卸載', 'wm delete <world> confirm')
  await command(paper, logPath, 'wm delete basic confirm', '將於下次伺服器啟動時完成刪除', 'wm delete <world> confirm')
  assertDeletingMetadata(readMetadata(serverRoot, 'basic'), 'STANDARD')

  await feedback(paper, logPath, 'wme2e world create autodelete', 'WM_E2E_WORLD_CREATED world=minecraft:autodelete loaded=true')
  await command(
    paper, logPath, 'wm delete autodelete confirm',
    '世界 autodelete 已完成存檔並卸載', 'wm delete <world> confirm', false
  )
  assert.equal(readMetadata(serverRoot, 'autodelete')['management-state'], 'DETACHED')
  assert.equal(readMetadata(serverRoot, 'autodelete')['registration-source'], 'DELETE_AUTO')
  await command(
    paper, logPath, 'wm delete autodelete confirm',
    '將於下次伺服器啟動時完成刪除', 'wm delete <world> confirm', false
  )
  assertDeletingMetadata(readMetadata(serverRoot, 'autodelete'), 'DELETE_AUTO')
  assert.ok(
    findDirectories(serverRoot, '.worldmanagement-quarantine').length > 0,
    'Pending restart delete must retain a quarantine claim.'
  )

  await command(paper, logPath, 'wmstore migrate INVALID SQLITE confirm', '未知的儲存供應者', 'wm storage migrate <source> <target> confirm')
  await command(paper, logPath, 'wm storage migrate YAML SQLITE confirm', '已遷移', 'wm storage migrate <source> <target> confirm')
  if (!fs.existsSync(path.join(serverRoot, 'plugins', 'WorldManagement', 'migration-target.db'))) {
    throw new Error('Console storage migration did not create the SQLite target.')
  }
  return [
    { worldId: 'basic', storagePaths: basicStoragePaths },
    {
      worldId: 'autodelete',
      storagePaths: [
        path.join(serverRoot, 'world', 'dimensions', 'minecraft', 'autodelete'),
        path.join(serverRoot, 'autodelete')
      ]
    }
  ]
}

function assertDeletingMetadata (metadata, registrationSource) {
  assert.equal(metadata['management-state'], 'DELETING')
  assert.equal(metadata['registration-source'], registrationSource)
  assert.match(
    metadata.deletion?.['transaction-id'],
    /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
  )
}

function assertRecoveredDeletes (serverRoot, targets) {
  for (const { worldId, storagePaths } of targets) {
    assert.equal(
      fs.existsSync(path.join(serverRoot, 'plugins', 'WorldManagement', 'worlds', `${worldId}.yml`)),
      false,
      `Startup recovery left ${worldId} metadata behind.`
    )
    assert.equal(
      storagePaths.some(candidate => fs.existsSync(candidate)),
      false,
      `Startup recovery left ${worldId} storage behind.`
    )
  }
  assert.equal(
    findDirectories(serverRoot, '.worldmanagement-quarantine').some(directory =>
      fs.readdirSync(directory).length > 0
    ),
    false,
    'Startup recovery left a quarantine claim behind.'
  )
}

async function command (paper, logPath, input, expected, commandPath, creditCoverage = true) {
  assertCommandMatchesPath(input, commandPath)
  const start = normalizedLog(logPath).length
  paper.stdin.write(`${input}\n`)
  await waitForLog(logPath, expected, 30000, input, start, paper)
  passedCommands++
  if (creditCoverage) coveredPaths.add(commandPath)
}

async function feedback (paper, logPath, input, expected) {
  const start = normalizedLog(logPath).length
  paper.stdin.write(`${input}\n`)
  await waitForLog(logPath, expected, 30000, input, start, paper)
  passedCommands++
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
  assert.equal(metadata['schema-version'], 4, `${worldId} must use schema 4 metadata.`)
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

async function waitForLog (logPath, expected, timeoutMilliseconds, label, start = 0, paper) {
  const deadline = Date.now() + timeoutMilliseconds
  while (Date.now() < deadline) {
    if (fs.existsSync(logPath) && normalizedLog(logPath).slice(start).includes(expected)) return
    if (paper && processExited(paper)) {
      throw new Error(`Paper exited before ${label}: ${expected}`)
    }
    await new Promise(resolve => setTimeout(resolve, 50))
  }
  throw new Error(`Timed out waiting for ${label}: ${expected}`)
}

function normalizedLog (logPath) {
  return fs.readFileSync(logPath, 'utf8').replace(/\u001B\[[\d;]*[^\d;]/g, '')
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
