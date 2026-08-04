const fs = require('node:fs')
const crypto = require('node:crypto')
const net = require('node:net')
const path = require('node:path')
const zlib = require('node:zlib')
const assert = require('node:assert/strict')
const { spawn } = require('node:child_process')
const minecraftData = require('minecraft-data')
const minecraftProtocol = require('minecraft-protocol')
const mineflayer = require('mineflayer')
const nbt = require('prismarine-nbt')
const { Vec3 } = require('vec3')
const YAML = require('yaml')
const { chooseConnectionMode, runWithViaFallback } = require('./protocol-strategy.cjs')
const { resolveCheckedArtifact } = require('./via-artifacts.cjs')
const { literalChildren, literalChildrenAt } = require('./command-tree.cjs')
const { assertCommandMatchesPath, assertRuntimeCoverage } = require('../../runtime-coverage.cjs')
const { resolveBuildChild } = require('../../build-child-path.cjs')
const { closeLogStream, createChildProcessDeadline, stopChildProcess } = require('../../process-control.cjs')
const { createOutputMonitor } = require('./output-monitor.cjs')

const BOT_NAME = 'WmLifecycleE2E'
const OWNER_BOT_NAME = 'WmOwnerE2E'
const OVERWORLD_ID = 'overworld'
const IDENTITY_WORLD_ID = 'identitytarget'
const LUCKPERMS_WARP = 'lpcontext'
const LUCKPERMS_PERMISSION = 'worldmanagement.e2e.destination-warp'
const SHUTDOWN_FAILURES = ['zip file error', 'I/O shutdown failed', 'did not drain']
const SHUTDOWN_COMPLETE = 'WorldManagement terminal shutdown complete.'
const PROJECT_ROOT = path.resolve(__dirname, '..', '..', '..')
const activePaperProcesses = new Set()
const suiteDeadline = createChildProcessDeadline(activePaperProcesses, 600000, 'Player E2E')
let passedCommandChecks = 0
const coveredPaths = new Set()

const timeout = async (promise, milliseconds, label) => {
  let timer
  try {
    return await Promise.race([
      promise,
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error(`Timed out waiting for ${label}.`)), milliseconds)
      })
    ])
  } finally {
    clearTimeout(timer)
  }
}

const onceMatching = (emitter, event, predicate) => new Promise((resolve, reject) => {
  const onEvent = (...arguments_) => {
    if (!predicate(...arguments_)) return
    cleanup()
    resolve(arguments_)
  }
  const onError = (error) => {
    cleanup()
    reject(error instanceof Error ? error : new Error(String(error)))
  }
  const cleanup = () => {
    emitter.off(event, onEvent)
    emitter.off('error', onError)
  }
  emitter.on(event, onEvent)
  emitter.on('error', onError)
})

const availablePort = () => new Promise((resolve, reject) => {
  const server = net.createServer()
  server.once('error', reject)
  server.listen(0, '127.0.0.1', () => {
    const { port } = server.address()
    server.close((error) => error ? reject(error) : resolve(port))
  })
})

async function main () {
  const configuration = loadConfiguration()
  fs.rmSync(configuration.serverRoot, { recursive: true, force: true })
  fs.mkdirSync(configuration.serverRoot, { recursive: true })
  configuration.luckPermsPlugin = await resolveLuckPermsArtifact(configuration)

  let nativeAttempt
  let completedAttempt
  try {
    nativeAttempt = await startPaperAttempt(configuration, 'native', [], configuration.luckPermsPlugin)
    const status = await timeout(minecraftProtocol.ping({
      host: '127.0.0.1',
      port: nativeAttempt.port,
      closeTimeout: 10000,
      noPongTimeout: 5000
    }), 15000, 'Paper status ping')
    const decision = chooseConnectionMode(
      status.version,
      mineflayer.testedVersions,
      protocolForVersion,
      configuration.fallbackClientVersion
    )
    console.log(`Server reports ${decision.serverVersion} protocol ${decision.serverProtocol}.`)

    completedAttempt = await runWithViaFallback({
      decision,
      runNative: async nativeDecision => {
        await runPlayerFlow(nativeAttempt, nativeDecision.clientVersion)
        return nativeAttempt
      },
      prepareVia: async nativeDecision => {
        await stopPaperAttempt(nativeAttempt)
        nativeAttempt = undefined
        console.log(`Mineflayer cannot use ${nativeDecision.serverVersion} directly; preparing Via fallback.`)
        return resolveViaArtifacts(configuration)
      },
      runVia: async (nativeDecision, viaArtifacts) => {
        const viaAttempt = await startPaperAttempt(configuration, 'via-fallback', viaArtifacts, configuration.luckPermsPlugin)
        try {
          await runPlayerFlow(viaAttempt, nativeDecision.clientVersion)
          return viaAttempt
        } catch (error) {
          await stopPaperAttempt(viaAttempt)
          throw error
        }
      }
    })
  } finally {
    await stopPaperAttempt(nativeAttempt)
    await stopPaperAttempt(completedAttempt)
    if (completedAttempt) {
      fs.copyFileSync(completedAttempt.logPath, path.join(configuration.serverRoot, 'latest.log'))
    }
  }

  console.log(
    `Mineflayer player E2E passed in ${completedAttempt.mode} mode with client ${completedAttempt.clientVersion}; log: ${completedAttempt.logPath}`
  )
}

function loadConfiguration () {
  return {
    paperJar: requiredFile('WM_PAPER_JAR'),
    pluginJar: requiredFile('WM_PLUGIN_JAR'),
    e2eSupportJar: requiredFile('WM_E2E_SUPPORT_JAR'),
    javaExecutable: process.env.WM_JAVA_EXECUTABLE || 'java',
    serverRoot: resolveServerRoot(),
    fallbackClientVersion: process.env.WM_MINECRAFT_VERSION || undefined,
    viaCacheDirectory: requiredValue('WM_VIA_CACHE_DIR'),
    viaArtifacts: [artifactConfiguration('VIAVERSION'), artifactConfiguration('VIABACKWARDS')],
    luckPermsPlugin: process.env.WM_LUCKPERMS_PLUGIN_JAR,
    luckPermsArtifact: {
      fileName: requiredValue('WM_LUCKPERMS_FILE_NAME'),
      cacheDirectory: requiredValue('WM_LUCKPERMS_CACHE_DIR'),
      downloadUrl: requiredValue('WM_LUCKPERMS_URL'),
      expectedHash: requiredValue('WM_LUCKPERMS_SHA512'),
      hashAlgorithm: 'sha512'
    }
  }
}

function resolveServerRoot () {
  return resolveBuildChild(process.env.WM_E2E_SERVER_DIR, 'player-e2e', 'WM_E2E_SERVER_DIR')
}

function artifactConfiguration (name) {
  return {
    fileName: requiredValue(`WM_${name}_FILE_NAME`),
    downloadUrl: requiredValue(`WM_${name}_URL`),
    expectedHash: requiredValue(`WM_${name}_SHA256`),
    hashAlgorithm: 'sha256'
  }
}

