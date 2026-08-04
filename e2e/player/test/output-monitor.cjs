const ANSI_ESCAPE = /\u001B\[[0-?]*[ -/]*[@-~]/g
const DEFAULT_TAIL_LIMIT = 1024 * 1024

function createOutputMonitor (process, tailLimit = DEFAULT_TAIL_LIMIT) {
  if (!Number.isSafeInteger(tailLimit) || tailLimit <= 0) {
    throw new Error('Output monitor tail limit must be a positive safe integer.')
  }
  let tail = ''
  const waiters = new Set()

  const inspect = data => {
    const output = data.toString('utf8').replace(ANSI_ESCAPE, '')
    tail = (tail + output).slice(-tailLimit)
    for (const waiter of [...waiters]) {
      const observed = waiter.pending + output
      if (!observed.includes(waiter.marker)) {
        waiter.pending = observed.slice(-Math.max(0, waiter.marker.length - 1))
        continue
      }
      waiters.delete(waiter)
      waiter.resolve()
    }
  }
  const exited = (code, signal) => {
    for (const waiter of waiters) {
      waiter.reject(new Error(
        `Paper exited with ${code ?? signal} before output marker: ${waiter.marker}`
      ))
    }
    waiters.clear()
  }
  process.stdout.on('data', inspect)
  process.stderr.on('data', inspect)
  process.on('exit', exited)

  return {
    tail: () => tail,
    waitFor: marker => {
      if (tail.includes(marker)) return Promise.resolve()
      return waitForNext(marker)
    },
    waitForNext
  }

  function waitForNext (marker) {
    if (process.exitCode !== null || process.signalCode !== null) {
      return Promise.reject(new Error(
        `Paper exited with ${process.exitCode ?? process.signalCode} before output marker: ${marker}`
      ))
    }
    return new Promise((resolve, reject) => waiters.add({ marker, pending: '', resolve, reject }))
  }
}

module.exports = { createOutputMonitor }