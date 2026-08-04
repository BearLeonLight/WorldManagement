const assert = require('node:assert/strict')
const { spawn } = require('node:child_process')
const test = require('node:test')
const {
  createChildProcessDeadline,
  processExited,
  stopChildProcess,
  waitForClose,
  waitForExit
} = require('./process-control.cjs')

test('waitForExit handles a process that exits immediately', async () => {
  const child = spawn(process.execPath, ['-e', ''], { stdio: 'ignore' })

  await waitForExit(child, 2000, 'immediate fixture')

  assert.equal(processExited(child), true)
})

test('waitForClose waits for inherited child output after the process exits', async () => {
  const fixture = [
    "const { spawn } = require('node:child_process')",
    `spawn(process.execPath, ['-e', ${JSON.stringify("setTimeout(() => process.stdout.write('tail'), 100)")}], { stdio: ['ignore', 'inherit', 'ignore'] })`
  ].join('; ')
  const child = spawn(process.execPath, ['-e', fixture], { stdio: ['ignore', 'pipe', 'ignore'] })
  let output = ''
  child.stdout.on('data', chunk => { output += chunk })

  await waitForExit(child, 2000, 'inherited-output fixture exit')
  await waitForClose(child, 2000, 'inherited-output fixture close')

  assert.equal(output, 'tail')
})

test('stopChildProcess forcibly terminates an unresponsive process within its deadline', async () => {
  const child = spawn(
    process.execPath,
    ['-e', 'process.stdin.resume(); setInterval(() => {}, 1000)'],
    { stdio: ['pipe', 'ignore', 'ignore'] }
  )

  await stopChildProcess(child, 'unresponsive fixture', 50, 2000)

  assert.equal(processExited(child), true)
})

test('a suite deadline terminates every registered child process', async () => {
  const child = spawn(
    process.execPath,
    ['-e', 'process.stdin.resume(); setInterval(() => {}, 1000)'],
    { stdio: ['pipe', 'ignore', 'ignore'] }
  )
  const deadline = createChildProcessDeadline(new Set([child]), 20, 'fixture suite')

  try {
    await waitForExit(child, 2000, 'deadline fixture')
    assert.equal(deadline.expired(), true)
    assert.match(deadline.failure(new Error('fixture')).message, /exceeded/)
  } finally {
    deadline.close()
  }
})