const { finished } = require('node:stream/promises')

function processExited (child) {
  return child.exitCode !== null || child.signalCode !== null
}

function createChildProcessDeadline (children, timeoutMilliseconds, label) {
  let expired = false
  const timer = setTimeout(() => {
    expired = true
    for (const child of children) {
      if (!processExited(child)) child.kill('SIGKILL')
    }
  }, timeoutMilliseconds)
  timer.unref()
  return {
    close: () => clearTimeout(timer),
    expired: () => expired,
    failure: cause => new Error(`${label} exceeded its ${timeoutMilliseconds}ms deadline.`, { cause })
  }
}

function withTimeout (promise, timeoutMilliseconds, label) {
  let timer
  return Promise.race([
    promise,
    new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error(`Timed out waiting for ${label}.`)), timeoutMilliseconds)
    })
  ]).finally(() => clearTimeout(timer))
}

function waitForExit (child, timeoutMilliseconds, label) {
  if (processExited(child)) return Promise.resolve()
  return withTimeout(new Promise((resolve, reject) => {
    const cleanup = () => {
      child.off('exit', exited)
      child.off('error', failed)
    }
    const exited = () => {
      cleanup()
      resolve()
    }
    const failed = error => {
      cleanup()
      reject(error)
    }
    child.once('exit', exited)
    child.once('error', failed)
    if (processExited(child)) exited()
  }), timeoutMilliseconds, label)
}

function waitForClose (child, timeoutMilliseconds, label) {
  if (child.stdout?.closed !== false && child.stderr?.closed !== false) return Promise.resolve()
  return withTimeout(new Promise((resolve, reject) => {
    const cleanup = () => {
      child.off('close', closed)
      child.off('error', failed)
    }
    const closed = () => {
      cleanup()
      resolve()
    }
    const failed = error => {
      cleanup()
      reject(error)
    }
    child.once('close', closed)
    child.once('error', failed)
    if (child.stdout?.closed !== false && child.stderr?.closed !== false) closed()
  }), timeoutMilliseconds, label)
}

async function stopChildProcess (child, label, gracefulTimeoutMilliseconds = 30000, forcedTimeoutMilliseconds = 10000) {
  if (processExited(child)) return
  try {
    if (child.stdin?.writable) child.stdin.write('stop\n')
    await waitForExit(child, gracefulTimeoutMilliseconds, `${label} shutdown`)
    return
  } catch (gracefulFailure) {
    if (processExited(child)) return
    if (!child.kill('SIGKILL')) {
      throw new Error(`Could not forcibly stop ${label}.`, { cause: gracefulFailure })
    }
    try {
      await waitForExit(child, forcedTimeoutMilliseconds, `forced ${label} shutdown`)
    } catch (forcedFailure) {
      throw new AggregateError([gracefulFailure, forcedFailure], `${label} did not stop after forced termination.`)
    }
  }
}

async function closeLogStream (stream, timeoutMilliseconds = 5000) {
  if (stream.destroyed) return
  stream.end()
  try {
    await withTimeout(finished(stream), timeoutMilliseconds, 'E2E log stream close')
  } catch (error) {
    stream.destroy()
    throw error
  }
}

module.exports = {
  closeLogStream,
  createChildProcessDeadline,
  processExited,
  stopChildProcess,
  waitForClose,
  waitForExit,
  withTimeout
}