async function resolveViaArtifacts (configuration) {
  const artifacts = []
  for (const artifact of configuration.viaArtifacts) {
    const resolved = await resolveCheckedArtifact({
      ...artifact,
      cacheDirectory: configuration.viaCacheDirectory
    })
    console.log(`Using ${artifact.fileName} from ${resolved}.`)
    artifacts.push(resolved)
  }
  return artifacts
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

async function startPaperAttempt (configuration, mode, viaPlugins, luckPermsPlugin) {
  const serverDirectory = path.join(configuration.serverRoot, mode)
  const port = await availablePort()

  fs.rmSync(serverDirectory, { recursive: true, force: true })
  fs.mkdirSync(path.join(serverDirectory, 'plugins'), { recursive: true })
  const pluginDataDirectory = path.join(serverDirectory, 'plugins', 'WorldManagement')
  fs.mkdirSync(pluginDataDirectory, { recursive: true })
  fs.copyFileSync(configuration.paperJar, path.join(serverDirectory, 'paper.jar'))
  fs.copyFileSync(configuration.pluginJar, path.join(serverDirectory, 'plugins', path.basename(configuration.pluginJar)))
  fs.copyFileSync(configuration.e2eSupportJar, path.join(serverDirectory, 'plugins', path.basename(configuration.e2eSupportJar)))
  fs.copyFileSync(luckPermsPlugin, path.join(serverDirectory, 'plugins', path.basename(luckPermsPlugin)))
  for (const plugin of viaPlugins) {
    fs.copyFileSync(plugin, path.join(serverDirectory, 'plugins', path.basename(plugin)))
  }
  fs.writeFileSync(path.join(serverDirectory, 'eula.txt'), 'eula=true\n')
  fs.writeFileSync(path.join(pluginDataDirectory, 'config.yml'), [
    'schema-version: 1',
    'storage:',
    '  provider: YAML',
    'storage-migration:',
    '  targets:',
    '    sqlite:',
    '      jdbc-url: "jdbc:sqlite:plugins/WorldManagement/migration-target.db"',
    ''
  ].join('\n'))
  fs.writeFileSync(path.join(pluginDataDirectory, 'commands.yml'), [
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
  fs.writeFileSync(path.join(serverDirectory, 'server.properties'), [
    `server-port=${port}`,
    'server-ip=127.0.0.1',
    'online-mode=false',
    'spawn-protection=0',
    'gamemode=creative',
    'view-distance=2',
    'simulation-distance=2',
    'generate-structures=false',
    'spawn-monsters=false',
    ''
  ].join('\n'))

  const logPath = path.join(serverDirectory, 'latest.log')
  const { paper, logFile, outputMonitor } = launchPaper(configuration.javaExecutable, serverDirectory, logPath, 'w')
  const attempt = {
    mode, serverDirectory, logFile, logPath, paper, outputMonitor, port,
    javaExecutable: configuration.javaExecutable, stopped: false
  }

  try {
    await timeout(waitForObservedOutput(paper, '[WorldManagement] Enabled WorldManagement'), 120000, `${mode} Paper and WorldManagement startup`)
    await timeout(waitForObservedOutput(paper, 'LuckPerms: available'), 10000, `${mode} LuckPerms hook startup`)
    if (viaPlugins.length > 0) {
      await timeout(waitForObservedOutput(paper, 'ViaBackwards'), 10000, `${mode} ViaBackwards startup`)
      await timeout(
        waitForObservedOutput(paper, 'Registering protocol transformers and injecting'),
        10000,
        `${mode} ViaVersion protocol injection`
      )
    }
    return attempt
  } catch (error) {
    await stopPaperAttempt(attempt)
    throw error
  }
}

function launchPaper (javaExecutable, serverDirectory, logPath, flags) {
  const logFile = fs.createWriteStream(logPath, { encoding: 'utf8', flags })
  const paper = spawn(javaExecutable, [
    '-Xms512M', '-Xmx1024M',
    '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
    '-jar', 'paper.jar', '--nogui'
  ], { cwd: serverDirectory, stdio: ['pipe', 'pipe', 'pipe'] })
  const outputMonitor = createOutputMonitor(paper)
  activePaperProcesses.add(paper)
  paper.stdout.pipe(logFile, { end: false })
  paper.stderr.pipe(logFile, { end: false })
  paper.wmOutputMonitor = outputMonitor
  return { paper, logFile, outputMonitor }
}

async function runPlayerFlow (attempt, clientVersion) {
  let bot
  let ownerBot
  let phase = 'pre-spawn'
  attempt.clientVersion = clientVersion
  try {
    await consoleCommand(attempt, `wm adopt ${OVERWORLD_ID}`, `世界 ${OVERWORLD_ID} 已加入管理`, 'adopt player fixture world', 'wm adopt <world>')
    await consoleCommand(attempt, 'wm create teleporttarget NORMAL NORMAL', '世界 teleporttarget 已建立並加入管理', 'create teleport fixture', 'wm create <world> <environment> <world-type>')
    await consoleCommand(attempt, 'wm create relocation NORMAL NORMAL', '世界 relocation 已建立並加入管理', 'create unload fixture', 'wm create <world> <environment> <world-type>')
    await consoleCommand(attempt, 'wm create deletiontarget NORMAL NORMAL', '世界 deletiontarget 已建立並加入管理', 'create delete fixture', 'wm create <world> <environment> <world-type>')
    await consoleCommand(attempt, `wm create ${IDENTITY_WORLD_ID} NORMAL NORMAL`, `世界 ${IDENTITY_WORLD_ID} 已建立並加入管理`, 'create identity fixture', 'wm create <world> <environment> <world-type>')
    await consoleCommand(attempt, 'wm ownership access teleporttarget mode WHITELIST', '世界存取設定已更新', 'restrict teleport fixture', 'wm ownership access <world> <operation> <value>')
    bot = await connectPlayer(attempt, clientVersion)
    phase = 'post-spawn'
    await verifyPermissionCommandTree(attempt, bot)
    await verifyExplicitTeleportPermissions(attempt, bot)
    ownerBot = await connectPlayer(attempt, clientVersion, OWNER_BOT_NAME)
    await verifyWorldProtection(attempt, bot)
    await disconnectBot(ownerBot, 'owner fixture disconnect')
    ownerBot = undefined
    await grantOperator(attempt, bot)

    await consoleCommand(
      attempt, 'wm create detachedtarget NORMAL NORMAL',
      '世界 detachedtarget 已建立並加入管理', 'create detached lifecycle fixture',
      'wm create <world> <environment> <world-type>'
    )
    await supportCommand(
      attempt, 'wme2e world create runtimefallback',
      'WM_E2E_WORLD_CREATED world=minecraft:runtimefallback loaded=true',
      'create unknown runtime fallback fixture'
    )
    await command(
      bot, '/wm tp self detachedtarget 90 90 92',
      '已傳送至世界 detachedtarget', 'enter detach fixture',
      'wm tp self <world> <x> <y> <z>'
    )
    await assertPlayerWorld(attempt, bot, 'minecraft:detachedtarget', 'loaded remove source world')
    await timeout(bot.waitForChunksToLoad(), 10000, 'detached fixture chunk load')
    await command(
      bot, '/wm remove detachedtarget',
      '世界 detachedtarget 已停止管理', 'remove loaded world without unload',
      'wm remove <world>', false
    )
    await assertPlayerWorld(attempt, bot, 'minecraft:detachedtarget', 'loaded remove preserves world')
    assert.equal(readWorldMetadata(attempt, 'detachedtarget')['management-state'], 'DETACHED')

    await deopPlayer(attempt, bot)
    await supportCommand(
      attempt,
      `wme2e player break-probe ${BOT_NAME}`,
      `WM_E2E_PLAYER_BREAK_PROBE player=${BOT_NAME} world=minecraft:detachedtarget cancelled=false final=AIR`,
      'detached governance disabled break probe'
    )
    await grantOperator(attempt, bot)
    await consoleTeleport(
      attempt, BOT_NAME, 'minecraft:overworld', 0, 90, 0, 'minecraft:detachedtarget'
    )
    await command(
      bot, '/wm tp self detachedtarget',
      '已傳送至世界 detachedtarget', 'teleport to detached world',
      'wm tp self <world>'
    )
    await assertPlayerWorld(attempt, bot, 'minecraft:detachedtarget', 'detached teleport target')
    await command(
      bot, '/wm unload detachedtarget runtimefallback',
      '世界 detachedtarget 已unloaded', 'unknown runtime fallback relocation',
      'wm unload <world> <fallback>'
    )
    await assertPlayerWorld(attempt, bot, 'minecraft:runtimefallback', 'unknown runtime fallback world')
    await command(
      bot, `/wm delete runtimefallback ${OVERWORLD_ID} confirm`,
      '世界 runtimefallback 已完成存檔並卸載', 'unknown runtime delete auto-adopt',
      'wm delete <world> <fallback> confirm'
    )
    await assertPlayerWorld(attempt, bot, 'minecraft:overworld', 'unknown delete fallback world')
    const autoDeleteDetached = readWorldMetadata(attempt, 'runtimefallback')
    assert.equal(autoDeleteDetached['management-state'], 'DETACHED')
    assert.equal(autoDeleteDetached['registration-source'], 'DELETE_AUTO')
    await command(
      bot, `/wm delete runtimefallback ${OVERWORLD_ID} confirm`,
      '將於下次伺服器啟動時完成刪除', 'unknown runtime delete pending restart',
      'wm delete <world> <fallback> confirm'
    )
    const autoDeleteDeleting = readWorldMetadata(attempt, 'runtimefallback')
    assert.equal(autoDeleteDeleting['management-state'], 'DELETING')
    assert.equal(autoDeleteDeleting['registration-source'], 'DELETE_AUTO')
    assert.match(
      autoDeleteDeleting.deletion?.['transaction-id'],
      /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
    )

    bot.chat(`/tp ${BOT_NAME} 40 90 40`)
    await waitForPosition(bot, { x: 40, y: 90, z: 40 }, 'self teleport setup')
    await command(bot, '/wm tp self teleporttarget', '已傳送至世界 teleporttarget', 'self teleport to spawn', 'wm tp self <world>')
    await assertPlayerWorld(attempt, bot, 'minecraft:teleporttarget', 'self teleport target world')
    await waitUntilMovedFrom(bot, { x: 40, y: 90, z: 40 }, 'self teleport to spawn position')
    await command(bot, `/wm tp self ${OVERWORLD_ID} 10 80 10`, `已傳送至世界 ${OVERWORLD_ID}`, 'self teleport to coordinates', 'wm tp self <world> <x> <y> <z>')
    await assertPlayerWorld(attempt, bot, 'minecraft:overworld', 'self coordinate target world')
    await waitForPosition(bot, { x: 10, y: 80, z: 10 }, 'self teleport coordinate position')
    await command(bot, `/wm tp player ${BOT_NAME} teleporttarget`, '已傳送至世界 teleporttarget', 'player teleport to spawn', 'wm tp player <player> <world>')
    await assertPlayerWorld(attempt, bot, 'minecraft:teleporttarget', 'player target world')
    await waitUntilMovedFrom(bot, { x: 10, y: 80, z: 10 }, 'player teleport to spawn position')
    await command(bot, `/wm tp player ${BOT_NAME} ${OVERWORLD_ID} 11 81 11`, `已傳送至世界 ${OVERWORLD_ID}`, 'player teleport to coordinates', 'wm tp player <player> <world> <x> <y> <z>')
    await assertPlayerWorld(attempt, bot, 'minecraft:overworld', 'player coordinate target world')
    await waitForPosition(bot, { x: 11, y: 81, z: 11 }, 'player teleport coordinate position')
    await command(bot, '/wm tp --any teleporttarget', '已傳送至世界 teleporttarget', 'explicit bypass teleport to spawn', 'wm tp --any <world>')
    await assertPlayerWorld(attempt, bot, 'minecraft:teleporttarget', 'explicit bypass target world')
    await waitUntilMovedFrom(bot, { x: 11, y: 81, z: 11 }, 'explicit bypass teleport to spawn position')
    await command(bot, `/wm tp --any ${OVERWORLD_ID} 12 82 12`, `已傳送至世界 ${OVERWORLD_ID}`, 'explicit bypass teleport to coordinates', 'wm tp --any <world> <x> <y> <z>')
    await assertPlayerWorld(attempt, bot, 'minecraft:overworld', 'explicit bypass coordinate target world')
    await waitForPosition(bot, { x: 12, y: 82, z: 12 }, 'explicit bypass teleport coordinate position')

    await command(bot, `/wm ownership owner set ${OVERWORLD_ID} ${BOT_NAME}`, '世界擁有者已更新', 'owner set', 'wm ownership owner set <world> <player>')
    await consoleCommand(attempt, `wm ownership rank create ${OVERWORLD_ID} builder`, '世界階級設定已更新', 'create player rank fixture', 'wm ownership rank create <world> <rank>')
    await command(bot, `/wm ownership rank set ${OVERWORLD_ID} ${BOT_NAME} builder`, '世界階級設定已更新', 'player rank set', 'wm ownership rank set <world> <player> <rank>')
    await command(bot, `/wm ownership rank remove ${OVERWORLD_ID} ${BOT_NAME}`, '世界階級設定已更新', 'player rank remove', 'wm ownership rank remove <world> <player>')
    await command(bot, `/wm ownership access ${OVERWORLD_ID} add ${BOT_NAME}`, '世界存取設定已更新', 'access entry add', 'wm ownership access <world> <operation> <value>')
    await command(bot, `/wm ownership access ${OVERWORLD_ID} remove ${BOT_NAME}`, '世界存取設定已更新', 'access entry remove', 'wm ownership access <world> <operation> <value>')

    await command(bot, `/wm tp self ${OVERWORLD_ID} 20 80 20`, `已傳送至世界 ${OVERWORLD_ID}`, 'warp source position', 'wm tp self <world> <x> <y> <z>')
    await command(bot, `/wm warp set ${OVERWORLD_ID} spawn PUBLIC`, '傳送點 spawn 已儲存', 'public warp set', 'wm warp set <world> <name> <visibility>')
    await command(bot, `/wm warp trust ${OVERWORLD_ID} spawn add ${BOT_NAME}`, '傳送點信任名單已更新', 'warp trust add', 'wm warp trust <world> <warp> <operation> <player>')
    await command(bot, `/wm warp trust ${OVERWORLD_ID} spawn remove ${BOT_NAME}`, '傳送點信任名單已更新', 'warp trust remove', 'wm warp trust <world> <warp> <operation> <player>')
    await command(bot, `/wm tp self ${OVERWORLD_ID} 30 80 30`, `已傳送至世界 ${OVERWORLD_ID}`, 'move away from warp', 'wm tp self <world> <x> <y> <z>')
    await positionCommand(
      bot,
      `/wm warp tp ${OVERWORLD_ID} spawn`,
      { x: 20, y: 80, z: 20 },
      'warp teleport',
      'wm warp tp <world> <name>'
    )

    const unloadSourcePosition = { x: 200, y: 90, z: 200 }
    await command(bot, '/wm tp self relocation 200 90 200', '已傳送至世界 relocation', 'enter unload source world', 'wm tp self <world> <x> <y> <z>')
    await assertPlayerWorld(attempt, bot, 'minecraft:relocation', 'unload source world')
    await waitForPosition(bot, unloadSourcePosition, 'unload source position')
    await command(bot, `/wm unload relocation ${OVERWORLD_ID}`, '世界 relocation 已unloaded', 'unload with player fallback', 'wm unload <world> <fallback>')
    await assertPlayerWorld(attempt, bot, 'minecraft:overworld', 'unload fallback world')
    await waitUntilMovedFrom(bot, unloadSourcePosition, 'unload fallback relocation')

    const deleteSourcePosition = { x: 220, y: 90, z: 220 }
    await command(bot, '/wm tp self deletiontarget 220 90 220', '已傳送至世界 deletiontarget', 'enter delete source world', 'wm tp self <world> <x> <y> <z>')
    await assertPlayerWorld(attempt, bot, 'minecraft:deletiontarget', 'delete source world')
    await waitForPosition(bot, deleteSourcePosition, 'delete source position')
    await command(bot, `/wm delete deletiontarget ${OVERWORLD_ID} confirm`, '已完成存檔並卸載', 'delete fallback unload confirmation', 'wm delete <world> <fallback> confirm')
    await assertPlayerWorld(attempt, bot, 'minecraft:overworld', 'delete fallback world')
    await waitUntilMovedFrom(bot, deleteSourcePosition, 'delete fallback relocation')
    await command(bot, `/wm delete deletiontarget ${OVERWORLD_ID} confirm`, '將於下次伺服器啟動時完成刪除', 'delete fallback pending restart', 'wm delete <world> <fallback> confirm')
    const metadataPath = path.join(attempt.serverDirectory, 'plugins', 'WorldManagement', 'worlds', 'deletiontarget.yml')
    const deletingMetadata = YAML.parse(fs.readFileSync(metadataPath, 'utf8'))
    assert.equal(deletingMetadata['management-state'], 'DELETING')
    assert.match(
      deletingMetadata.deletion?.['transaction-id'],
      /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
    )
    assert.ok(
      findDirectories(attempt.serverDirectory, '.worldmanagement-quarantine').length > 0,
      'Pending player fallback delete did not retain a quarantine claim.'
    )

    await command(bot, '/wm ownership access teleporttarget mode NONE', '世界存取設定已更新', 'open LuckPerms destination world', 'wm ownership access <world> <operation> <value>')
    await command(bot, '/wm tp self teleporttarget 60 90 60', '已傳送至世界 teleporttarget', 'enter LuckPerms warp destination', 'wm tp self <world> <x> <y> <z>')
    await command(bot, `/wm warp set teleporttarget ${LUCKPERMS_WARP} PUBLIC`, `傳送點 ${LUCKPERMS_WARP} 已儲存`, 'set LuckPerms destination warp', 'wm warp set <world> <name> <visibility>')

    await command(
      bot,
      `/wm tp self ${IDENTITY_WORLD_ID} 0 90 0`,
      `已傳送至世界 ${IDENTITY_WORLD_ID}`,
      'enter identity replacement world',
      'wm tp self <world> <x> <y> <z>'
    )
    await assertPlayerWorld(attempt, bot, `minecraft:${IDENTITY_WORLD_ID}`, 'identity replacement source world')
    await deopPlayer(attempt, bot)
    await disconnectBot(bot, 'identity replacement fixture disconnect')
    bot = undefined

    await stopPaperAttempt(attempt)
    const replacement = await replacePaperWorldIdentity(attempt, IDENTITY_WORLD_ID)
    setWarpRequiredPermission(attempt, 'teleporttarget', LUCKPERMS_WARP, LUCKPERMS_PERMISSION)
    await restartPaperAttempt(attempt)
    assert.equal(
      fs.existsSync(path.join(
        attempt.serverDirectory, 'plugins', 'WorldManagement', 'worlds', 'deletiontarget.yml'
      )),
      false,
      'Startup recovery left managed deletion metadata behind.'
    )
    assert.equal(
      fs.existsSync(path.join(
        attempt.serverDirectory, 'plugins', 'WorldManagement', 'worlds', 'runtimefallback.yml'
      )),
      false,
      'Startup recovery left DELETE_AUTO metadata behind.'
    )
    assert.equal(
      findDirectories(attempt.serverDirectory, '.worldmanagement-quarantine').some(directory =>
        fs.readdirSync(directory).length > 0
      ),
      false,
      'Startup recovery left player E2E quarantine claims behind.'
    )
    const conflicted = await waitForIdentityState(attempt, IDENTITY_WORLD_ID, 'CONFLICT')
    assert.equal(
      conflicted.identity.accepted['world-uuid'],
      replacement.acceptedUuid,
      'Identity replacement changed the accepted UUID.'
    )
    assert.equal(
      conflicted.identity.pending.snapshot['world-uuid'],
      replacement.replacementUuid,
      'Identity replacement did not persist the observed UUID as pending.'
    )

    bot = await connectPlayer(attempt, clientVersion)
    await assertPlayerWorld(attempt, bot, 'minecraft:overworld', 'identity replacement fallback login')
  await verifyLuckPermsDestinationPermission(attempt, bot)

    const bypassEntryDenied = waitForOutput(
      attempt.paper,
      `WM_E2E_EVENT_TELEPORT player=${BOT_NAME} cause=PLUGIN from=minecraft:overworld to=minecraft:${IDENTITY_WORLD_ID} cancelled=true`
    )
    const fixtureReady = waitForOutput(
      attempt.paper,
      `WM_E2E_WORLD_LOAD_RELOCATED player=${BOT_NAME} world=minecraft:${IDENTITY_WORLD_ID} teleported=false`
    )
    const bypassArmed = waitForOutput(
      attempt.paper,
      `WM_E2E_WORLD_LOAD_RELOCATION_ARMED player=${BOT_NAME} world=minecraft:${IDENTITY_WORLD_ID} bypass=true`
    )
    const worldLoadIsolation = Promise.all([
      timeout(bypassArmed, 10000, 'identity replacement fixture bypass'),
      timeout(fixtureReady, 10000, 'identity replacement online fixture'),
      timeout(bypassEntryDenied, 10000, 'identity replacement bypass entry denial')
    ])
    await supportCommand(
      attempt,
      `wme2e world load-relocate ${BOT_NAME} ${IDENTITY_WORLD_ID}`,
      `WM_E2E_WORLD_LOAD_COMPLETE world=minecraft:${IDENTITY_WORLD_ID}`,
      'load identity replacement for online player'
    )
    await worldLoadIsolation
    await assertPlayerWorld(attempt, bot, 'minecraft:overworld', 'identity replacement bypass entry source')

    const entryDenied = waitForOutput(
      attempt.paper,
      `WM_E2E_EVENT_TELEPORT player=${BOT_NAME} cause=COMMAND from=minecraft:overworld to=minecraft:${IDENTITY_WORLD_ID} cancelled=true`
    )
    attempt.paper.stdin.write(
      `execute in minecraft:${IDENTITY_WORLD_ID} run tp ${BOT_NAME} 0 90 0\n`
    )
    await timeout(entryDenied, 10000, 'identity replacement direct entry denial')
    await assertPlayerWorld(attempt, bot, 'minecraft:overworld', 'identity replacement denied entry source')

    const playerKilled = waitForOutput(attempt.paper, `Killed ${BOT_NAME}`)
    attempt.paper.stdin.write(`execute in minecraft:${IDENTITY_WORLD_ID} run spawnpoint ${BOT_NAME} 0 90 0\n`)
    attempt.paper.stdin.write(`kill ${BOT_NAME}\n`)
    await timeout(playerKilled, 10000, 'identity replacement player death')
    const respawnedInReplacement = waitForOutput(
      attempt.paper,
      `WM_E2E_EVENT_POST_RESPAWN player=${BOT_NAME} world=minecraft:${IDENTITY_WORLD_ID}`
    )
    const relocatedAfterRespawn = waitForOutput(
      attempt.paper,
      `WM_E2E_EVENT_CHANGED_WORLD player=${BOT_NAME} from=minecraft:${IDENTITY_WORLD_ID} to=minecraft:overworld`
    )
    await Promise.all([
      supportCommand(
        attempt,
        `wme2e player respawn ${BOT_NAME} ${IDENTITY_WORLD_ID}`,
        `WM_E2E_PLAYER_RESPAWN_REQUESTED player=${BOT_NAME} health=0.0 online=true requested=true`,
        'identity replacement player respawn'
      ),
      timeout(respawnedInReplacement, 10000, 'identity replacement respawn world'),
      timeout(relocatedAfterRespawn, 10000, 'identity replacement respawn relocation')
    ])
    await assertPlayerWorld(attempt, bot, 'minecraft:overworld', 'identity replacement respawn fallback')

    const coveredLeaves = assertRuntimeCoverage('PLAYER_RUNTIME', coveredPaths)
    console.log(`Verified ${passedCommandChecks} player command outcomes across ${coveredLeaves} player runtime leaves.`)

    await disconnectBot(bot, 'Mineflayer disconnect')
    bot = undefined
  } catch (error) {
    const resolved = error instanceof Error ? error : new Error(String(error))
    resolved.phase = phase
    throw resolved
  } finally {
    if (bot) bot.end('WorldManagement player E2E cleanup')
    if (ownerBot) ownerBot.end('WorldManagement owner fixture cleanup')
  }
}

async function connectPlayer (attempt, clientVersion, username = BOT_NAME) {
  const bot = mineflayer.createBot({
    host: '127.0.0.1',
    port: attempt.port,
    username,
    auth: 'offline',
    version: clientVersion,
    hideErrors: true,
    checkTimeoutInterval: 60000,
    respawn: false
  })
  if (username === BOT_NAME) {
    bot._client.on('declare_commands', packet => {
      bot.wmLatestCommandTree = packet
    })
  }
  bot.once('kicked', reason => bot.emit('error', new Error(`Bot was kicked: ${JSON.stringify(reason)}`)))
  await timeout(onceMatching(bot, 'spawn', () => true), 30000, 'Mineflayer spawn')
  return bot
}

async function verifyPermissionCommandTree (attempt, bot) {
  await waitForCommandChildren(bot, ['help', 'warp'], 'non-operator default command tree')
  await setPermissionAndWait(attempt, bot, 'worldmanagement.command.warp', false, ['help'])
  await setPermissionAndWait(attempt, bot, 'worldmanagement.command.list', true, ['help', 'list'])
  await setPermissionAndWait(attempt, bot, 'worldmanagement.command.list', false, ['help'])

  const refreshed = waitForCommandChildren(bot, ['help', 'warp'], 'permission attachment clear', true)
  await supportCommand(
    attempt,
    `wme2e permission clear ${BOT_NAME}`,
    `WM_E2E_PERMISSION_CLEARED player=${BOT_NAME} tree=refreshed`,
    'permission attachment clear'
  )
  await refreshed
}

async function verifyLuckPermsDestinationPermission (attempt, bot) {
  await externalConsoleCommand(
    attempt,
    `lp user ${BOT_NAME} permission set ${LUCKPERMS_PERMISSION} false world=world`,
    `Set ${LUCKPERMS_PERMISSION} to false for ${BOT_NAME.toLowerCase()} in context world=world.`,
    'LuckPerms source-world deny'
  )
  await externalConsoleCommand(
    attempt,
    `lp user ${BOT_NAME} permission set ${LUCKPERMS_PERMISSION} true world=teleporttarget`,
    `Set ${LUCKPERMS_PERMISSION} to true for ${BOT_NAME.toLowerCase()} in context world=teleporttarget.`,
    'LuckPerms destination-world allow'
  )
  await supportCommand(
    attempt,
    `wme2e player permission ${BOT_NAME} ${LUCKPERMS_PERMISSION}`,
    `WM_E2E_PLAYER_PERMISSION player=${BOT_NAME} world=world permission=${LUCKPERMS_PERMISSION} value=false`,
    'LuckPerms source-world permission probe'
  )
  await consoleTeleport(attempt, BOT_NAME, 'minecraft:teleporttarget', 0, 90, 0)
  await assertPlayerWorld(attempt, bot, 'minecraft:teleporttarget', 'LuckPerms direct destination probe world')
  await supportCommand(
    attempt,
    `wme2e player permission ${BOT_NAME} ${LUCKPERMS_PERMISSION}`,
    `WM_E2E_PLAYER_PERMISSION player=${BOT_NAME} world=teleporttarget permission=${LUCKPERMS_PERMISSION} value=true`,
    'LuckPerms direct destination permission probe'
  )
  await consoleTeleport(attempt, BOT_NAME, 'minecraft:overworld', 0, 90, 0)
  await assertPlayerWorld(attempt, bot, 'minecraft:overworld', 'LuckPerms destination-context warp source')
  await supportCommand(
    attempt,
    `wme2e player luckperms ${BOT_NAME} teleporttarget ${LUCKPERMS_PERMISSION}`,
    `WM_E2E_LUCKPERMS player=${BOT_NAME} source-mode=CONTEXTUAL source-world=[world] destination=teleporttarget permission=${LUCKPERMS_PERMISSION} value=true failure=none`,
    'LuckPerms public API destination query'
  )
  const destinationWarped = waitForOutput(
    attempt.paper,
    `WM_E2E_EVENT_CHANGED_WORLD player=${BOT_NAME} from=minecraft:overworld to=minecraft:teleporttarget`
  )
  const warpCommand = `/wm warp tp teleporttarget ${LUCKPERMS_WARP}`
  assertCommandMatchesPath(warpCommand, 'wm warp tp <world> <name>')
  bot.chat(warpCommand)
  await timeout(destinationWarped, 30000, 'LuckPerms destination-context warp')
  await assertPlayerWorld(attempt, bot, 'minecraft:teleporttarget', 'LuckPerms destination-context warp world')
  await supportCommand(
    attempt,
    `wme2e player permission ${BOT_NAME} ${LUCKPERMS_PERMISSION}`,
    `WM_E2E_PLAYER_PERMISSION player=${BOT_NAME} world=teleporttarget permission=${LUCKPERMS_PERMISSION} value=true`,
    'LuckPerms destination-world permission probe'
  )
  await consoleTeleport(attempt, BOT_NAME, 'minecraft:overworld', 0, 90, 0)
  await assertPlayerWorld(attempt, bot, 'minecraft:overworld', 'LuckPerms fixture return world')
}

async function verifyExplicitTeleportPermissions (attempt, bot) {
  await setPermissionAndWait(attempt, bot, 'worldmanagement.command.warp', false, ['help'])
  await setPermissionAndWait(attempt, bot, 'worldmanagement.command.tp', true, ['help', 'tp'])
  await waitForNestedCommandChildren(bot, ['wm', 'tp'], ['self'], 'base teleport command tree')

  await setPermissionAndWaitForPath(
    attempt, bot, 'worldmanagement.command.tp.any.explicit', true,
    ['wm', 'tp'], ['self']
  )
  await assertRejectedTeleport(bot, 'explicit-only --any teleport')
  await setPermissionAndWaitForPath(
    attempt, bot, 'worldmanagement.command.tp.any.explicit', false,
    ['wm', 'tp'], ['self']
  )
  await setPermissionAndWaitForPath(
    attempt, bot, 'worldmanagement.bypass.protection', true,
    ['wm', 'tp'], ['self']
  )
  await assertRejectedTeleport(bot, 'bypass-only --any teleport')
  await setPermissionAndWaitForPath(
    attempt, bot, 'worldmanagement.command.tp.any.explicit', true,
    ['wm', 'tp'], ['--any', 'self']
  )
  await positionCommand(
    bot,
    '/wm tp --any teleporttarget 70 90 70',
    { x: 70, y: 90, z: 70 },
    'two-permission --any teleport',
    'wm tp --any <world> <x> <y> <z>'
  )
  await assertPlayerWorld(attempt, bot, 'minecraft:teleporttarget', 'two permission any target world')

  const refreshed = waitForCommandChildren(bot, ['help', 'warp'], 'explicit teleport permission clear', true)
  await supportCommand(
    attempt,
    `wme2e permission clear ${BOT_NAME}`,
    `WM_E2E_PERMISSION_CLEARED player=${BOT_NAME} tree=refreshed`,
    'explicit teleport permission clear'
  )
  await refreshed
}

async function verifyWorldProtection (attempt, bot) {
  await consoleCommand(
    attempt,
    `wm ownership owner set ${OVERWORLD_ID} ${OWNER_BOT_NAME}`,
    '世界擁有者已更新',
    'set protection fixture owner',
    'wm ownership owner set <world> <player>'
  )
  await consoleCommand(
    attempt,
    `wm ownership rank create ${OVERWORLD_ID} e2e_interact`,
    '世界階級設定已更新',
    'create protection fixture rank',
    'wm ownership rank create <world> <rank>'
  )
  await consoleCommand(
    attempt,
    `wm ownership rank perm ${OVERWORLD_ID} e2e_interact add interact`,
    '世界階級設定已更新',
    'grant protection fixture interaction',
    'wm ownership rank perm <world> <rank> <add|remove> <permission>'
  )
  await consoleCommand(
    attempt,
    `wm ownership rank set ${OVERWORLD_ID} ${BOT_NAME} e2e_interact`,
    '世界階級設定已更新',
    'assign protection fixture rank',
    'wm ownership rank set <world> <player> <rank>'
  )
  await prepareFixture(attempt, bot, `tp ${BOT_NAME} 4 80 2`, 'protection fixture position')
  await waitForPosition(bot, { x: 4, y: 80, z: 2 }, 'protection fixture position')
  await prepareFixture(attempt, bot, 'setblock 4 80 0 minecraft:stone', 'break fixture block')
  await prepareFixture(attempt, bot, 'setblock 8 79 0 minecraft:stone', 'place fixture base')
  await prepareFixture(attempt, bot, 'setblock 8 80 0 minecraft:air', 'place fixture target')
  await prepareFixture(attempt, bot, 'setblock 12 80 0 minecraft:lever[face=floor,powered=false]', 'interact fixture block')
  await prepareFixture(attempt, bot, 'setblock 14 80 0 minecraft:chest', 'container fixture block')
  await prepareFixture(attempt, bot, 'setblock 14 81 0 minecraft:air', 'container fixture clearance')

  const breakPosition = new Vec3(4, 80, 0)
  const breakBlock = await requireBlock(bot, breakPosition, 'stone', 'break fixture')
  await observedAction(
    attempt,
    `WM_E2E_EVENT_BREAK player=${BOT_NAME} cancelled=true`,
    'cancelled block break',
    () => bot.dig(breakBlock, true)
  )
  await assertServerBlock(attempt, 'minecraft:overworld', breakPosition, 'minecraft:stone', 'cancelled break state')

  await prepareFixture(
    attempt,
    bot,
    `item replace entity ${BOT_NAME} weapon.mainhand with minecraft:stone`,
    'place fixture item'
  )
  await prepareFixture(attempt, bot, `tp ${BOT_NAME} 8 80 2`, 'place fixture position')
  await waitForPosition(bot, { x: 8, y: 80, z: 2 }, 'place fixture position')
  const placePosition = new Vec3(8, 80, 0)
  const placeBase = await requireBlock(bot, new Vec3(8, 79, 0), 'stone', 'place fixture base')
  await observedAction(
    attempt,
    `WM_E2E_EVENT_PLACE player=${BOT_NAME} cancelled=true`,
    'cancelled block place',
    () => bot.placeBlock(placeBase, new Vec3(0, 1, 0))
  )
  await assertServerBlock(attempt, 'minecraft:overworld', placePosition, 'minecraft:air', 'cancelled place state')

  await consoleCommand(
    attempt,
    `wm ownership rank remove ${OVERWORLD_ID} ${BOT_NAME}`,
    '世界階級設定已更新',
    'remove protection fixture rank',
    'wm ownership rank remove <world> <player>'
  )
  await prepareFixture(attempt, bot, `tp ${BOT_NAME} 12 80 2`, 'interact fixture position')
  await waitForPosition(bot, { x: 12, y: 80, z: 2 }, 'interact fixture position')
  const leverPosition = new Vec3(12, 80, 0)
  const lever = await requireBlock(bot, leverPosition, 'lever', 'interact fixture')
  await observedAction(
    attempt,
    `WM_E2E_EVENT_INTERACT player=${BOT_NAME} use-block=DENY`,
    'denied block interaction',
    () => bot.activateBlock(lever)
  )
  assert.equal(
    bot.blockAt(leverPosition)?.getProperties().powered,
    false,
    'Denied interaction powered the lever.'
  )

  await consoleCommand(
    attempt,
    `wm ownership rank set ${OVERWORLD_ID} ${BOT_NAME} e2e_interact`,
    '世界階級設定已更新',
    'restore protection fixture rank',
    'wm ownership rank set <world> <player> <rank>'
  )
  await prepareFixture(attempt, bot, `tp ${BOT_NAME} 14 80 2`, 'container fixture position')
  await waitForPosition(bot, { x: 14, y: 80, z: 2 }, 'container fixture position')
  const chest = await requireBlock(bot, new Vec3(14, 80, 0), 'chest', 'container fixture')
  await observedAction(
    attempt,
    `WM_E2E_EVENT_CONTAINER player=${BOT_NAME} cancelled=true`,
    'cancelled container open',
    () => bot.openContainer(chest)
  )
  assert.equal(bot.currentWindow, null, 'Cancelled container open created a client window.')

  await consoleCommand(
    attempt,
    `wm ownership rank remove ${OVERWORLD_ID} ${BOT_NAME}`,
    '世界階級設定已更新',
    'clear protection fixture rank',
    'wm ownership rank remove <world> <player>'
  )
  await consoleCommand(
    attempt,
    `wm ownership rank delete ${OVERWORLD_ID} e2e_interact`,
    '世界階級設定已更新',
    'delete protection fixture rank',
    'wm ownership rank delete <world> <rank>'
  )
  await consoleCommand(
    attempt,
    `wm ownership owner remove ${OVERWORLD_ID}`,
    '世界擁有者已更新',
    'restore server owner',
    'wm ownership owner remove <world>'
  )
}

async function observedAction (attempt, marker, label, action) {
  const observed = waitForOutput(attempt.paper, marker)
  const actionFailed = Promise.resolve()
    .then(action)
    .then(() => new Promise(() => {}), failure => Promise.reject(new Error(`${label} action failed.`, { cause: failure })))
  await timeout(Promise.race([observed, actionFailed]), 10000, label)
  await new Promise(resolve => setTimeout(resolve, 250))
}

async function requireBlock (bot, position, expectedName, label) {
  const deadline = Date.now() + 5000
  while (Date.now() < deadline) {
    const block = bot.blockAt(position)
    if (block?.name === expectedName) return block
    await new Promise(resolve => setTimeout(resolve, 50))
  }
  throw new Error(`Timed out waiting for ${label} block ${expectedName}.`)
}

async function assertRejectedTeleport (bot, label) {
  const start = bot.entity.position.clone()
  bot.chat('/wm tp --any teleporttarget 70 90 70')
  await new Promise(resolve => setTimeout(resolve, 500))
  assert.ok(bot.entity.position.distanceTo(start) <= 0.25, `${label} moved the player.`)
}

async function setPermissionAndWait (attempt, bot, permission, value, expectedChildren) {
  const label = `${permission}=${value}`
  const refreshed = waitForCommandChildren(bot, expectedChildren, label, true)
  await supportCommand(
    attempt,
    `wme2e permission set ${BOT_NAME} ${permission} ${value}`,
    `WM_E2E_PERMISSION_APPLIED player=${BOT_NAME} permission=${permission} value=${value} tree=refreshed`,
    label
  )
  await refreshed
}

async function setPermissionAndWaitForPath (attempt, bot, permission, value, commandPath, expectedChildren) {
  const label = `${permission}=${value}`
  const refreshed = waitForNestedCommandChildren(bot, commandPath, expectedChildren, label, true)
  await supportCommand(
    attempt,
    `wme2e permission set ${BOT_NAME} ${permission} ${value}`,
    `WM_E2E_PERMISSION_APPLIED player=${BOT_NAME} permission=${permission} value=${value} tree=refreshed`,
    label
  )
  await refreshed
}

async function grantOperator (attempt, bot) {
  const refreshed = waitForCommandTree(
    bot,
    packet => {
      const children = literalChildren(packet, 'wm')
      return Array.isArray(children) && children.includes('create') && children.includes('warp')
    },
    'operator command tree',
    true
  )
  const granted = waitForOutput(attempt.paper, `Made ${BOT_NAME} a server operator`)
  attempt.paper.stdin.write(`op ${BOT_NAME}\n`)
  await timeout(granted, 10000, 'operator permission')
  await refreshed
}

async function deopPlayer (attempt, bot) {
  const refreshed = waitForCommandChildren(bot, ['help', 'warp'], 'non-operator command tree after deop', true)
  const removed = waitForOutput(attempt.paper, `Made ${BOT_NAME} no longer a server operator`)
  attempt.paper.stdin.write(`deop ${BOT_NAME}\n`)
  await timeout(removed, 10000, 'remove operator permission')
  await refreshed
}

function waitForCommandChildren (bot, expectedChildren, label, requireNewPacket = false) {
  return waitForNestedCommandChildren(bot, ['wm'], expectedChildren, label, requireNewPacket)
}

function waitForNestedCommandChildren (bot, commandPath, expectedChildren, label, requireNewPacket = false) {
  return waitForCommandTree(
    bot,
    packet => {
      try {
        assert.deepEqual(literalChildrenAt(packet, commandPath), expectedChildren)
        return true
      } catch {
        return false
      }
    },
    label,
    requireNewPacket
  )
}

async function waitForCommandTree (bot, predicate, label, requireNewPacket) {
  const matches = packet => predicate(packet)
  if (!requireNewPacket && bot.wmLatestCommandTree && matches(bot.wmLatestCommandTree)) return
  try {
    await timeout(onceMatching(bot._client, 'declare_commands', matches), 10000, label)
  } catch (error) {
    const latest = bot.wmLatestCommandTree
      ? JSON.stringify(literalChildren(bot.wmLatestCommandTree, 'wm'))
      : 'unavailable'
    throw new Error(`${error.message} Latest /wm children: ${latest}`, { cause: error })
  }
}

async function disconnectBot (bot, label) {
  const disconnected = onceMatching(bot, 'end', () => true)
  bot.quit('WorldManagement player E2E complete')
  await timeout(disconnected, 10000, label)
}

function findDirectories (root, name) {
  const matches = []
  for (const entry of fs.readdirSync(root, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue
    const resolved = path.join(root, entry.name)
    if (entry.name === name) {
      matches.push(resolved)
    } else {
      matches.push(...findDirectories(resolved, name))
    }
  }
  return matches
}

async function stopPaperAttempt (attempt) {
  if (!attempt || attempt.stopped) return
  attempt.stopped = true
  const { paper, logFile, logPath } = attempt
  const shutdownStart = fs.existsSync(logPath) ? fs.readFileSync(logPath, 'utf8').length : 0
  try {
    await stopChildProcess(paper, `${attempt.mode} Paper`)
  } finally {
    activePaperProcesses.delete(paper)
    await closeLogStream(logFile)
  }
  if (paper.exitCode !== 0) {
    throw new Error(`${attempt.mode} Paper exited with ${paper.exitCode ?? paper.signalCode}.`)
  }
  const shutdownLog = fs.readFileSync(logPath, 'utf8').slice(shutdownStart)
  if (!shutdownLog.includes(SHUTDOWN_COMPLETE)) {
    throw new Error(`${attempt.mode} Paper exited before WorldManagement terminal shutdown completed.`)
  }
  const shutdownFailure = SHUTDOWN_FAILURES.find(marker => shutdownLog.includes(marker))
  if (shutdownFailure) throw new Error(`${attempt.mode} Paper shutdown reported '${shutdownFailure}'.`)
}

async function restartPaperAttempt (attempt) {
  await stopPaperAttempt(attempt)
  const pluginConfigPath = path.join(attempt.serverDirectory, 'plugins', 'WorldManagement', 'config.yml')
  const pluginConfig = YAML.parse(fs.readFileSync(pluginConfigPath, 'utf8'))
  pluginConfig.lifecycle = { ...(pluginConfig.lifecycle || {}), 'fallback-world': OVERWORLD_ID }
  fs.writeFileSync(pluginConfigPath, YAML.stringify(pluginConfig))
  const modulesPath = path.join(attempt.serverDirectory, 'plugins', 'WorldManagement', 'modules.yml')
  const modules = YAML.parse(fs.readFileSync(modulesPath, 'utf8'))
  modules.protection = { ...(modules.protection || {}), enabled: false }
  fs.writeFileSync(modulesPath, YAML.stringify(modules))

  const launched = launchPaper(attempt.javaExecutable, attempt.serverDirectory, attempt.logPath, 'a')
  attempt.paper = launched.paper
  attempt.logFile = launched.logFile
  attempt.stopped = false
  await timeout(
    waitForObservedOutput(attempt.paper, '[WorldManagement] Enabled WorldManagement'),
    120000,
    `${attempt.mode} Paper identity replacement restart`
  )
  await Promise.all([
    timeout(
      waitForObservedOutput(attempt.paper, 'Protection listener disabled with protection module'),
      10000,
      `${attempt.mode} disabled protection governance`
    ),
    timeout(
      waitForObservedOutput(attempt.paper, 'Lifecycle isolation listener registered'),
      10000,
      `${attempt.mode} lifecycle isolation startup`
    )
  ])
}

async function replacePaperWorldIdentity (attempt, worldId) {
  const metadata = readWorldMetadata(attempt, worldId)
  const acceptedUuid = metadata.identity.accepted['world-uuid']
  const replacementUuid = crypto.randomUUID()
  assert.notEqual(replacementUuid, acceptedUuid)

  const paperMetadataPath = path.join(
    attempt.serverDirectory,
    'world', 'dimensions', 'minecraft', worldId, 'data', 'paper', 'metadata.dat'
  )
  const paperMetadata = (await nbt.parse(fs.readFileSync(paperMetadataPath))).parsed
  paperMetadata.value.data.value.uuid = nbt.intArray(uuidToIntArray(replacementUuid))
  writeCompressedNbt(paperMetadataPath, paperMetadata)

  return { acceptedUuid, replacementUuid }
}

function writeCompressedNbt (filePath, value) {
  fs.writeFileSync(filePath, zlib.gzipSync(nbt.writeUncompressed(value)))
}

function uuidToIntArray (uuid) {
  const compact = uuid.replaceAll('-', '')
  return Array.from({ length: 4 }, (_, index) => {
    const unsigned = Number.parseInt(compact.slice(index * 8, index * 8 + 8), 16)
    return unsigned > 0x7fffffff ? unsigned - 0x100000000 : unsigned
  })
}

function readWorldMetadata (attempt, worldId) {
  const metadataPath = path.join(
    attempt.serverDirectory,
    'plugins', 'WorldManagement', 'worlds', `${worldId}.yml`
  )
  return YAML.parse(fs.readFileSync(metadataPath, 'utf8'))
}

function setWarpRequiredPermission (attempt, worldId, warpName, permission) {
  const metadataPath = path.join(
    attempt.serverDirectory,
    'plugins', 'WorldManagement', 'worlds', `${worldId}.yml`
  )
  const metadata = YAML.parseDocument(fs.readFileSync(metadataPath, 'utf8'), { intAsBigInt: true })
  metadata.setIn(['warps', warpName, 'required-permission'], permission)
  fs.writeFileSync(metadataPath, String(metadata))
}

async function waitForIdentityState (attempt, worldId, expectedState) {
  const deadline = Date.now() + 30000
  while (Date.now() < deadline) {
    const metadata = readWorldMetadata(attempt, worldId)
    if (metadata.identity['verification-state'] === expectedState) return metadata
    await new Promise(resolve => setTimeout(resolve, 50))
  }
  throw new Error(`Timed out waiting for ${worldId} identity state ${expectedState}.`)
}

function protocolForVersion (version) {
  return minecraftData.versionsByMinecraftVersion.pc[version]?.version
}

async function command (bot, input, expected, label, commandPath, creditCoverage = true) {
  assertCommandMatchesPath(input, commandPath)
  const response = onceMatching(bot, 'messagestr', message => message.includes(expected))
  bot.chat(input)
  await timeout(response, 30000, label)
  passedCommandChecks++
  if (creditCoverage) coveredPaths.add(commandPath)
}

async function externalConsoleCommand (attempt, input, expected, label) {
  const response = waitForOutput(attempt.paper, expected)
  attempt.paper.stdin.write(`${input}\n`)
  await timeout(response, 30000, label)
}

async function positionCommand (bot, input, expectedPosition, label, commandPath) {
  assertCommandMatchesPath(input, commandPath)
  bot.chat(input)
  await waitForPosition(bot, expectedPosition, label)
  passedCommandChecks++
  coveredPaths.add(commandPath)
}

async function consoleCommand (attempt, input, expected, label, commandPath) {
  assertCommandMatchesPath(input, commandPath)
  const logPath = path.join(attempt.serverDirectory, 'logs', 'latest.log')
  const start = fs.existsSync(logPath) ? fs.readFileSync(logPath, 'utf8').length : 0
  attempt.paper.stdin.write(`${input}\n`)
  const deadline = Date.now() + 30000
  while (Date.now() < deadline) {
    if (fs.existsSync(logPath) && fs.readFileSync(logPath, 'utf8').slice(start).includes(expected)) return
    await new Promise(resolve => setTimeout(resolve, 50))
  }
  throw new Error(`Timed out waiting for console fixture ${label}: ${expected}`)
}

async function supportCommand (attempt, input, expected, label) {
  const response = waitForOutput(attempt.paper, expected)
  attempt.paper.stdin.write(`${input}\n`)
  await timeout(response, 10000, label)
}

async function consoleTeleport (
  attempt, playerName, destination, x, y, z,
  source = destination === 'minecraft:overworld' ? 'minecraft:teleporttarget' : 'minecraft:overworld'
) {
  const changedWorld = waitForOutput(
    attempt.paper,
    `WM_E2E_EVENT_CHANGED_WORLD player=${playerName} from=${source} to=${destination}`
  )
  attempt.paper.stdin.write(`execute in ${destination} run tp ${playerName} ${x} ${y} ${z}\n`)
  await timeout(changedWorld, 10000, `console teleport ${source} to ${destination}`)
}


async function prepareFixture (attempt, bot, input, label) {
  const marker = `WM_E2E_FIXTURE_${label.replace(/\W+/g, '_')}`
  const prepared = onceMatching(bot, 'messagestr', message => message.includes(marker))
  attempt.paper.stdin.write(`${input}\n`)
  attempt.paper.stdin.write(`tellraw ${BOT_NAME} {"text":"${marker}"}\n`)
  await timeout(prepared, 10000, label)
}

async function assertPlayerWorld (attempt, bot, expectedWorld, label) {
  const marker = `WM_E2E_WORLD_${label.replace(/\W+/g, '_')}`
  const response = onceMatching(bot, 'messagestr', message => message.includes(marker))
  attempt.paper.stdin.write(
    `execute as ${BOT_NAME} at @s if dimension ${expectedWorld} run tellraw @s {"text":"${marker}"}\n`
  )
  await timeout(response, 10000, label)
}

async function assertServerBlock (attempt, world, position, block, label) {
  const marker = `WM_E2E_BLOCK_${label.replace(/\W+/g, '_')}`
  const response = waitForOutput(attempt.paper, marker)
  attempt.paper.stdin.write(
    `execute in ${world} if block ${position.x} ${position.y} ${position.z} ${block} run say ${marker}\n`
  )
  await timeout(response, 10000, label)
}

async function waitForPosition (bot, expected, label) {
  await waitForPositionCondition(bot, position => position.distanceTo(expected) <= 1, label)
}

async function waitUntilMovedFrom (bot, previous, label) {
  await waitForPositionCondition(bot, position => position.distanceTo(previous) > 2, label)
}

async function waitForPositionCondition (bot, predicate, label) {
  await new Promise((resolve, reject) => {
    let interval
    let timer
    const cleanup = () => {
      clearInterval(interval)
      clearTimeout(timer)
    }
    const inspect = () => {
      const position = bot.entity?.position
      if (!position || !predicate(position)) return
      cleanup()
      resolve()
    }
    interval = setInterval(inspect, 50)
    timer = setTimeout(() => {
      cleanup()
      reject(new Error(`Timed out waiting for ${label}.`))
    }, 10000)
    inspect()
  })
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

function waitForOutput (process, marker) {
  return process.wmOutputMonitor.waitForNext(marker)
}

function waitForObservedOutput (process, marker) {
  return process.wmOutputMonitor.waitFor(marker)
}

main().catch(error => {
  const reported = suiteDeadline.expired() ? suiteDeadline.failure(error) : error
  console.error(reported.stack || reported)
  process.exitCode = 1
}).finally(() => {
  suiteDeadline.close()
})