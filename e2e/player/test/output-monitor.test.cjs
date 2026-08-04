const assert = require('node:assert/strict')
const { EventEmitter } = require('node:events')
const { PassThrough } = require('node:stream')
const test = require('node:test')

const { createOutputMonitor } = require('./output-monitor.cjs')

function paperProcess () {
  const process = new EventEmitter()
  process.stdout = new PassThrough()
  process.stderr = new PassThrough()
  process.exitCode = null
  process.signalCode = null
  return process
}

test('matches output written before the waiter is registered', async () => {
  const process = paperProcess()
  const monitor = createOutputMonitor(process)
  process.stdout.write('[WorldManagement] LuckPerms: available\n')

  await monitor.waitFor('LuckPerms: available')

  assert.match(monitor.tail(), /LuckPerms: available/)
})

test('matches a marker split across output chunks', async () => {
  const process = paperProcess()
  const monitor = createOutputMonitor(process)
  const observed = monitor.waitFor('[WorldManagement] Enabled WorldManagement')

  process.stdout.write('[WorldManagement] Enabled World')
  process.stdout.write('Management\n')

  await observed
})

test('waitForNext ignores a historical marker until it is emitted again', async () => {
  const process = paperProcess()
  const monitor = createOutputMonitor(process)
  process.stdout.write('runtime-ready\n')
  let resolved = false
  const observed = monitor.waitForNext('runtime-ready').then(() => { resolved = true })

  await new Promise(resolve => setImmediate(resolve))
  assert.equal(resolved, false)
  process.stdout.write('runtime-')
  process.stdout.write('ready\n')

  await observed
})